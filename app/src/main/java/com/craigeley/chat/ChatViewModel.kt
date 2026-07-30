package com.craigeley.chat

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.ImageBitmap
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.craigeley.chat.api.ApiException
import com.craigeley.chat.api.BlueBubblesApi
import com.craigeley.chat.api.Store
import com.craigeley.chat.socket.AppForeground
import com.craigeley.chat.socket.SocketBus
import com.craigeley.chat.socket.SocketService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Status { Idle, Loading, Ready, Error }

// Send a typing "stop" this long after the last keystroke; expire a received
// "typing" after this long without a refresh (the server re-emits ~every 5s).
private const val TYPING_PAUSE_MS = 4_000L
private const val TYPING_EXPIRY_MS = 12_000L

data class UiState(
    val isConfigured: Boolean,                 // a server URL and password are stored
    val status: Status = Status.Idle,
    val conversations: List<Conversation> = emptyList(),
    val open: Conversation? = null,            // the currently-open thread, if any
    val messages: List<ChatMessage> = emptyList(),
    val threadLoading: Boolean = false,
    val contacts: Contacts = Contacts(),       // address → name, from the server's address book
    val contactList: List<Contact> = emptyList(), // searchable recipients for a new message
    val composingNew: Boolean = false,         // the "New message" compose screen is open
    val privateApi: Boolean = false,           // server's Private API live → tapbacks available
    val typingChatGuid: String? = null,        // chat whose other party is currently typing
    val message: String? = null,               // transient status / error line
)

/**
 * Single source of truth for the BlueBubbles client. Owns password setup, the
 * conversation list, the open thread, sending, and the live feed.
 *
 * Reads (REST, [BlueBubblesApi]): [refresh] re-pulls the list, [open] pulls a
 * thread. Writes: [sendMessage] posts optimistically then reconciles with the
 * server's echo. Live: it collects [SocketBus] (fed by the foreground
 * [SocketService]) and folds new/updated messages into the list and open thread.
 * Ordering is enforced here — conversations by last activity, messages by date.
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application

    // A client only exists once setup has stored both a server URL and a password.
    private var api: BlueBubblesApi? = run {
        val url = Store.baseUrl(application)
        val pw = Store.password(application)?.takeIf { it.isNotBlank() }
        if (url != null && pw != null) BlueBubblesApi(url, pw) else null
    }

    private val _state = MutableStateFlow(
        UiState(isConfigured = api != null, privateApi = Store.privateApi(application)),
    )
    val state: StateFlow<UiState> = _state

    private var loadJob: Job? = null
    private var threadJob: Job? = null

    // The address book is small (hundreds of contacts) and changes rarely, so we
    // fetch it once per session alongside the first conversation load and cache it.
    // Seeded from the persisted index so names resolve immediately on launch and
    // survive a session where the fetch never succeeds.
    private var contacts = Store.contacts(application)
    private var contactList = emptyList<Contact>()
    private var contactsLoaded = false

    // Per-conversation message cache (session-lived). Reopening a thread shows the
    // cached messages instantly while a fresh fetch refreshes in the background —
    // no "Loading…" flash. Snapshotted on close so live updates persist. Holds the
    // *raw* list (reaction messages included) so reopening re-folds correctly.
    private val messageCache = HashMap<String, List<ChatMessage>>()

    // Per-conversation (keyed by primary guid) the forked-group room guid that last
    // delivered, so AppleScript sends retry it first instead of re-probing a dead room
    // every time (see sendTargets). Session-lived; the Private API path ignores it.
    private val lastGoodRoom = HashMap<String, String>()

    // The open thread's raw messages — the single source for what's shown. State's
    // `messages` is always foldReactions(openRaw); every open-thread mutation goes
    // through updateOpenThread so tapbacks stay folded onto their targets.
    private var openRaw: List<ChatMessage> = emptyList()

    // Per-conversation (primary guid → lastDate at the time) unread markers cleared
    // on this device. Papers over the window between our markRead and the server's
    // chat.db reflecting it — without this a refresh would re-derive unread from a
    // not-yet-stamped dateRead and resurrect the dot on a thread just read here. A
    // newer message (lastDate past the recorded one) shows unread again. Session-
    // lived: across launches the server's own read state is correct.
    private val clearedUnread = HashMap<String, Long>()

    // A chat to open as soon as the conversation list has loaded — set when a
    // notification tap arrives before the list exists (cold start).
    private var pendingOpenGuid: String? = null

    init {
        observeSocket()
        if (api != null) {
            refresh()
            startSocket()
        }
    }

    // ---- Setup ------------------------------------------------------------

    /** First-launch setup: store the server URL + password and validate them
     *  against the server before committing. Both are required. */
    fun saveSetup(url: String, password: String) {
        val pw = password.trim()
        if (url.isBlank() || pw.isEmpty()) return
        _state.update { it.copy(status = Status.Loading, message = "Connecting…") }
        viewModelScope.launch(Dispatchers.IO) {
            val client = connectClient(url, pw, "Couldn’t reach the server — check the URL and password")
                ?: return@launch
            Store.setPassword(app, pw)
            _state.update { it.copy(isConfigured = true) }
            loadConversations()
            startSocket()
        }
    }

    /** Changes the server URL from Settings: re-validate against the existing
     *  password, then recreate the client and reconnect the socket to the new host. */
    fun updateServerUrl(url: String) {
        if (url.isBlank()) return
        val pw = Store.password(app)?.takeIf { it.isNotBlank() } ?: return
        _state.update { it.copy(status = Status.Loading, message = "Connecting…") }
        viewModelScope.launch(Dispatchers.IO) {
            connectClient(url, pw, "Couldn’t reach that server — check the URL") ?: return@launch
            // Bounce the socket so it reconnects to the new host.
            stopSocket()
            startSocket()
            loadConversations()
        }
    }

    /**
     * Shared tail of [saveSetup]/[updateServerUrl]: normalizes + stores [url] (the
     * same way it's read back), validates it against [pw], and installs the client
     * as [api], caching the Private API flag. Returns null — with [failureMessage]
     * surfaced as an error — when the server can't be reached.
     */
    private fun connectClient(url: String, pw: String, failureMessage: String): BlueBubblesApi? {
        Store.setBaseUrl(app, url)
        val base = Store.baseUrl(app) ?: return null
        val client = BlueBubblesApi(base, pw)
        val info = runCatching { client.serverInfo() }.getOrNull()
        if (info?.reachable != true) {
            _state.update { it.copy(status = Status.Error, message = failureMessage) }
            return null
        }
        api = client
        Store.setPrivateApi(app, info.privateApiReady)
        _state.update { it.copy(privateApi = info.privateApiReady, message = null) }
        return client
    }

    // ---- Conversation list ------------------------------------------------

    fun refresh() {
        if (api == null) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) { loadConversations() }
    }

    private suspend fun loadConversations() {
        val client = api ?: return
        _state.update { it.copy(status = Status.Loading, message = null) }
        try {
            val convos = client.conversations().sortedByDescending { it.lastDate }
                // Honor unreads already cleared on this device (see clearedUnread).
                .map { c ->
                    if (c.unread && (clearedUnread[c.guid] ?: 0L) >= c.lastDate) c.copy(unread = false) else c
                }
            // Re-check Private API liveness so enabling/disabling it on the server
            // (or the helper dropping) reflects without re-running setup.
            val privateApi = runCatching { client.serverInfo() }.getOrNull()
                ?.also { Store.setPrivateApi(app, it.privateApiReady) }
                ?.privateApiReady ?: _state.value.privateApi
            if (!contactsLoaded) {
                runCatching { client.contacts() }.onSuccess { raw ->
                    // A server that can't read the Mac's address book (seen after a
                    // BlueBubbles restart when its contacts access came up broken)
                    // answers with nothing. Treat that like a failed load: keep the
                    // seeded index rather than overwriting the persisted one with
                    // an empty map, and leave contactsLoaded false so a later
                    // refresh retries.
                    if (raw.isEmpty() && contacts.asMap().isNotEmpty()) return@onSuccess
                    contacts = Contacts.from(raw)
                    // Persist so SocketService can name notification senders even
                    // when the app (and this ViewModel) isn't running.
                    Store.setContacts(app, contacts.asMap())
                    // One pickable row per address (a person may have several),
                    // newest search needs name→address, sorted for the picker.
                    contactList = raw.map { Contact(name = it.second, address = it.first) }
                        .distinctBy { it.address }
                        .sortedBy { it.name.lowercase() }
                    contactsLoaded = true
                }
            }
            _state.update {
                it.copy(
                    status = Status.Ready,
                    conversations = convos,
                    contacts = contacts,
                    contactList = contactList,
                    privateApi = privateApi,
                    message = null,
                )
            }
            // A notification tap that landed before the list existed (cold start) —
            // open its thread now, unless the user has already navigated somewhere.
            pendingOpenGuid?.let { guid ->
                pendingOpenGuid = null
                if (_state.value.open == null && !_state.value.composingNew) {
                    convos.firstOrNull { guid in it.guids }?.let(::open)
                }
            }
        } catch (t: Throwable) {
            handleError(t)
        }
    }

    /**
     * Opens the conversation containing [chatGuid] — the notification deep link.
     * If the list isn't loaded yet (app launched from the notification), the open
     * is queued and fires when [loadConversations] lands.
     */
    fun openByGuid(chatGuid: String) {
        val convo = _state.value.conversations.firstOrNull { chatGuid in it.guids }
        if (convo != null) {
            open(convo)
        } else {
            pendingOpenGuid = chatGuid
            refresh()
        }
    }

    // ---- One thread -------------------------------------------------------

    fun open(conversation: Conversation) {
        val cached = messageCache[conversation.guid]
        openRaw = cached ?: emptyList()
        _state.update {
            it.copy(
                open = conversation,
                messages = cached?.let(::foldReactions) ?: emptyList(),
                threadLoading = cached == null,
            )
        }
        conversation.guids.forEach { markReadIfPrivate(it) }
        clearUnread(conversation.guid)
        Notifications.clearChat(app, conversation.guids)
        threadJob?.cancel()
        threadJob = viewModelScope.launch(Dispatchers.IO) {
            val client = api ?: return@launch
            try {
                // Raw — reactions included; merged across a forked group's sibling
                // rooms (usually just one guid) and re-sorted by date in foldReactions.
                // Fetch each room independently so a single stale/dead room can't sink
                // the whole thread; only surface an error if every room failed (so an
                // auth failure still reaches handleError → sign-out).
                val results = conversation.guids.map { g -> runCatching { client.messages(g) } }
                val msgs = results.mapNotNull { it.getOrNull() }.flatten().distinctBy { it.guid }
                if (msgs.isEmpty()) results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
                messageCache[conversation.guid] = msgs
                if (_state.value.open?.guid == conversation.guid) {
                    openRaw = msgs
                    _state.update { it.copy(messages = foldReactions(msgs), threadLoading = false) }
                }
            } catch (t: Throwable) {
                _state.update { if (it.open?.guid == conversation.guid) it.copy(threadLoading = false) else it }
                handleError(t)
            }
        }
    }

    fun closeThread() {
        // Snapshot the raw list (incl. live updates) so reopening is instant.
        _state.value.open?.let {
            messageCache[it.guid] = openRaw
            finishTyping(it.guid) // don't leave a typing bubble up after leaving
        }
        openRaw = emptyList()
        threadJob?.cancel()
        _state.update { it.copy(open = null, messages = emptyList(), threadLoading = false) }
    }

    /**
     * Permanently deletes a conversation from Messages on the Mac (swipe-to-delete in
     * the list). Private-API only — the server gates `DELETE /chat/:guid` on it. A
     * forked group spans several rooms, so every guid is deleted. Optimistic: the row
     * disappears immediately; if any room fails we re-pull the list so it reappears.
     */
    fun deleteConversation(conversation: Conversation) {
        if (!_state.value.privateApi) {
            _state.update { it.copy(message = "Deleting needs the Private API") }
            return
        }
        val client = api ?: return
        // Drop the row now (and close it if it's the open thread); drop its cache too.
        _state.update { s ->
            s.copy(
                conversations = s.conversations.filterNot { it.guid == conversation.guid },
                open = s.open?.takeUnless { it.guid == conversation.guid },
            )
        }
        if (_state.value.open == null) { openRaw = emptyList(); threadJob?.cancel() }
        messageCache.remove(conversation.guid)
        viewModelScope.launch(Dispatchers.IO) {
            val failed = conversation.guids.any { runCatching { client.deleteChat(it) }.isFailure }
            // The server can take ~30s per room (it waits for the local DB), so by now
            // the list may have moved on — a refresh reconciles either way: it restores
            // a row that failed to delete, and confirms the ones that succeeded are gone.
            loadConversations()
            // After the reload (which clears `message`), surface any failure.
            if (failed) _state.update { it.copy(message = "Couldn’t delete the conversation") }
        }
    }

    /**
     * Applies [transform] to the open thread's raw messages and republishes the
     * folded view — but only if [convoGuid] is still the open thread, so a late
     * send/echo can't clobber a thread the user has since navigated away from.
     */
    private fun updateOpenThread(convoGuid: String, transform: (List<ChatMessage>) -> List<ChatMessage>) {
        // Membership, not equality: an incoming message may arrive on any of a forked
        // group's sibling rooms, all of which belong to the same open thread.
        if (_state.value.open?.guids?.contains(convoGuid) != true) return
        openRaw = transform(openRaw)
        val folded = foldReactions(openRaw)
        _state.update { it.copy(messages = folded) }
    }

    /**
     * Folds tapback messages onto their targets: a reaction message isn't shown as
     * its own row but attached to the message it targets as a [Reaction]. A reactor
     * holds at most one tapback per message, so we key by (target, reactor) and let
     * the latest add win — a removal (3000s) clears it. Group-event rows we can't
     * describe (an itemType with no [GroupEvent] mapping — e.g. FaceTime call
     * markers) are dropped here too: they have no text and would render as blank
     * turns. Output is sorted by date.
     */
    private fun foldReactions(raw: List<ChatMessage>): List<ChatMessage> {
        val rows = raw.filterNot { it.isGroupEvent && it.groupEvent == null }
        if (rows.none { it.isReaction }) return rows.sortedBy { it.date }
        val active = LinkedHashMap<Pair<String, String>, Reaction?>()
        for (r in rows.filter { it.isReaction }.sortedBy { it.date }) {
            val target = r.reactionTargetGuid ?: continue
            val type = r.reactionType ?: continue
            val reactorKey = if (r.fromMe) "me" else (r.sender ?: "?")
            active[target to reactorKey] = if (r.isReactionRemoval) null else Reaction(type, r.fromMe, r.sender)
        }
        val byTarget = HashMap<String, MutableList<Reaction>>()
        for ((key, reaction) in active) {
            if (reaction != null) byTarget.getOrPut(key.first) { mutableListOf() }.add(reaction)
        }
        return rows.asSequence()
            .filterNot { it.isReaction }
            .map { m -> byTarget[m.guid]?.let { m.copy(reactions = it) } ?: m }
            .sortedBy { it.date }
            .toList()
    }

    // ---- Sending ----------------------------------------------------------

    /** The send method for text/attachments: the Private API when it's live, else
     *  AppleScript. The Private API is more capable (it sends by DB identity, so it
     *  handles any room of a forked group); the AppleScript path is pickier about the
     *  room guid — see [sendTarget]. */
    private fun sendMethod() = if (_state.value.privateApi) "private-api" else "apple-script"

    /**
     * The room guids to try when sending [convo], best first. The Private API resolves
     * a chat by its DB identity, so the primary (`convo.guid`) alone is enough.
     * AppleScript is pickier — its `chat id "…"` lookup can't resolve a *dead* room of
     * a forked group (it throws -1728 "Can't get chat id", which sends the server into
     * the DM-only fallback script that rejects groups: "Can't use the send message
     * (fallback) script to text a group chat!"). The live sibling resolves and delivers
     * fine. So for AppleScript we hand back *every* sibling room and let [sendAcrossRooms]
     * try each until one delivers — the last room that delivered (cached in
     * [lastGoodRoom]) first, then UUID-form rooms ahead of `chat<number>` forms, since
     * those tend to resolve. A 1:1 or single-room group is just `[guid]`.
     */
    private fun sendTargets(convo: Conversation, method: String): List<String> {
        if (method != "apple-script") return listOf(convo.guid)
        val ordered = convo.guids.sortedBy { it.substringAfterLast(";").startsWith("chat") }
        val good = lastGoodRoom[convo.guid]?.takeIf { it in convo.guids } ?: return ordered
        return listOf(good) + ordered.filter { it != good }
    }

    /**
     * Sends via the first of [targets] that succeeds; records the winning room in
     * [lastGoodRoom] under [convoGuid] so the next send tries it first, and returns its
     * result. Rethrows the last error if all fail. Lets an AppleScript send fall through
     * a forked group's dead rooms to the live one. Safe against double-sending: the
     * dead-room failure (-1728) happens during chat resolution, before any message goes
     * out.
     */
    private fun <T> sendAcrossRooms(convoGuid: String, targets: List<String>, send: (String) -> T): T {
        var last: Throwable? = null
        for (guid in targets) {
            try {
                val result = send(guid)
                lastGoodRoom[convoGuid] = guid
                return result
            } catch (t: Throwable) {
                last = t
            }
        }
        throw last ?: IllegalStateException("no send targets")
    }

    /** A client-side guid for an optimistic message, swapped for the server echo's
     *  real guid on reconcile. The `temp-` prefix marks a not-yet-acked message. */
    private fun newTempGuid(prefix: String = "temp") =
        "$prefix-${System.currentTimeMillis()}-${(0..99999).random()}"

    /** Swaps the optimistic [tempGuid] row for the server's [sent] echo, deduping
     *  in case the socket echo already landed under the real guid. */
    private fun reconcileEcho(convoGuid: String, tempGuid: String, sent: ChatMessage) {
        updateOpenThread(convoGuid) { list ->
            list.map { if (it.guid == tempGuid) sent else it }.distinctBy { it.guid }
        }
    }

    /** Drops the optimistic [tempGuid] row after a failed send and surfaces [error]. */
    private fun rollbackOptimistic(convoGuid: String, tempGuid: String, error: String) {
        updateOpenThread(convoGuid) { list -> list.filterNot { it.guid == tempGuid } }
        _state.update { it.copy(message = error) }
    }

    /**
     * Shared tail of [sendMessage]/[sendImage]: runs [call] against the first room
     * of [convo] that delivers (see [sendAcrossRooms]), then reconciles the
     * optimistic [tempGuid] row with the echo and bumps the conversation list —
     * or rolls the optimistic row back with [errorMessage]. Call off-main.
     */
    private fun performSend(
        convo: Conversation,
        tempGuid: String,
        errorMessage: String,
        call: (BlueBubblesApi, String, String) -> ChatMessage,
    ) {
        val client = api ?: return
        try {
            val method = sendMethod()
            val sent = sendAcrossRooms(convo.guid, sendTargets(convo, method)) { g ->
                call(client, g, method)
            }
            reconcileEcho(convo.guid, tempGuid, sent)
            bumpConversation(convo.guid, sent.previewText, sent.date, fromMe = true)
        } catch (t: Throwable) {
            rollbackOptimistic(convo.guid, tempGuid, errorMessage)
        }
    }

    /** Sends [text] into the open thread — as an inline reply to [replyToGuid]
     *  when given (Private-API only; ignored otherwise, and a not-yet-acked temp
     *  guid can't be replied to). */
    fun sendMessage(text: String, replyToGuid: String? = null) {
        val body = text.trim()
        val convo = _state.value.open ?: return
        if (body.isEmpty()) return
        finishTyping(convo.guid) // sending clears our typing bubble
        val reply = replyToGuid?.takeIf { _state.value.privateApi && !it.startsWith("temp-") }
        // Optimistic: show it immediately under a temp guid, then swap in the
        // server's echo (real guid) so the socket's new-message dedupes cleanly.
        val tempGuid = newTempGuid()
        val optimistic = ChatMessage(
            tempGuid, body, System.currentTimeMillis(), fromMe = true, sender = null,
            threadOriginatorGuid = reply,
        )
        updateOpenThread(convo.guid) { it + optimistic }
        _state.update { it.copy(message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            performSend(convo, tempGuid, "Couldn’t send") { client, g, method ->
                client.send(g, body, tempGuid, method, reply)
            }
        }
    }

    // ---- Tapbacks ---------------------------------------------------------

    /**
     * Sends a tapback onto [target] (or removes it, if [target] already carries
     * mine of [type] — long-pressing the same reaction toggles it off). Mirrors
     * [sendMessage]: an optimistic reaction message is folded in immediately, then
     * reconciled with the server's echo. Gated on [UiState.privateApi]; can't react
     * to a not-yet-acked optimistic message (no real guid to target).
     */
    fun sendReaction(target: ChatMessage, type: ReactionType) {
        val convo = _state.value.open ?: return
        if (!_state.value.privateApi) return
        if (target.guid.startsWith("temp-")) {
            _state.update { it.copy(message = "Still sending — try again in a moment") }
            return
        }
        val removing = target.reactions.firstOrNull { it.fromMe }?.type == type
        val apiValue = if (removing) "-${type.apiValue}" else type.apiValue
        val tempGuid = newTempGuid("temp-react")
        val optimistic = ChatMessage(
            guid = tempGuid,
            text = "",
            date = System.currentTimeMillis(),
            fromMe = true,
            sender = null,
            associatedMessageGuid = target.guid,
            associatedMessageType = apiValue, // "love" or "-love"
        )
        updateOpenThread(convo.guid) { it + optimistic }
        viewModelScope.launch(Dispatchers.IO) {
            val client = api ?: return@launch
            try {
                val sent = client.react(convo.guid, target.guid, apiValue)
                reconcileEcho(convo.guid, tempGuid, sent)
            } catch (t: Throwable) {
                rollbackOptimistic(convo.guid, tempGuid, "Couldn’t react")
            }
        }
    }

    // ---- Group management (Private API) ------------------------------------

    /**
     * Renames the open group — across *all* its rooms (best-effort), so a forked
     * group's dead siblings keep the same name and the name+participants merge key
     * holds instead of splitting the row. Succeeds if any room renamed; the
     * `group-name-change` socket echo and the refresh reconcile the rest.
     */
    fun renameGroup(name: String) {
        val convo = _state.value.open ?: return
        val newName = name.trim()
        if (!_state.value.privateApi || !convo.isGroup || newName.isEmpty()) return
        val client = api ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val results = convo.guids.map { g -> runCatching { client.renameChat(g, newName) } }
            if (results.none { it.isSuccess }) {
                _state.update { it.copy(message = "Couldn’t rename") }
                return@launch
            }
            _state.update { s ->
                s.copy(
                    open = s.open?.takeIf { it.guid == convo.guid }?.copy(displayName = newName) ?: s.open,
                    conversations = s.conversations.map { c ->
                        if (c.guid == convo.guid) c.copy(displayName = newName) else c
                    },
                )
            }
            loadConversations()
        }
    }

    /** Adds [address] to the open group. iMessage may fork the group into a new
     *  room for the new membership — the refresh reflects whatever it did. */
    fun addMember(address: String) {
        val convo = _state.value.open ?: return
        val addr = address.trim()
        if (!_state.value.privateApi || !convo.isGroup || addr.isEmpty()) return
        val client = api ?: return
        _state.update { it.copy(message = "Adding…") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                client.addParticipant(convo.guid, addr)
                _state.update { s ->
                    s.copy(
                        open = s.open?.takeIf { it.guid == convo.guid }
                            ?.let { it.copy(participants = it.participants + addr) } ?: s.open,
                        message = null,
                    )
                }
                loadConversations()
            } catch (t: Throwable) {
                _state.update { it.copy(message = "Couldn’t add — are they on iMessage?") }
            }
        }
    }

    /** Removes [address] from the open group. */
    fun removeMember(address: String) {
        val convo = _state.value.open ?: return
        if (!_state.value.privateApi || !convo.isGroup) return
        val client = api ?: return
        _state.update { it.copy(message = "Removing…") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                client.removeParticipant(convo.guid, address)
                _state.update { s ->
                    s.copy(
                        open = s.open?.takeIf { it.guid == convo.guid }
                            ?.let { o -> o.copy(participants = o.participants.filterNot { it == address }) }
                            ?: s.open,
                        message = null,
                    )
                }
                loadConversations()
            } catch (t: Throwable) {
                _state.update { it.copy(message = "Couldn’t remove them") }
            }
        }
    }

    /** Leaves the open group (every room of a forked group, best-effort), then
     *  drops back to the list. iMessage refuses on too-small groups — that
     *  surfaces as the failure message. */
    fun leaveGroup() {
        val convo = _state.value.open ?: return
        if (!_state.value.privateApi || !convo.isGroup) return
        val client = api ?: return
        _state.update { it.copy(message = "Leaving…") }
        viewModelScope.launch(Dispatchers.IO) {
            val results = convo.guids.map { g -> runCatching { client.leaveChat(g) } }
            if (results.none { it.isSuccess }) {
                _state.update { it.copy(message = "Couldn’t leave the conversation") }
                return@launch
            }
            closeThread()
            _state.update { it.copy(message = null) }
            loadConversations()
        }
    }

    /** Whether [address] can receive iMessages, delivered via [onResult] (skipped
     *  entirely when the Private API is down — the check needs it — or on a failed
     *  call, so "unknown" never blocks anyone). */
    fun checkIMessage(address: String, onResult: (Boolean) -> Unit) {
        if (!_state.value.privateApi) return
        val client = api ?: return
        viewModelScope.launch {
            val available = withContext(Dispatchers.IO) {
                runCatching { client.iMessageAvailable(address) }.getOrNull()
            }
            if (available != null) onResult(available)
        }
    }

    // ---- Attachments ------------------------------------------------------

    /** Decoded inline image for [attachment] (downloaded + cached on first use),
     *  or null if it isn't an image / can't be fetched. Called from the thread UI. */
    suspend fun loadImage(attachment: Attachment): ImageBitmap? {
        val client = api ?: return null
        return Attachments.image(app, client, attachment)
    }

    /**
     * Opens a non-image attachment: downloads it to a FileProvider-shared cache file,
     * then hands off to an external app via `ACTION_VIEW`. Falls back to a share
     * chooser, then a message if nothing on the (minimal) device can handle it.
     */
    fun openAttachment(attachment: Attachment) {
        val client = api ?: return
        _state.update { it.copy(message = "Downloading…") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dir = File(app.cacheDir, "shared").apply { mkdirs() }
                val safe = (attachment.transferName ?: attachment.guid)
                    .replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { attachment.guid }
                val dest = File(dir, safe)
                if (!dest.exists() || dest.length() == 0L) client.downloadAttachment(attachment.guid, dest)
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", dest)
                val mime = attachment.mimeType ?: "application/octet-stream"
                _state.update { it.copy(message = null) }
                val view = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    app.startActivity(view)
                } catch (e: ActivityNotFoundException) {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val chooser = Intent.createChooser(send, "Open with")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { app.startActivity(chooser) }
                        .onFailure { _state.update { s -> s.copy(message = "No app can open this file") } }
                }
            } catch (t: Throwable) {
                _state.update { it.copy(message = "Couldn’t download attachment") }
            }
        }
    }

    /**
     * Sends a picked image into the open thread. Mirrors [sendMessage]: it shows an
     * optimistic bubble immediately — the picked bytes are seeded into the image
     * cache under the temp guid so the normal loader renders them without a round
     * trip — then swaps in the server's echo (real guid) so the socket dedupes.
     */
    fun sendImage(uri: Uri) {
        val convo = _state.value.open ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val img = readPickedImage(uri) ?: return@launch
            val tempGuid = newTempGuid()
            // Seed the cache so the optimistic bubble renders the local image.
            Attachments.cacheLocal(app, tempGuid, img.bytes)
            val optimistic = ChatMessage(
                guid = tempGuid,
                text = ChatMessage.ATTACHMENT_PLACEHOLDER,
                date = System.currentTimeMillis(),
                fromMe = true,
                sender = null,
                attachments = listOf(Attachment(tempGuid, img.mime, img.name, width = 0, height = 0)),
            )
            updateOpenThread(convo.guid) { it + optimistic }
            _state.update { it.copy(message = null) }
            performSend(convo, tempGuid, "Couldn’t send image") { client, g, method ->
                client.sendAttachment(g, img.bytes, img.name, img.mime, tempGuid, method)
            }
        }
    }

    /** A picked image's bytes plus the metadata a send needs. */
    private class PickedImage(val bytes: ByteArray, val mime: String, val name: String)

    /** Reads the image behind a picker [uri] (bytes, mime, display name), or null —
     *  with the error surfaced — when it can't be read. Call off-main. */
    private fun readPickedImage(uri: Uri): PickedImage? {
        val resolver = app.contentResolver
        val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            _state.update { it.copy(message = "Couldn’t read that image") }
            return null
        }
        return PickedImage(bytes, resolver.getType(uri) ?: "image/jpeg", queryDisplayName(uri) ?: "image.jpg")
    }

    private fun queryDisplayName(uri: Uri): String? =
        runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst() && it.columnCount > 0) it.getString(0) else null }
        }.getOrNull()

    /**
     * Sends a picked image as the first message of a *new* 1:1. There's no chat
     * guid yet, so we construct the canonical BlueBubbles 1:1 guid
     * (`iMessage;-;<handle>`) — sending an attachment to it creates the chat
     * server-side — then drop into the thread and refresh the list. The address is
     * normalized to the E.164 handle iMessage keys its guids by (`newChat`'s
     * AppleScript resolves loose addresses for text, but a constructed guid can't).
     */
    fun sendNewImage(address: String, uri: Uri) {
        val addr = address.trim()
        if (addr.isEmpty()) return
        _state.update { it.copy(composingNew = false, message = "Sending…") }
        viewModelScope.launch(Dispatchers.IO) {
            val client = api ?: return@launch
            val img = readPickedImage(uri) ?: return@launch
            val handle = imessageHandle(addr)
            val guid = "iMessage;-;$handle"
            try {
                client.sendAttachment(guid, img.bytes, img.name, img.mime, newTempGuid(), sendMethod())
                messageCache.remove(guid)
                val convo = Conversation(
                    guid = guid,
                    displayName = "",
                    participants = listOf(handle),
                    isGroup = false,
                    lastText = "[Photo]",
                    lastDate = System.currentTimeMillis(),
                    lastFromMe = true,
                )
                _state.update { it.copy(message = null) }
                open(convo)   // land in the new thread (fetch pulls the sent image)
                refresh()     // and pull it into the conversation list
            } catch (t: Throwable) {
                _state.update { it.copy(message = t.message ?: "Couldn’t send image") }
            }
        }
    }

    /** Normalizes a picked address to the E.164 (or lowercased email) handle that
     *  iMessage keys 1:1 chat guids by. US-centric on the country code, matching the
     *  single personal account this app serves. */
    private fun imessageHandle(address: String): String {
        val a = address.trim()
        if (a.contains("@")) return a.lowercase()
        val digits = a.filter { it.isDigit() }
        return when {
            a.startsWith("+") -> "+$digits"
            digits.length == 10 -> "+1$digits"
            digits.length == 11 && digits.startsWith("1") -> "+$digits"
            else -> a
        }
    }

    // ---- New message ------------------------------------------------------

    fun startNewMessage() = _state.update { it.copy(composingNew = true, message = null) }

    fun cancelNewMessage() = _state.update { it.copy(composingNew = false, message = null) }

    /**
     * Starts a fresh chat with [addresses] by sending [text], then opens it. One
     * address is a 1:1 (AppleScript); two or more form a group, which the server
     * only creates over the Private API — so a group send is gated on
     * `state.privateApi` (the picker also hides the option, this is the backstop).
     * The group's guid is server-assigned, so we open on whatever `newChat` returns.
     */
    fun sendNewMessage(addresses: List<String>, text: String) {
        val addrs = addresses.map { it.trim() }.filter { it.isNotEmpty() }
        val body = text.trim()
        if (addrs.isEmpty() || body.isEmpty()) return
        val isGroup = addrs.size > 1
        if (isGroup && !_state.value.privateApi) {
            _state.update { it.copy(message = "Group messaging needs the server’s Private API") }
            return
        }
        _state.update { it.copy(composingNew = false, message = "Sending…") }
        viewModelScope.launch(Dispatchers.IO) {
            val client = api ?: return@launch
            try {
                val guid = client.newChat(addrs, body)
                messageCache.remove(guid)
                val convo = Conversation(
                    guid = guid,
                    displayName = "",
                    participants = addrs,
                    isGroup = isGroup,
                    lastText = body,
                    lastDate = System.currentTimeMillis(),
                    lastFromMe = true,
                )
                _state.update { it.copy(message = null) }
                open(convo)   // land the user in the new thread
                refresh()     // and pull it into the conversation list
            } catch (t: Throwable) {
                _state.update { it.copy(message = t.message ?: "Couldn’t start the message") }
            }
        }
    }

    // ---- Live feed (socket) ----------------------------------------------

    private fun observeSocket() {
        viewModelScope.launch {
            SocketBus.incoming.collect { applyIncoming(it) }
        }
        viewModelScope.launch {
            SocketBus.typing.collect { applyTyping(it) }
        }
        viewModelScope.launch {
            SocketBus.readStatus.collect { applyReadStatus(it) }
        }
    }

    // ---- Typing indicators ------------------------------------------------

    private var typingExpiryJob: Job? = null      // clears a stale "typing" if no refresh
    private var typingSent = false                // whether we've told the server we're typing
    private var typingStopJob: Job? = null        // fires the auto-stop after a pause

    /** Receiving: fold a typing change into state. A "stopped" event can be missed,
     *  so a "typing" auto-expires after [TYPING_EXPIRY_MS] without a refresh (the
     *  server re-emits ~every 5s while typing continues). */
    private fun applyTyping(event: TypingEvent) {
        typingExpiryJob?.cancel()
        if (event.typing) {
            _state.update { it.copy(typingChatGuid = event.chatGuid) }
            typingExpiryJob = viewModelScope.launch {
                delay(TYPING_EXPIRY_MS)
                _state.update { if (it.typingChatGuid == event.chatGuid) it.copy(typingChatGuid = null) else it }
            }
        } else {
            _state.update { if (it.typingChatGuid == event.chatGuid) it.copy(typingChatGuid = null) else it }
        }
    }

    /** Sending: the open thread's compose text changed. Tells the server we're
     *  typing on the first keystroke and schedules an auto-stop after a pause; an
     *  empty field stops immediately. No-op unless the Private API is live. */
    fun onComposeTextChanged(text: String) {
        if (!_state.value.privateApi) return
        val guid = _state.value.open?.guid ?: return
        if (text.isBlank()) {
            typingStopJob?.cancel()
            stopTypingNow(guid)
            return
        }
        val client = api ?: return
        if (!typingSent) {
            typingSent = true
            viewModelScope.launch(Dispatchers.IO) { runCatching { client.startTyping(guid) } }
        }
        typingStopJob?.cancel()
        typingStopJob = viewModelScope.launch {
            delay(TYPING_PAUSE_MS)
            stopTypingNow(guid)
        }
    }

    private fun stopTypingNow(guid: String) {
        if (!typingSent) return
        typingSent = false
        val client = api ?: return
        viewModelScope.launch(Dispatchers.IO) { runCatching { client.stopTyping(guid) } }
    }

    /** Cancel a pending auto-stop and clear our typing state — on send or close. */
    private fun finishTyping(guid: String) {
        typingStopJob?.cancel()
        stopTypingNow(guid)
    }

    private fun applyIncoming(incoming: IncomingMessage) {
        val known = _state.value.conversations.any { incoming.chatGuid in it.guids }
        // Looking right at the thread — don't flag unread, and mark read below.
        val viewing = _state.value.open?.guids?.contains(incoming.chatGuid) == true && AppForeground.active
        // A genuinely new message (or tapback) from someone else, not on screen →
        // the row goes unread. Group events bump recency but aren't unread-worthy
        // (they also never get a dateRead, so the load derivation skips them too).
        val flagUnread = incoming.isNew && !incoming.message.fromMe &&
            !incoming.message.isGroupEvent && !viewing
        _state.update { s ->
            val convos = s.conversations.map { c ->
                if (incoming.chatGuid in c.guids) {
                    when {
                        // A group event (rename, member change) bumps recency but
                        // isn't speech — the text preview keeps the newest real message.
                        incoming.message.isGroupEvent -> c.copy(
                            lastDate = maxOf(c.lastDate, incoming.message.date),
                        )
                        // A tapback bumps recency and surfaces as "Liz loved an image"
                        // ([lastReaction]); a removal clears that overlay; a normal message
                        // updates the text preview and clears any reaction overlay.
                        incoming.message.isReaction -> c.copy(
                            lastDate = incoming.message.date,
                            lastReaction = incoming.message.reactionPreview(::cachedMessage),
                            unread = c.unread || flagUnread,
                        )
                        else -> c.copy(
                            lastText = incoming.message.previewText,
                            lastDate = incoming.message.date,
                            lastFromMe = incoming.message.fromMe,
                            lastReaction = null,
                            unread = c.unread || flagUnread,
                        )
                    }
                } else {
                    c
                }
            }.sortedByDescending { it.lastDate }
            s.copy(conversations = convos)
        }
        // A rename should reflect immediately in the open thread's title; the list
        // row's stored displayName comes from the refresh below.
        if (incoming.message.groupEvent == GroupEvent.RENAMED) {
            _state.update { s ->
                val open = s.open
                if (open != null && incoming.chatGuid in open.guids) {
                    s.copy(open = open.copy(displayName = incoming.chatDisplayName))
                } else {
                    s
                }
            }
            refresh()
        }
        // Fold the message into the open thread's raw list (a tapback lands on its
        // target; a normal message appends). foldReactions re-runs in updateOpenThread.
        updateOpenThread(incoming.chatGuid) { mergeRaw(it, incoming.message) }
        // If it's an incoming message in the thread you're looking at, mark the chat
        // read so the unread clears on your other devices too — and record the clear
        // locally so a refresh can't resurrect the dot before the server catches up.
        if (!incoming.message.fromMe && viewing) {
            markReadIfPrivate(incoming.chatGuid)
            _state.value.conversations.firstOrNull { incoming.chatGuid in it.guids }
                ?.let { clearUnread(it.guid) }
        }
        // A message for a chat not currently in the list (e.g. a brand-new
        // conversation) — pull the list again so it appears with full metadata.
        if (!known) refresh()
    }

    /** A `chat-read-status-changed` from the socket: the chat was read somewhere
     *  (another device, or our own markRead echoing back) — drop its unread dot. */
    private fun applyReadStatus(event: ReadStatusEvent) {
        if (!event.read) return
        _state.value.conversations.firstOrNull { event.chatGuid in it.guids }
            ?.let { clearUnread(it.guid) }
    }

    /** Clears [convoGuid]'s unread marker in the list and records it in
     *  [clearedUnread] so the next refresh can't resurrect it (the server's
     *  dateRead stamp can lag our markRead). */
    private fun clearUnread(convoGuid: String) {
        val convo = _state.value.conversations.firstOrNull { it.guid == convoGuid } ?: return
        clearedUnread[convoGuid] = maxOf(clearedUnread[convoGuid] ?: 0L, convo.lastDate)
        if (!convo.unread) return
        _state.update { s ->
            s.copy(conversations = s.conversations.map { if (it.guid == convoGuid) it.copy(unread = false) else it })
        }
    }

    /** Marks [chatGuid] read on the server (best-effort, off-main), but only when
     *  the Private API is live — it's the only path that can send a read receipt. */
    private fun markReadIfPrivate(chatGuid: String) {
        if (!_state.value.privateApi) return
        val client = api ?: return
        viewModelScope.launch(Dispatchers.IO) { runCatching { client.markRead(chatGuid) } }
    }

    private fun mergeRaw(list: List<ChatMessage>, m: ChatMessage): List<ChatMessage> {
        // Match by guid, or — for the socket echo of our own send — by the tempGuid the
        // server echoes back, since the optimistic bubble still carries it as its guid.
        // Without the latter the echo (real guid) would render as a second row until the
        // HTTP send call returns and swaps the temp guid in.
        val idx = list.indexOfFirst { it.guid == m.guid || (m.tempGuid != null && it.guid == m.tempGuid) }
        return if (idx >= 0) list.toMutableList().also { it[idx] = m } else list + m
    }

    /** Looks up a message by guid in whatever's cached (the open thread, or any
     *  previously-opened thread), so a live tapback can describe its target. Null
     *  when the target isn't loaded — the preview then reads "a message". */
    private fun cachedMessage(guid: String): ChatMessage? {
        openRaw.firstOrNull { it.guid == guid }?.let { return it }
        for (list in messageCache.values) list.firstOrNull { it.guid == guid }?.let { return it }
        return null
    }

    private fun bumpConversation(guid: String, text: String, date: Long, fromMe: Boolean) {
        _state.update { s ->
            val convos = s.conversations.map { c ->
                // A real message (this is only called for sends) clears any reaction overlay.
                if (guid in c.guids) c.copy(lastText = text, lastDate = date, lastFromMe = fromMe, lastReaction = null) else c
            }.sortedByDescending { it.lastDate }
            s.copy(conversations = convos)
        }
    }

    // ---- Service lifecycle ------------------------------------------------

    private fun startSocket() {
        app.startForegroundService(Intent(app, SocketService::class.java))
    }

    private fun stopSocket() {
        app.stopService(Intent(app, SocketService::class.java))
    }

    // ---- Errors / sign out ------------------------------------------------

    private fun handleError(t: Throwable) {
        if (t is ApiException && t.isAuthError) {
            signOutInternal("Password rejected")
            return
        }
        _state.update { it.copy(status = Status.Error, message = t.message ?: "Something went wrong") }
    }

    fun signOut() = signOutInternal(null)

    private fun signOutInternal(message: String?) {
        loadJob?.cancel()
        threadJob?.cancel()
        api = null
        stopSocket()
        Store.signOut(app)
        _state.value = UiState(isConfigured = false, message = message)
    }

    override fun onCleared() {
        loadJob?.cancel()
        threadJob?.cancel()
        super.onCleared()
    }
}
