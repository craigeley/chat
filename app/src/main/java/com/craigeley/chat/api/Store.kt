package com.craigeley.chat.api

import android.content.Context
import com.craigeley.chat.Contacts
import com.craigeley.chat.Conversation
import org.json.JSONArray
import org.json.JSONObject

/**
 * On-device state for the BlueBubbles client: the server URL (entered at setup,
 * editable in Settings) and the server password, encrypted at rest by
 * [SecureStore]. The app talks to one self-hosted BlueBubbles Server — typically
 * reached over Tailscale Serve or another HTTPS reverse proxy (which provides TLS
 * + private routing; the server itself stays LAN-bound). Nothing here touches
 * Google Play Services.
 */
object Store {
    private const val PREFS = "chat"
    private const val KEY_PASSWORD = "bb_password" // encrypted
    private const val KEY_CONTACTS = "contacts"    // normalized key → name, JSON
    private const val KEY_BASE_URL = "base_url"    // the server URL, set at setup
    private const val KEY_PRIVATE_API = "private_api" // server's Private API live?
    private const val KEY_LAST_SEEN = "last_seen_date" // newest message date seen (catch-up cursor)
    private const val KEY_GROUPS = "named_groups"  // named group chats for New Message search, JSON

    /** The configured BlueBubbles Server URL, or null if setup hasn't run yet. */
    fun baseUrl(context: Context): String? =
        prefs(context).getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() }

    /** Stores the server URL, normalizing it: default to https:// if no scheme is
     *  given, and drop a trailing slash so it concatenates cleanly with API paths. */
    fun setBaseUrl(context: Context, value: String) {
        var url = value.trim().trimEnd('/')
        if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        prefs(context).edit().putString(KEY_BASE_URL, url).apply()
    }

    fun password(context: Context): String? =
        prefs(context).getString(KEY_PASSWORD, null)?.let { runCatching { SecureStore.decrypt(it) }.getOrNull() }

    fun setPassword(context: Context, value: String) {
        prefs(context).edit().putString(KEY_PASSWORD, SecureStore.encrypt(value.trim())).apply()
    }

    fun hasPassword(context: Context): Boolean = !password(context).isNullOrBlank()

    /**
     * Persists the contact index so the [com.craigeley.chat.socket.SocketService] —
     * which can run with no activity/ViewModel alive (e.g. started at boot) — can
     * resolve sender addresses to names for notifications. Stored as the already-
     * normalized key → name map ([Contacts.asMap]), no re-normalization on read.
     */
    fun setContacts(context: Context, byKey: Map<String, String>) {
        val obj = JSONObject()
        for ((k, v) in byKey) obj.put(k, v)
        prefs(context).edit().putString(KEY_CONTACTS, obj.toString()).apply()
    }

    /** The persisted contact index, or an empty one if none stored yet. */
    fun contacts(context: Context): Contacts {
        val json = prefs(context).getString(KEY_CONTACTS, null) ?: return Contacts()
        return runCatching {
            val obj = JSONObject(json)
            val map = HashMap<String, String>(obj.length())
            obj.keys().forEach { map[it] = obj.getString(it) }
            Contacts.fromMap(map)
        }.getOrDefault(Contacts())
    }

    /** Whether the server's Private API is live (tapbacks available). Cached from
     *  `server/info` so the UI knows on launch before the first refresh lands. */
    fun privateApi(context: Context): Boolean = prefs(context).getBoolean(KEY_PRIVATE_API, false)

    fun setPrivateApi(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_PRIVATE_API, value).apply()
    }

    /**
     * The catch-up cursor: the `dateCreated` of the newest message this device has
     * seen, by any path (a socket event, a replay, or the ViewModel's sweep). On
     * every socket connect `SocketService` replays what the server has after this
     * point, so nothing that arrived during a gap is missed. 0 until the first
     * sweep seeds it. Only ever advances ([advanceLastSeen]).
     */
    fun lastSeenDate(context: Context): Long = prefs(context).getLong(KEY_LAST_SEEN, 0L)

    fun advanceLastSeen(context: Context, date: Long) {
        if (date <= 0L) return
        val prefs = prefs(context)
        if (date > prefs.getLong(KEY_LAST_SEEN, 0L)) prefs.edit().putLong(KEY_LAST_SEEN, date).apply()
    }

    /**
     * The named group chats New Message searches (LP3-63), so a search right after
     * launch works before [com.craigeley.chat.api.BlueBubblesApi.namedGroups] —
     * a few-MB fetch — comes back. Only what the picker and
     * [com.craigeley.chat.ChatViewModel.openGroup]
     * need: name, participants, guids (live room first), recency.
     */
    fun setGroups(context: Context, groups: List<Conversation>) {
        val arr = JSONArray()
        for (g in groups) {
            arr.put(
                JSONObject()
                    .put("name", g.displayName)
                    .put("participants", JSONArray(g.participants))
                    .put("guids", JSONArray(g.guids))
                    .put("lastDate", g.lastDate),
            )
        }
        prefs(context).edit().putString(KEY_GROUPS, arr.toString()).apply()
    }

    fun groups(context: Context): List<Conversation> {
        val json = prefs(context).getString(KEY_GROUPS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val parts = o.getJSONArray("participants").let { p -> (0 until p.length()).map(p::getString) }
                val guids = o.getJSONArray("guids").let { g -> (0 until g.length()).map(g::getString) }
                Conversation(
                    guid = guids.first(),
                    displayName = o.getString("name"),
                    participants = parts,
                    isGroup = true,
                    lastText = "",
                    lastDate = o.optLong("lastDate"),
                    lastFromMe = false,
                    guids = guids,
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Sign out: wipe the stored password. */
    fun signOut(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
