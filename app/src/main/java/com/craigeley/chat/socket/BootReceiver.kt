package com.craigeley.chat.socket

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.craigeley.chat.api.Store

/**
 * Restarts the live-socket [SocketService] without the user opening the app —
 * the difference between a demo and an always-on texting app:
 *
 *  - after a reboot (`BOOT_COMPLETED`, which arrives post-unlock so encrypted
 *    prefs / Keystore are available), and
 *  - after the app is updated (`MY_PACKAGE_REPLACED` — an install kills the
 *    running process and nothing else brings the service back until the next
 *    launch, which for a sideloaded/Obtainium update could be hours of silence).
 *
 * Only fires once setup has run (a server URL and password are stored). The
 * service's `remoteMessaging` type is allowed to start from these broadcasts on
 * Android 14+ (dataSync would be blocked); the start is still wrapped so a policy
 * refusal degrades to "starts on next launch" rather than a crash.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        if (!Store.hasPassword(context) || Store.baseUrl(context) == null) return
        runCatching { context.startForegroundService(Intent(context, SocketService::class.java)) }
            .onFailure { Log.w("BootReceiver", "couldn't start SocketService on ${intent.action}", it) }
    }
}
