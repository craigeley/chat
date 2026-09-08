package com.craigeley.chat.socket

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.craigeley.chat.Contacts
import com.craigeley.chat.IncomingMessage
import com.craigeley.chat.Notifications
import com.craigeley.chat.ReadStatusEvent
import com.craigeley.chat.TypingEvent
import com.craigeley.chat.api.BlueBubblesApi
import com.craigeley.chat.api.Store
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import java.net.URLEncoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
 *
 * Three things make it hold up unattended (the battery-vs-realtime balance):
 *
 *  - **Network-driven reconnects.** Two connectivity callbacks drive the socket.
 *    The *default-network* one sees the network the app actually uses — under
 *    Tailscale that's the VPN network — so the tunnel coming up after boot, or
 *    going away, reconnects *immediately* rather than waiting out the backoff.
 *    But a VPN network outlives its underlying link (verified on-device: Wi-Fi
 *    off and even airplane mode leave Tailscale's network in place, and the
 *    socket simply rides the WireGuard migration when the link returns), so a
 *    second callback watches the *physical* (non-VPN) networks: when the last
 *    one goes the socket disconnects and stops retrying — a phone in a dead spot
 *    doesn't burn radio on attempts that can't succeed — and when one appears
 *    while the socket is down it reconnects at once. The library's own backoff
 *    (2s → 60s) still covers the "network up, server down" case.
 *  - **Catch-up on connect.** The server only pushes what happens while the socket
 *    is open; anything that arrived during a gap (reboot, dead spot, server
 *    restart) would be silently missed. So every connect replays messages newer
 *    than the [Store.lastSeenDate] cursor through the same path a live event
 *    takes — the thread/list update and, for unread incoming messages, a
 *    notification fires just as if the socket had been up.
 *  - **Connection state** is published on [SocketBus.connected] (the ViewModel
 *    refreshes on a reconnect and shows a hint while offline) and mirrored on the
 *    ongoing notification's text so the state is visible from Android settings.
 */
class SocketService : Service() {

    private var socket: Socket? = null
    private var api: BlueBubblesApi? = null
    private lateinit var connectivity: ConnectivityManager
    private val main = Handler(Looper.getMainLooper())
    private val catchUpExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Main-thread only: the current default network per the callback (null = none),
    // and the physical (non-VPN) networks that currently offer internet.
    private var currentNetwork: Network? = null
    private val physicalNetworks = HashSet<Network>()
    // The network the live socket was opened on — so a repeated onAvailable for the
    // same network (capability churn) doesn't bounce a healthy connection.
    @Volatile private var connectedOn: Network? = null
    private var callbackRegistered = false

    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            currentNetwork = network
            val s = socket ?: return
            if (s.connected() && connectedOn == network) return
            Log.d(TAG, "default network $network — connecting")
            requestReconnect()
        }

        override fun onLost(network: Network) {
            // Only a loss of the network we're actually on matters; on a handover
            // the new default may be reported first, in which case this is the old
            // one going and there's nothing to do.
            if (network != currentNetwork) return
            currentNetwork = null
            goIdle("default network $network lost")
        }
    }

    private val physicalCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            physicalNetworks.add(network)
            val s = socket ?: return
            if (s.connected()) return // a link change under a live socket needs nothing
            Log.d(TAG, "physical network $network — connecting")
            requestReconnect()
        }

        override fun onLost(network: Network) {
            physicalNetworks.remove(network)
            if (physicalNetworks.isEmpty()) goIdle("no physical network")
        }
    }

    /** Coalesces the two callbacks' reconnect requests (both fire on registration,
     *  and a handover can report several networks in a burst) into one attempt. */
    private val reconnectRunnable = Runnable { reconnectNow() }

    private fun requestReconnect() {
        main.removeCallbacks(reconnectRunnable)
        main.postDelayed(reconnectRunnable, RECONNECT_COALESCE_MS)
    }

    /** No usable network: drop the socket (which also cancels the library's pending
     *  retry timer) and wait for a callback to bring one back. */
    private fun goIdle(why: String) {
        main.removeCallbacks(reconnectRunnable)
        Log.d(TAG, "$why — going idle")
        socket?.disconnect()
        setStatus(STATUS_OFFLINE)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        startForeground(
            Notifications.SERVICE_ID,
            Notifications.foregroundNotification(this, STATUS_CONNECTING),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
        )
        connectivity = getSystemService(ConnectivityManager::class.java)
        if (!setUp()) { stopSelf(); return }
        // Both callbacks fire onAvailable straight away for what's already up, which
        // is what performs the initial connect — so a service started with no
        // network simply waits for one.
        connectivity.registerDefaultNetworkCallback(defaultCallback, main)
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build(),
            physicalCallback,
            main,
        )
        callbackRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /** Builds the client + socket from stored setup. False if setup hasn't run. */
    private fun setUp(): Boolean {
        val password = Store.password(this) ?: return false
        val baseUrl = Store.baseUrl(this) ?: return false
        api = BlueBubblesApi(baseUrl, password)
        val opts = IO.Options().apply {
            transports = arrayOf("websocket") // server upgrades to ws anyway; skip polling
            query = "password=" + URLEncoder.encode(password, "UTF-8")
            forceNew = true // a fresh Manager per service instance (no stale cached one)
            reconnection = true
            // Defaults retry forever with backoff capped at 5s — and websocket-only
            // means every attempt on a half-dead link (TLS up, session dies) pays a
            // full cert-chain handshake, indefinitely, often on cellular. Let a down
            // link settle to ~1 attempt/min instead; the delay resets to fast on a
            // successful reconnect (LP3-23), and the network callback above skips
            // the wait entirely when a network (re)appears.
            reconnectionDelay = 2_000
            reconnectionDelayMax = 60_000
        }
        val s = runCatching { IO.socket(baseUrl, opts) }.getOrNull() ?: return false
        socket = s
        s.on(Socket.EVENT_CONNECT, Emitter.Listener {
            Log.d(TAG, "socket connected")
            connectedOn = currentNetwork
            SocketBus.connected.value = true
            setStatus(STATUS_CONNECTED)
            catchUp()
        })
        s.on(Socket.EVENT_DISCONNECT, Emitter.Listener {
            Log.d(TAG, "socket disconnected: ${it.firstOrNull()}")
            connectedOn = null
            SocketBus.connected.value = false
            setStatus(if (currentNetwork == null) STATUS_OFFLINE else STATUS_RECONNECTING)
        })
        s.on(Socket.EVENT_CONNECT_ERROR, Emitter.Listener {
            Log.w(TAG, "connect error: ${it.firstOrNull()}")
            SocketBus.connected.value = false
            setStatus(if (currentNetwork == null) STATUS_OFFLINE else STATUS_RECONNECTING)
        })
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
        // and the list bumps. They never notify (see deliver's isGroupEvent gate).
        for (event in listOf("group-name-change", "participant-added", "participant-removed", "participant-left")) {
            s.on(event, Emitter.Listener { onMessage(it, isNew = true) })
        }
        return true
    }

    /**
     * (Re)connects right now, skipping any backoff wait. Must be disconnect-then-
     * connect: the client's `connect()` is a no-op while its reconnect timer is
     * pending (`Manager.isReconnecting`), and `disconnect()` is what cancels that
     * timer. Also the right move on a network *change* while connected — the old
     * TCP session is dead on the wire and would only be found out at ping timeout.
     */
    private fun reconnectNow() {
        val s = socket ?: return
        setStatus(STATUS_CONNECTING)
        s.disconnect()
        s.connect()
    }

    /**
     * Replays whatever the server saw while we weren't connected: every message
     * created after the persisted cursor, run through [deliver] exactly like a live
     * `new-message`. Messages already read on another device are merged but don't
     * notify — a phone that was off overnight shouldn't buzz for a morning's worth
     * of conversations you already had on the Mac. No cursor (first run) means
     * nothing to catch up on: the ViewModel's first sweep seeds it.
     */
    private fun catchUp() {
        val since = Store.lastSeenDate(this)
        if (since <= 0L) return
        val client = api ?: return
        catchUpExecutor.execute {
            val missed = runCatching { client.messagesSince(since) }
                .getOrElse { Log.w(TAG, "catch-up failed", it); return@execute }
                .filter { it.message.date > since } // belt and braces on the server's `after`
            Log.d(TAG, "catch-up: ${missed.size} message(s) since $since")
            for (m in missed) deliver(m, replay = true)
        }
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
        deliver(incoming, replay = false)
    }

    /**
     * The one path every message takes, live or replayed: advance the catch-up
     * cursor, hand it to the ViewModel, and notify for a genuinely new incoming
     * message the user can't see — not group events (renames etc., whose `text` is
     * empty), not our own sends, and on a [replay] not anything already read
     * elsewhere.
     */
    private fun deliver(incoming: IncomingMessage, replay: Boolean) {
        val m = incoming.message
        Store.advanceLastSeen(this, m.date)
        SocketBus.incoming.tryEmit(incoming)
        val alert = incoming.isNew && !m.fromMe && !m.isGroupEvent && !AppForeground.active &&
            (!replay || m.dateRead == 0L)
        if (!alert) return
        // Prefer an explicit (group) chat name; otherwise resolve the sender's
        // address to a contact name from the persisted index, falling back to
        // the raw address. Read fresh so it reflects the latest address book.
        val title = incoming.chatDisplayName.ifBlank {
            m.sender?.let { contacts().name(it) ?: it } ?: "Message"
        }
        Notifications.post(this, title, m.text, incoming.chatGuid)
    }

    /** The persisted contact index. Reloaded per notification (infrequent — only
     *  background messages) so a name added while the service ran still resolves. */
    private fun contacts(): Contacts = Store.contacts(this)

    // The ongoing notification's one line of text; only re-posted when it changes,
    // so a flapping link can't spam the notification service.
    @Volatile private var status: String? = null

    private fun setStatus(text: String) {
        if (status == text) return
        status = text
        Notifications.updateForeground(this, text)
    }

    override fun onDestroy() {
        if (callbackRegistered) {
            runCatching { connectivity.unregisterNetworkCallback(defaultCallback) }
            runCatching { connectivity.unregisterNetworkCallback(physicalCallback) }
        }
        main.removeCallbacks(reconnectRunnable)
        catchUpExecutor.shutdownNow()
        socket?.disconnect()
        socket?.off()
        socket = null
        SocketBus.connected.value = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SocketService"
        private const val STATUS_CONNECTING = "Connecting…"
        private const val STATUS_CONNECTED = "Connected"
        private const val STATUS_RECONNECTING = "Reconnecting…"
        private const val STATUS_OFFLINE = "Offline — waiting for a network"
        private const val RECONNECT_COALESCE_MS = 300L
    }
}
