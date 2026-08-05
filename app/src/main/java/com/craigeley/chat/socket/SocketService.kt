package com.craigeley.chat.socket

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.craigeley.chat.Contacts
import com.craigeley.chat.Notifications
import com.craigeley.chat.ReadStatusEvent
import com.craigeley.chat.TypingEvent
import com.craigeley.chat.api.BlueBubblesApi
import com.craigeley.chat.api.Store
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import java.net.URLEncoder
import org.json.JSONObject

/**
 * Foreground service holding the one live Socket.IO connection to the BlueBubbles
 * server. This is the OpenBubbles replacement's core: with no Google Play push on
 * the device, instant delivery means keeping a socket open ourselves — but it's a
 * single lightweight connection, not a whole Flutter runtime.
 *
 * On `new-message`/`updated-message` it parses the payload (shared with
 * [BlueBubblesApi]), pushes it onto [SocketBus] for the live UI, and — when the
 * app isn't foreground — raises a notification.
 */
class SocketService : Service() {

    private var socket: Socket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        startForeground(
            Notifications.SERVICE_ID,
            Notifications.foregroundNotification(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
        )
        connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun connect() {
        val password = Store.password(this) ?: run { stopSelf(); return }
        val baseUrl = Store.baseUrl(this) ?: run { stopSelf(); return }
        val opts = IO.Options().apply {
            transports = arrayOf("websocket") // server upgrades to ws anyway; skip polling
            query = "password=" + URLEncoder.encode(password, "UTF-8")
            reconnection = true
            // Defaults retry forever with backoff capped at 5s — and websocket-only
            // means every attempt on a half-dead link (TLS up, session dies) pays a
            // full cert-chain handshake, indefinitely, often on cellular. Let a down
            // link settle to ~1 attempt/min instead; the delay resets to fast on a
            // successful reconnect (LP3-23).
            reconnectionDelay = 2_000
            reconnectionDelayMax = 60_000
        }
        val s = runCatching { IO.socket(baseUrl, opts) }.getOrNull() ?: run { stopSelf(); return }
        socket = s
        s.on(Socket.EVENT_CONNECT, Emitter.Listener { Log.d(TAG, "socket connected") })
        s.on(Socket.EVENT_CONNECT_ERROR, Emitter.Listener { Log.w(TAG, "connect error: ${it.firstOrNull()}") })
        s.on("new-message", Emitter.Listener { onMessage(it, isNew = true) })
        s.on("updated-message", Emitter.Listener { onMessage(it, isNew = false) })
        // A send the Mac accepted but couldn't deliver (e.g. not an iMessage
        // address) — the payload is the message with its `error` set; merging it
        // flips the bubble to "Not delivered" instead of failing silently.
        s.on("message-send-error", Emitter.Listener { onMessage(it, isNew = false) })
        s.on("typing-indicator", Emitter.Listener { onTyping(it) })
        // The chat was read somewhere on the account (Mac, iPhone, or our own
        // markRead) — the server's chat.db poller reports it, no Private API needed.
        // Clears the list's unread marker and the chat's now-stale notification.
        s.on("chat-read-status-changed", Emitter.Listener { onReadStatus(it) })
        // Group-system changes arrive as their own event types but carry the same
        // serialized message payload (embedded chats included), so they route
        // through the normal message path — the thread shows them as event rows
        // and the list bumps. They never notify (see onMessage's isGroupEvent gate).
        for (event in listOf("group-name-change", "participant-added", "participant-removed", "participant-left")) {
            s.on(event, Emitter.Listener { onMessage(it, isNew = true) })
        }
        s.connect()
    }

    /** A `chat-read-status-changed` event — `{ chatGuid, read }`. Bridged to the
     *  ViewModel (unread marker) and, when read, dismisses the chat's notification
     *  so an alert already read on another device doesn't linger here. */
    private fun onReadStatus(args: Array<out Any?>?) {
        val data = args?.firstOrNull() as? JSONObject ?: return
        val guid = data.optString("chatGuid").takeIf { it.isNotBlank() } ?: return
        val read = data.optBoolean("read", false)
        SocketBus.readStatus.tryEmit(ReadStatusEvent(guid, read))
        if (read) Notifications.clearChat(this, listOf(guid))
    }

    /** A `typing-indicator` event — `{ display, guid }` — bridged to the ViewModel.
     *  No notification; it only matters for the open thread. */
    private fun onTyping(args: Array<out Any?>?) {
        val data = args?.firstOrNull() as? JSONObject ?: return
        val guid = data.optString("guid").takeIf { it.isNotBlank() } ?: return
        SocketBus.typing.tryEmit(TypingEvent(guid, data.optBoolean("display", false)))
    }

    private fun onMessage(args: Array<out Any?>?, isNew: Boolean) {
        val data = args?.firstOrNull() as? JSONObject ?: return
        val incoming = BlueBubblesApi.messageEvent(data, isNew) ?: return
        SocketBus.incoming.tryEmit(incoming)
        // Notify only for genuinely new incoming messages the user can't see —
        // not group events (renames etc.), whose `text` is empty.
        if (isNew && !incoming.message.fromMe && !incoming.message.isGroupEvent && !AppForeground.active) {
            // Prefer an explicit (group) chat name; otherwise resolve the sender's
            // address to a contact name from the persisted index, falling back to
            // the raw address. Read fresh so it reflects the latest address book.
            val title = incoming.chatDisplayName.ifBlank {
                val sender = incoming.message.sender
                sender?.let { contacts().name(it) ?: it } ?: "Message"
            }
            Notifications.post(this, title, incoming.message.text, incoming.chatGuid)
        }
    }

    /** The persisted contact index. Reloaded per notification (infrequent — only
     *  background messages) so a name added while the service ran still resolves. */
    private fun contacts(): Contacts = Store.contacts(this)

    override fun onDestroy() {
        socket?.disconnect()
        socket?.off()
        socket = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SocketService"
    }
}
