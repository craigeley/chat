package com.craigeley.chat

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * Temporarily lifts LightOS's forced grayscale — vandamd's zero-camera trick.
 * The Light Phone's B&W look is just Android's accessibility color-correction
 * (daltonizer) pinned to grayscale, a secure setting; flipping
 * `accessibility_display_daltonizer_enabled` off shows true color instantly
 * (it's a SurfaceFlinger color-matrix change, no restart).
 *
 * Writing it needs `WRITE_SECURE_SETTINGS`, grantable only over adb (one-time):
 *
 *     adb shell pm grant com.craigeley.chat android.permission.WRITE_SECURE_SETTINGS
 *
 * Without the grant every call here no-ops (the SecurityException is swallowed)
 * and the viewer simply stays grayscale like the rest of the phone.
 *
 * [acquire]/[release] bracket the full-screen image viewer's lifetime; the
 * [onAppHidden]/[onAppVisible] pair (MainActivity.onStop/onStart) restores
 * grayscale while the user is elsewhere on the phone and re-lifts it if they
 * come back with the viewer still open. `held` survives that stop/start;
 * nothing is persisted — a process death mid-view can leave the phone in color
 * until the app next runs (same gap zero accepts).
 */
object ColorMode {
    private const val TAG = "ColorMode"
    private const val ENABLED = "accessibility_display_daltonizer_enabled"
    private const val MODE = "accessibility_display_daltonizer"

    /** The daltonizer mode to put back (LightOS pins 0 = simulate monochromacy,
     *  i.e. grayscale); non-null exactly while we're holding the phone in color. */
    private var savedMode: Int? = null

    /** The viewer is open and wants color — kept across activity stop/start. */
    private var held = false

    /** The image viewer opened: show true color until [release]. */
    fun acquire(context: Context) {
        held = true
        lift(context)
    }

    /** Back to grayscale, immediately. The viewer calls this as it closes, in
     *  the same frame it dismisses; the write lands asynchronously, so the
     *  thread may show in color for a frame or two — accepted for an instant
     *  dismissal (see ImageViewerScreen). */
    fun release(context: Context) {
        held = false
        restore(context)
    }

    /** App left the foreground — the rest of the phone should be B&W even if
     *  the viewer is still open underneath. */
    fun onAppHidden(context: Context) {
        restore(context)
    }

    /** App back in the foreground — re-lift if the viewer never closed. */
    fun onAppVisible(context: Context) {
        if (held) lift(context)
    }

    private fun lift(context: Context) {
        val resolver = context.contentResolver
        if (Settings.Secure.getInt(resolver, ENABLED, 0) != 1) return // already color
        val mode = Settings.Secure.getInt(resolver, MODE, 0)
        try {
            Settings.Secure.putInt(resolver, ENABLED, 0)
            savedMode = mode
        } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS not granted; staying grayscale")
        }
    }

    private fun restore(context: Context) {
        val mode = savedMode ?: return
        try {
            Settings.Secure.putInt(context.contentResolver, MODE, mode)
            Settings.Secure.putInt(context.contentResolver, ENABLED, 1)
            savedMode = null
        } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS revoked mid-hold; can't restore grayscale")
        }
    }
}
