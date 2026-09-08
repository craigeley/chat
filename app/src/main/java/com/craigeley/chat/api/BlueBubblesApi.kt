package com.craigeley.chat.api

import com.craigeley.chat.Attachment
import com.craigeley.chat.ChatMessage
import com.craigeley.chat.Conversation
import com.craigeley.chat.IncomingMessage
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/** Carries the HTTP status so the ViewModel can treat 401/403 as a bad password. */
class ApiException(val code: Int, message: String) : IOException(message) {
    val isAuthError: Boolean get() = code == 401 || code == 403
}

/**
 * What `GET /server/info` tells us. [reachable] is the password/connectivity check
 * setup relies on; [privateApiReady] is true only when the server has the Private
 * API enabled *and* the Messages helper is actually connected — the gate for
 * offering tapbacks (sending needs both; the SIP/helper setup is in the README).
 */
data class ServerInfo(val reachable: Boolean, val privateApiReady: Boolean)

/**
 * Minimal BlueBubbles Server REST client — plain [HttpURLConnection] + `org.json`,
 * no networking dependency. Auth is the server password passed as
 * the `password` query param on every call. The base URL is the user-configured
 * server (`Store.baseUrl`), typically reached over Tailscale Serve, passed into
 * the constructor. The live event feed is separate (Socket.IO, see the `socket`
 * package); message parsing is shared with it via the [companion object].
 *
 * Timestamps come back as epoch millis and are carried through unchanged — the UI
 * sorts on them, which is the whole point of doing this from scratch.
 */
class BlueBubblesApi(private val baseUrl: String, private val password: String) {

    /**
     * `GET /api/v1/server/info` — validates the URL/password (setup relies on
     * [ServerInfo.reachable]) and reads whether the Private API is live
     * (`private_api` && `helper_connected`), so the app can offer tapbacks only
     * when the server can actually send them.
     */
    fun serverInfo(): ServerInfo {
        val (code, text) = request("GET", "/api/v1/server/info", null)
        if (code !in 200..299) return ServerInfo(reachable = false, privateApiReady = false)
        val data = dataObject(text)
        val privateApi = data?.optBoolean("private_api", false) == true
        val helper = data?.optBoolean("helper_connected", false) == true
        return ServerInfo(reachable = true, privateApiReady = privateApi && helper)
    }

    /**
     * The conversation list, in true most-recent-activity order.
     *
     * Deliberately NOT built from `chat/query`: that endpoint sorts by an
     * unreliable `lastmessage` cache, so on a large account (this one has ~2700
     * chats) a freshly-active chat can fall outside its first page entirely and
     * never appear. Instead we drive the list from a single `message/query` DESC
     * sweep — the newest [limit] messages globally — taking the first (newest)
     * message seen per chat and reading each chat's metadata from the embedded
     * chat object (`with:["chats","chats.participants"]`). The result is already
     * newest-first; the ViewModel re-sorts defensively.
     *
     * Trade-off: only chats with activity inside the sweep window appear — i.e.
     * the recently-active ones, which is exactly what a messages list shows.
     *
     * A group that iMessage has forked into sibling rooms (same name + participants,
     * different guid — messages split across rooms by "era") is collapsed into one
     * conversation spanning all those guids, so it shows as a single row and its
     * thread merges messages from every room (see [groupIdentity] + ChatViewModel.open).
     */
    fun conversations(limit: Int = 1000): List<Conversation> = deriveConversations(sweepRows(limit))

    /** One row of the conversation sweep: a parsed message plus the raw chat
     *  objects it belongs to. The ViewModel retains the swept rows so a delta
     *  refresh can merge freshly-fetched ones and re-derive the list (LP3-18). */
    class SweepRow(val chats: JSONArray, val msg: ChatMessage)

    /**
     * Fetches the sweep [conversations] derives from: the newest [limit] messages
     * globally, each carrying its embedded chat objects. With [after] set, only
     * messages newer than that epoch-millis date transfer — the delta path, a few
     * KB against the full sweep's ~2-3 MB (LP3-18).
     */
    fun sweepRows(limit: Int = 1000, after: Long? = null): List<SweepRow> {
        val body = JSONObject()
            .put("limit", limit)
            .put("offset", 0)
            // `attachment` so an attachment-only message gets a real list preview
            // ("Photo" etc.) rather than a blank line; without it the sweep can't
            // tell text-less messages apart from genuinely empty ones.
            .put("with", JSONArray().put("chats").put("chats.participants").put("attachment"))
            .put("sort", "DESC")
        if (after != null) body.put("after", after)
        val respText = requestChecked("POST", "/api/v1/message/query", body, what = "message/query")
        val data = JSONObject(respText).optJSONArray("data") ?: JSONArray()
        val parsed = ArrayList<SweepRow>(data.length())
        for (i in 0 until data.length()) {
            val m = data.getJSONObject(i)
            val chats = m.optJSONArray("chats") ?: continue
            parsed.add(SweepRow(chats, parseMessage(m)))
        }
        return parsed
    }

    /** The list-derivation half of [conversations], usable on any row set — the
     *  full sweep or a delta-merged one. Row order doesn't matter (re-sorted
     *  newest-first here, which every pass below depends on). */
    fun deriveConversations(sweep: List<SweepRow>): List<Conversation> {
        val rows = sweep.sortedByDescending { it.msg.date }.map { it.chats to it.msg }
        // Every swept message indexed by guid, so a tapback can describe its
        // target ("an image" vs a quote) for the list. The target is an *older*
        // message (later in this DESC ordering), so we need the full index up front.
        val msgByGuid = HashMap<String, ChatMessage>()
        // Newest non-reaction message date per room — the signal for which sibling of a
        // forked group is the *live* one (the send target). A tapback can land in a dead
        // old room, so the newest message of *any* kind isn't a reliable send target.
        val realDateByGuid = HashMap<String, Long>()
        for ((_, msg) in rows) {
            msgByGuid[msg.guid] = msg
        }
        // Pass 1: one row per chat room, newest activity first. The newest message
        // sets recency (lastDate) — a tapback bumps the thread, like iMessage. When
        // that newest message is a tapback we surface it as "Liz loved an image"
        // ([lastReaction]); otherwise the preview is the newest real message text, and
        // a row whose newest message is a *reaction removal* falls back to that too.
        val byGuid = LinkedHashMap<String, Conversation>()
        val previewFinal = HashSet<String>() // guids whose text preview is a real (non-reaction) message
        // Per room: is its newest incoming message still unread? Decided by the first
        // (= newest, DESC sweep) non-group-event message seen — chat.db stamps
        // dateRead on incoming messages when the chat is read on any device, so
        // `!fromMe && dateRead == 0` means unread account-wide. Group events are
        // skipped: they never get a dateRead and would pin the marker forever.
        val unreadByGuid = HashMap<String, Boolean>()
        for ((chats, msg) in rows) {
            for (j in 0 until chats.length()) {
                val chat = chats.getJSONObject(j)
                val guid = chat.optString("guid")
                if (guid.isBlank()) continue
                if (!msg.isReaction) {
                    realDateByGuid[guid] = maxOf(realDateByGuid[guid] ?: 0L, msg.date)
                }
                if (!msg.isGroupEvent && guid !in unreadByGuid) {
                    unreadByGuid[guid] = !msg.fromMe && msg.dateRead == 0L
                }
                val existing = byGuid[guid]
                // Group events (renames, member changes) bump recency like anything
                // else, but the *text* preview should be real speech — they have no
                // text of their own and would otherwise blank the row's preview.
                val isSpeech = !msg.isReaction && !msg.isGroupEvent
                if (existing == null) {
                    // [lastReaction] is set only when the newest message is a real
                    // tapback; [lastText] still gets the older real message below as a
                    // fallback. A reaction *removal* sets neither and just bumps recency.
                    val reaction = msg.reactionPreview { g -> msgByGuid[g] }
                    byGuid[guid] = chatToConversation(chat, guid, msg.previewText, msg.date, msg.fromMe)
                        .copy(lastReaction = reaction)
                    if (isSpeech) previewFinal.add(guid)
                } else if (isSpeech && guid !in previewFinal) {
                    // Older than the row's newest message, but the first real one —
                    // upgrade the text preview while keeping the newest date / reaction.
                    byGuid[guid] = existing.copy(lastText = msg.previewText, lastFromMe = msg.fromMe)
                    previewFinal.add(guid)
                }
            }
        }
        // Pass 2: collapse a group that iMessage has forked into sibling rooms (same
        // name + identical participants, different guid) into a single conversation
        // spanning all their guids. Keyed only for groups; 1:1 chats are left alone.
        val groups = LinkedHashMap<String, MutableList<Conversation>>()
        for ((guid, conv) in byGuid) {
            val key = groupIdentity(conv) ?: guid // non-groups key by their own guid (never merge)
            groups.getOrPut(key) { mutableListOf() }.add(conv.copy(unread = unreadByGuid[guid] == true))
        }
        return groups.values.map { rooms ->
            if (rooms.size == 1) return@map rooms[0]
            // A forked group. Display fields (preview, recency, name) come from the
            // newest-overall room, so a tapback in any room still bumps the list. But the
            // *send target* — Conversation.guid / guids[0] — must be the live room: the
            // one iMessage routes real (non-reaction) messages to. A tapback can land in a
            // dead old room and make it newest by date, so ordering by the newest message
            // of any kind would aim sends at a room AppleScript can't send to ("Couldn't
            // send"). Order the spanned guids by newest non-reaction message instead.
            val display = rooms.maxByOrNull { it.lastDate } ?: rooms[0]
            val sendOrder = rooms.sortedByDescending { realDateByGuid[it.guid] ?: 0L }
            display.copy(guid = sendOrder.first().guid, guids = sendOrder.map { it.guid })
        }
    }

    /** A stable identity for a group, so forked sibling rooms collapse: its display
     *  name plus its sorted participant set. Null for 1:1s / participant-less chats —
     *  those never merge (keyed by their own guid). */
    private fun groupIdentity(c: Conversation): String? {
        if (!c.isGroup || c.participants.isEmpty()) return null
        return "g|${c.displayName}|${c.participants.sorted().joinToString(",")}"
    }

    /**
     * The socket's catch-up query: every message created after [after] (epoch
     * millis), oldest first, decoded exactly like a socket event ([messageEvent] —
     * embedded chats for routing, `handle` for the sender name in a notification)
     * so `SocketService` can replay a gap through its normal delivery path. Newest
     * [limit] only; a longer gap than that is the ViewModel's delta refresh's job.
     */
    fun messagesSince(after: Long, limit: Int = 100): List<IncomingMessage> {
        val body = JSONObject()
            .put("limit", limit)
            .put("offset", 0)
            .put("with", JSONArray().put("chats").put("handle").put("attachment"))
            .put("sort", "DESC")
            .put("after", after)
        val respText = requestChecked("POST", "/api/v1/message/query", body, what = "message/query")
        val data = JSONObject(respText).optJSONArray("data") ?: JSONArray()
        return (0 until data.length())
            .mapNotNull { messageEvent(data.getJSONObject(it), isNew = true) }
            .sortedBy { it.message.date }
    }

    /** `GET /api/v1/chat/:guid/message` — messages in one conversation, newest first. */
    fun messages(chatGuid: String, limit: Int = 100, offset: Int = 0): List<ChatMessage> {
        val path = "/api/v1/chat/${enc(chatGuid)}/message"
        val query = "with=handle,attachment&sort=DESC&limit=$limit&offset=$offset"
        val text = requestChecked("GET", path, null, what = "message query", extraQuery = query)
        val data = JSONObject(text).optJSONArray("data") ?: JSONArray()
        return (0 until data.length()).map { parseMessage(data.getJSONObject(it)) }
    }

    /**
     * `POST /api/v1/message/text` — sends a text into a chat. Only `chatGuid` and
     * `message` are required; we also pass a client `tempGuid` so the echoed
     * new-message can be correlated. [method] is `private-api` when the server's
     * Private API is live, else `apple-script`. We prefer `private-api` for group
     * chats: the server's AppleScript path tries the group-capable `sendMessage`
     * script first, but if that fails to resolve the chat (e.g. our guids carry an
     * `any;+;chat…` service prefix that `chat id "…"` may not match) it falls back to
     * the DM-only script, which errors with "Can't use the send message (fallback)
     * script to text a group chat!". The Private API resolves the chat by its DB
     * identity and sidesteps that. (Plain AppleScript *can* text groups when the
     * standard script resolves — this isn't a hard limitation.) Returns the created
     * message (real guid) parsed from the response, falling back to a synthetic one
     * if the body is unexpected.
     */
    fun send(
        chatGuid: String,
        text: String,
        tempGuid: String,
        method: String = "apple-script",
        replyToGuid: String? = null,
    ): ChatMessage {
        val body = JSONObject()
            .put("chatGuid", chatGuid)
            .put("tempGuid", tempGuid)
            .put("message", text)
            .put("method", method)
        if (replyToGuid != null) {
            // An inline reply (`selectedMessageGuid`) — Private-API only; callers
            // gate the reply UI on the Private API being live.
            body.put("selectedMessageGuid", replyToGuid)
            body.put("partIndex", 0)
        }
        val resp = requestChecked("POST", "/api/v1/message/text", body, what = "send")
        return dataObject(resp)?.let { parseMessage(it) }
            ?: ChatMessage(tempGuid, text, System.currentTimeMillis(), fromMe = true, sender = null)
    }

    /**
     * `POST /api/v1/message/react` — sends a tapback onto [selectedMessageGuid].
     * Private-API only (gated in the UI on [serverInfo]); [reaction] is a
     * [ReactionType.apiValue], prefixed `-` to remove. [partIndex] is 0 for a
     * normal single-part message. Returns the created reaction message (real guid)
     * parsed from the response, so the ViewModel can reconcile its optimistic echo.
     */
    fun react(
        chatGuid: String,
        selectedMessageGuid: String,
        reaction: String,
        partIndex: Int = 0,
    ): ChatMessage {
        val body = JSONObject()
            .put("chatGuid", chatGuid)
            .put("selectedMessageGuid", selectedMessageGuid)
            .put("reaction", reaction)
            .put("partIndex", partIndex)
        val resp = requestChecked("POST", "/api/v1/message/react", body, what = "react")
        return dataObject(resp)?.let { parseMessage(it) }
            ?: throw IOException("react: no message returned")
    }

    /**
     * `POST /api/v1/chat/:guid/read` — marks the chat read (Private-API only).
     * Clears its unread state, which iMessage syncs to the account's other devices,
     * and sends a read receipt per the conversation's setting (so it just mirrors
     * reading on another device). Idempotent; callers fire it best-effort.
     */
    fun markRead(chatGuid: String) {
        requestChecked("POST", "/api/v1/chat/${enc(chatGuid)}/read", null, what = "mark read")
    }

    /**
     * `POST`/`DELETE /api/v1/chat/:guid/typing` — show or clear your typing bubble
     * on the other party's device (Private-API only). Best-effort; callers ignore
     * failures and gate on the Private API being live.
     */
    fun startTyping(chatGuid: String) {
        request("POST", "/api/v1/chat/${enc(chatGuid)}/typing", null)
    }

    fun stopTyping(chatGuid: String) {
        request("DELETE", "/api/v1/chat/${enc(chatGuid)}/typing", null)
    }

    /**
     * `DELETE /api/v1/chat/:guid` — permanently deletes a chat from Messages on the
     * Mac (Private-API only). The server tells the helper to delete it, then waits up
     * to ~30s for the local DB to reflect the removal, so this call can be slow.
     * Irreversible; with Messages-in-iCloud on it can also clear from other devices.
     */
    fun deleteChat(chatGuid: String) {
        requestChecked("DELETE", "/api/v1/chat/${enc(chatGuid)}", null, what = "delete chat")
    }

    /** `PUT /api/v1/chat/:guid` — renames a group (Private-API only; the server
     *  rejects renaming a 1:1). The rename shows to every member, like iMessage. */
    fun renameChat(chatGuid: String, displayName: String) {
        val body = JSONObject().put("displayName", displayName)
        requestChecked("PUT", "/api/v1/chat/${enc(chatGuid)}", body, what = "rename")
    }

    /** `POST /api/v1/chat/:guid/participant/add` — adds [address] to a group
     *  (Private-API only). iMessage may fork the group into a new room for the new
     *  membership; a refresh picks that up. */
    fun addParticipant(chatGuid: String, address: String) {
        val body = JSONObject().put("address", address)
        requestChecked("POST", "/api/v1/chat/${enc(chatGuid)}/participant/add", body, what = "add member")
    }

    /** `POST /api/v1/chat/:guid/participant/remove` — removes [address] from a
     *  group (Private-API only). */
    fun removeParticipant(chatGuid: String, address: String) {
        val body = JSONObject().put("address", address)
        requestChecked("POST", "/api/v1/chat/${enc(chatGuid)}/participant/remove", body, what = "remove member")
    }

    /** `POST /api/v1/chat/:guid/leave` — leaves a group (Private-API only). */
    fun leaveChat(chatGuid: String) {
        requestChecked("POST", "/api/v1/chat/${enc(chatGuid)}/leave", null, what = "leave")
    }

    /**
     * `GET /api/v1/handle/availability/imessage` — whether [address] can receive
     * iMessages (Private-API only). Used to warn before starting a chat with a
     * non-iMessage number — such a send otherwise *appears* to work and dies
     * silently on the Mac.
     */
    fun iMessageAvailable(address: String): Boolean {
        val text = requestChecked(
            "GET", "/api/v1/handle/availability/imessage", null,
            what = "availability", extraQuery = "address=${enc(address)}",
        )
        return dataObject(text)?.optBoolean("available", false) == true
    }

    /**
     * `POST /api/v1/message/attachment` — sends a file into a chat as multipart
     * form-data (the one call that isn't JSON, so it's built by hand rather than
     * via [request]). Like [send] we pass a `tempGuid` to correlate the echo and a
     * [method] (`private-api` when live, else `apple-script`); see [send] for why
     * `private-api` is preferred for group chats. Returns the created message parsed
     * from the response, falling back to a placeholder if the body is unexpected.
     */
    fun sendAttachment(
        chatGuid: String,
        bytes: ByteArray,
        filename: String,
        mimeType: String,
        tempGuid: String,
        method: String = "apple-script",
    ): ChatMessage {
        val safeFile = filename.replace("\"", "").ifBlank { "image.jpg" }
        val boundary = "chatBoundary" + tempGuid.filter { it.isLetterOrDigit() }
        val crlf = "\r\n"
        fun field(name: String, value: String) =
            "--$boundary$crlf" +
                "Content-Disposition: form-data; name=\"$name\"$crlf$crlf" +
                "$value$crlf"
        val preamble = buildString {
            append(field("chatGuid", chatGuid))
            append(field("tempGuid", tempGuid))
            append(field("name", safeFile))
            append(field("method", method))
            append("--$boundary$crlf")
            append("Content-Disposition: form-data; name=\"attachment\"; filename=\"$safeFile\"$crlf")
            append("Content-Type: $mimeType$crlf$crlf")
        }
        val epilogue = "$crlf--$boundary--$crlf"

        val url = URL("$baseUrl/api/v1/message/attachment?password=" + enc(password))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setChunkedStreamingMode(0) // stream the file, don't buffer it all in RAM
        }
        conn.outputStream.use { out ->
            out.write(preamble.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.write(epilogue.toByteArray(Charsets.UTF_8))
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw ApiException(code, "send attachment failed ($code)")
        return dataObject(resp)?.let { parseMessage(it) }
            ?: ChatMessage(tempGuid, ChatMessage.ATTACHMENT_PLACEHOLDER, System.currentTimeMillis(), fromMe = true, sender = null)
    }

    /**
     * `GET /api/v1/attachment/:guid/download` — streams an attachment's raw bytes
     * to [dest]. Used by the inline image loader; deliberately not routed through
     * [request] (which buffers a text body) since these are binary and large.
     */
    fun downloadAttachment(guid: String, dest: File, maxDim: Int? = null) {
        val url = URL(buildString {
            append(baseUrl).append("/api/v1/attachment/").append(enc(guid)).append("/download")
            append("?password=").append(enc(password))
            // Server-side downscale: the server resizes images to fit width/height
            // before sending, so we don't ship a 4 MB original just to decode it
            // down to screen size anyway (LP3-19). Non-images (and GIFs) ignore
            // the params and arrive untouched.
            if (maxDim != null) append("&width=").append(maxDim).append("&height=").append(maxDim)
        })
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        val code = conn.responseCode
        if (code !in 200..299) throw ApiException(code, "attachment download failed ($code)")
        conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
    }

    /**
     * `POST /api/v1/chat/new` — starts a new chat by sending its first message.
     * macOS Big Sur+ requires a message (AppleScript can't create an empty chat),
     * so this both creates the chat and sends. Returns the new chat's guid.
     *
     * A single address goes over **AppleScript** (rock-solid, no Private API
     * needed). Two or more addresses form a **group**, which AppleScript can't do
     * reliably on modern macOS — that path requires `method:"private-api"`, so the
     * caller must gate group creation on the server's Private API being live. The
     * server assigns the group its own guid (`any;+;<hex>`, style 43), unguessable
     * client-side, so callers must use the returned guid rather than construct one.
     */
    fun newChat(addresses: List<String>, text: String, service: String = "iMessage"): String {
        val isGroup = addresses.size > 1
        val body = JSONObject()
            .put("addresses", JSONArray().apply { addresses.forEach { put(it) } })
            .put("message", text)
            .put("service", service)
            .put("method", if (isGroup) "private-api" else "apple-script")
        val resp = requestChecked("POST", "/api/v1/chat/new", body, what = "new chat")
        val guid = dataObject(resp)?.optString("guid")
        return guid?.takeIf { it.isNotBlank() } ?: throw IOException("new chat: no guid returned")
    }

    /**
     * `GET /api/v1/contact` — the Mac's whole address book, flattened to
     * (address, name) pairs (every phone number and email maps to the contact's
     * display name). [com.craigeley.chat.Contacts.from] turns this into a lookup.
     */
    fun contacts(): List<Pair<String, String>> {
        val text = requestChecked("GET", "/api/v1/contact", null, what = "contact")
        val data = JSONObject(text).optJSONArray("data") ?: JSONArray()
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until data.length()) {
            val c = data.getJSONObject(i)
            val name = c.optString("displayName").ifBlank {
                listOf(c.optString("firstName"), c.optString("lastName"))
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
            }
            if (name.isBlank()) continue
            for (field in listOf("phoneNumbers", "emails")) {
                val arr = c.optJSONArray(field) ?: continue
                for (j in 0 until arr.length()) {
                    arr.getJSONObject(j).optString("address").takeIf { it.isNotBlank() }
                        ?.let { out.add(it to name) }
                }
            }
        }
        return out
    }

    // ---- parsing ----------------------------------------------------------

    private fun chatToConversation(
        chat: JSONObject,
        guid: String,
        lastText: String,
        lastDate: Long,
        lastFromMe: Boolean,
    ): Conversation {
        val participants = chat.optJSONArray("participants")?.let { arr ->
            (0 until arr.length()).mapNotNull {
                arr.getJSONObject(it).optString("address").takeIf { a -> a.isNotBlank() }
            }
        } ?: emptyList()
        // 1:1 chats (style 45) come back with an empty participants list, but the
        // chatIdentifier is the other party's address — use it so names resolve.
        val resolved = participants.ifEmpty {
            chat.optString("chatIdentifier")
                .takeIf { it.isNotBlank() && !it.startsWith("chat") }
                ?.let { listOf(it) }
                ?: emptyList()
        }
        return Conversation(
            guid = guid,
            displayName = chat.optString("displayName", ""),
            participants = resolved,
            isGroup = chat.optInt("style") == 43, // 43 = group, 45 = one-on-one
            lastText = lastText,
            lastDate = lastDate,
            lastFromMe = lastFromMe,
        )
    }

    // ---- transport --------------------------------------------------------

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** [request], returning the body text and throwing an [ApiException] labelled
     *  [what] on a non-2xx status — the shape almost every endpoint wants. */
    private fun requestChecked(
        method: String,
        path: String,
        body: JSONObject?,
        what: String,
        extraQuery: String? = null,
    ): String {
        val (code, text) = request(method, path, body, extraQuery)
        if (code !in 200..299) throw ApiException(code, "$what failed ($code)")
        return text
    }

    /** The response body's `data` object, or null when the shape is unexpected. */
    private fun dataObject(resp: String): JSONObject? =
        runCatching { JSONObject(resp).optJSONObject("data") }.getOrNull()

    private fun request(
        method: String,
        path: String,
        body: JSONObject?,
        extraQuery: String? = null,
    ): Pair<Int, String> {
        val url = buildString {
            append(baseUrl).append(path)
            append("?password=").append(enc(password))
            if (extraQuery != null) append("&").append(extraQuery)
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        // No disconnect(): closing the stream after a full read returns the socket
        // to the keep-alive pool; disconnect() would evict it and every call over
        // the Tailscale tunnel would pay a fresh TLS handshake (LP3-22).
        if (body != null) {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        return code to text
    }

    /**
     * Shared message parsing. Lives in the companion so the Socket.IO service can
     * decode `new-message`/`updated-message` payloads with the same logic the REST
     * calls use — the socket emits the same message-object shape.
     */
    companion object {
        /** Body text, falling back to an attachment placeholder when null/blank. */
        fun messageText(o: JSONObject): String {
            // org.json's optString returns the literal "null" (not the fallback) for
            // an explicit JSON null, so guard with isNull — otherwise a text-less
            // message renders the word "null".
            val t = if (o.isNull("text")) "" else o.optString("text", "").trim()
            if (t.isNotEmpty()) return t
            val attachments = o.optJSONArray("attachments")?.length() ?: 0
            return if (attachments > 0) ChatMessage.ATTACHMENT_PLACEHOLDER else ""
        }

        fun parseMessage(o: JSONObject): ChatMessage {
            val handle = o.optJSONObject("handle")
            return ChatMessage(
                guid = o.optString("guid"),
                text = messageText(o),
                date = o.optLong("dateCreated", 0L),
                fromMe = o.optBoolean("isFromMe", false),
                sender = handle?.optString("address")?.takeIf { it.isNotBlank() },
                attachments = parseAttachments(o),
                // Delivery receipts (epoch millis, 0/null until they happen).
                dateDelivered = o.optLong("dateDelivered", 0L),
                dateRead = o.optLong("dateRead", 0L),
                // Group-system rows (renames, member changes) — no text of their
                // own; rendered as centered event lines, not message turns.
                itemType = o.optInt("itemType", 0),
                groupTitle = o.optString("groupTitle").takeIf { it.isNotBlank() && it != "null" },
                groupActionType = o.optInt("groupActionType", 0),
                // Non-zero = this sent message failed to deliver ("Not delivered").
                error = o.optInt("error", 0),
                // Present when the message is an inline reply to an earlier one.
                threadOriginatorGuid = o.optString("threadOriginatorGuid")
                    .takeIf { it.isNotBlank() && it != "null" },
                // Present only on tapbacks; the ViewModel folds such messages onto
                // their target rather than rendering them. The server reports the
                // type as a word (`love`/`-love`), not the raw iMessage int.
                associatedMessageGuid = o.optString("associatedMessageGuid").takeIf { it.isNotBlank() },
                associatedMessageType = o.optString("associatedMessageType").takeIf { it.isNotBlank() && it != "null" },
                tempGuid = o.optString("tempGuid").takeIf { it.isNotBlank() && it != "null" },
            )
        }

        private fun parseAttachments(o: JSONObject): List<Attachment> {
            val arr = o.optJSONArray("attachments") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val a = arr.getJSONObject(i)
                val guid = a.optString("guid").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = a.optString("transferName").takeIf { it.isNotBlank() }
                // iMessage rich-link previews (sent for URLs — Instagram, etc.) ride
                // along as a `*.pluginPayloadAttachment` metadata blob, not a real
                // file. We can't render the preview and the URL is already in the
                // message text, so drop it rather than show a junk "File · <guid>" row.
                if (name?.endsWith(".pluginPayloadAttachment", ignoreCase = true) == true) {
                    return@mapNotNull null
                }
                Attachment(
                    guid = guid,
                    mimeType = a.optString("mimeType").takeIf { it.isNotBlank() },
                    transferName = name,
                    width = a.optInt("width", 0),
                    height = a.optInt("height", 0),
                )
            }
        }

        /**
         * Decodes a socket message event into an [IncomingMessage], pulling the
         * chat guid + display name from the embedded `chats` array. Returns null if
         * the payload has no chat (can't route it).
         */
        fun messageEvent(data: JSONObject, isNew: Boolean): IncomingMessage? {
            val chats = data.optJSONArray("chats") ?: return null
            if (chats.length() == 0) return null
            val chat = chats.getJSONObject(0)
            val guid = chat.optString("guid").takeIf { it.isNotBlank() } ?: return null
            return IncomingMessage(
                chatGuid = guid,
                message = parseMessage(data),
                isNew = isNew,
                chatDisplayName = chat.optString("displayName", ""),
            )
        }
    }
}
