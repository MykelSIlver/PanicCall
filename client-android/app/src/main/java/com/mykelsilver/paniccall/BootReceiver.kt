package com.mykelsilver.paniccall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Android counterpart of the Sailfish daemon's systemd user-service
 * autostart: brings CallService back up after a reboot (and after an app
 * update) so a quick message or an incoming call can arrive without the
 * user ever opening the app.
 *
 * Why this is not just "start the service": CallService declares the
 * `microphone` foreground-service type, and apps targeting Android 14+
 * may NOT launch a microphone foreground service from a BOOT_COMPLETED
 * receiver -- the system throws ForegroundServiceStartNotAllowedException.
 * CallService therefore starts in `specialUse` mode here and only takes
 * on the microphone type later; see CallService.ensureCallCapable().
 *
 * Deliberately does nothing when the app has never been configured: an
 * ongoing notification for an app that cannot connect to anything would
 * be pure noise. The service is started from MainActivity as usual once
 * the user fills in a relay URL and token.
 */
class BootReceiver : BroadcastReceiver() {

    companion object { private const val TAG = "PanicCall" }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            // Non-standard, still emitted by some Samsung/HTC firmware in
            // place of BOOT_COMPLETED after a "fast boot" resume.
            "android.intent.action.QUICKBOOT_POWERON" -> {}
            else -> return
        }

        val p = context.getSharedPreferences("paniccall", Context.MODE_PRIVATE)
        val url = p.getString("url", "") ?: ""
        val token = p.getString("token", "") ?: ""
        if (url.isBlank() || token.isBlank()) {
            Log.w(TAG, "boot: not configured yet, not starting the service")
            return
        }

        try {
            CallService.start(context)
            Log.w(TAG, "METRIC boot_start action=${intent.action}")
        } catch (e: Exception) {
            // Never crash out of a boot broadcast: a failure here must not
            // take the app down before the user can open it and fix things.
            Log.w(TAG, "boot: could not start service: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
