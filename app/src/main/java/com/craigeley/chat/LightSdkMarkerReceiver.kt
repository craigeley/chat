package com.craigeley.chat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// No-op. Exists so LightOS can discover this app as a tool via
// queryBroadcastReceivers(ACTION_SDK_MARKER).
class LightSdkMarkerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) = Unit
}
