package com.craigeley.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Local notifications. There is no FCM/remote push — the device has no Google Play
 * services — so the socket service posts these itself: a high-importance one per
 * incoming message ([post]), and a quiet ongoing one ([foregroundNotification])
 * that keeps the live-socket service alive.
 */
object Notifications {
    // v2: vibrates. A channel's vibration can't be changed after creation, so the
    // original silent-buzz "messages" channel is replaced (and deleted) — on a Light
    // Phone with no notification shade the buzz *is* the alert.
    private const val MESSAGE_CHANNEL = "messages_v2"
    private const val LEGACY_MESSAGE_CHANNEL = "messages"
    private const val SERVICE_CHANNEL = "service"
    const val SERVICE_ID = 2
    /** Intent extra on a message notification's tap: the chat to open. */
    const val EXTRA_CHAT_GUID = "chatGuid"
    // Message notification ids start above the fixed ids; one per chat (guid-hashed)
    // so each thread keeps its own notification and each deep-links to its chat.
    private const val MESSAGE_ID_BASE = 100

    private fun messageId(chatGuid: String): Int =
        MESSAGE_ID_BASE + Math.floorMod(chatGuid.hashCode(), 1_000_000)

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(MESSAGE_CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(MESSAGE_CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "New iMessages"
                    enableVibration(true)
                },
            )
        }
        if (manager.getNotificationChannel(LEGACY_MESSAGE_CHANNEL) != null) {
            manager.deleteNotificationChannel(LEGACY_MESSAGE_CHANNEL)
        }
        if (manager.getNotificationChannel(SERVICE_CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(SERVICE_CHANNEL, "Connection", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Keeps chat connected for new messages"
                },
            )
        }
    }

    /** The quiet ongoing notification the foreground service runs under; [status]
     *  is its one line of text ("Connected" / "Reconnecting…" / …). */
    fun foregroundNotification(context: Context, status: String): Notification =
        Notification.Builder(context, SERVICE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_reply)
            .setContentTitle("chat")
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .build()

    /** Re-posts the ongoing notification with a new [status] line — the socket's
     *  connection state, visible from Android's notification settings. */
    fun updateForeground(context: Context, status: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(SERVICE_ID, foregroundNotification(context, status))
    }

    /** A per-message notification, posted when a message arrives while backgrounded.
     *  One notification per chat (a newer message replaces it); tapping opens that
     *  chat's thread via the [EXTRA_CHAT_GUID] extra. */
    fun post(context: Context, title: String, text: String, chatGuid: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val id = messageId(chatGuid)
        val snippet = text.trim().take(300)
        val notification = Notification.Builder(context, MESSAGE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_reply)
            .setContentTitle(title)
            .setContentText(snippet)
            .setStyle(Notification.BigTextStyle().bigText(snippet))
            .setContentIntent(openChat(context, chatGuid, id))
            .setAutoCancel(true)
            .build()
        manager.notify(id, notification)
    }

    /** Cancels every message notification — the user is back in the app. Enumerates
     *  what's actually posted (surviving process restarts); the foreground-service
     *  notification is skipped — only stopForeground may remove that. */
    fun clear(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.activeNotifications
            .filter { it.id != SERVICE_ID }
            .forEach { manager.cancel(it.id) }
    }

    /** Cancels one chat's notification — it was read on another device (or opened
     *  here), so the alert is stale. [chatGuids] because a forked group spans rooms. */
    fun clearChat(context: Context, chatGuids: List<String>) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        chatGuids.forEach { manager.cancel(messageId(it)) }
    }

    /** Tap target: launch (or refocus) the app and open [chatGuid]'s thread. The
     *  requestCode is the per-chat id so chats don't share (and overwrite) one
     *  PendingIntent; MainActivity is singleTask, so a running app gets onNewIntent. */
    private fun openChat(context: Context, chatGuid: String, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_CHAT_GUID, chatGuid),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
