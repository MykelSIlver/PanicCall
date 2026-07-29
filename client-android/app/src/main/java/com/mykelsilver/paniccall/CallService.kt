package com.mykelsilver.paniccall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Android counterpart of the Sailfish systemd daemon: owns the engine
 * permanently as a foreground service, auto-answers with the UI closed,
 * raises a full-screen incoming-call notification, and logs the same
 * METRIC lines (via logcat: adb logcat -s PanicCall).
 */
class CallService : LifecycleService() {

    companion object {
        private const val TAG = "PanicCall"
        private const val CH_ONGOING = "paniccall_service"
        private const val CH_CALL = "paniccall_incoming"
        private const val NOTIF_ONGOING = 1
        private const val NOTIF_CALL = 2

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, CallService::class.java))
        }
    }

    val engine = CallEngine()
    inner class LocalBinder : Binder() { val service get() = this@CallService }
    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(NOTIF_ONGOING, ongoingNotification("PanicCall standby"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

        applySettings()

        lifecycleScope.launch {
            engine.state.collectLatest { s ->
                notifyOngoing("PanicCall: $s")
                if (s == "in_call" || s == "ringing") postIncomingCallUi()
            }
        }
        lifecycleScope.launch {
            // App-level keepalive, mirroring the Sailfish 2.5-minute wakeup.
            // Doze will throttle this; measuring by how much is exactly the
            // point of the METRIC lines.
            var n = 0
            while (true) {
                delay(150_000)
                engine.sendKeepalivePing()
                Log.w(TAG, "METRIC wakeup n=${++n}")
            }
        }
        lifecycleScope.launch {
            val t0 = System.currentTimeMillis()
            while (true) {
                delay(300_000)
                Log.w(TAG, "METRIC alive uptime_s=${(System.currentTimeMillis() - t0) / 1000}" +
                        " state=${engine.state.value} reconnects=${engine.reconnects}")
            }
        }
    }

    /** Re-read settings (called by the UI after the settings dialog). */
    fun applySettings() {
        val p = getSharedPreferences("paniccall", MODE_PRIVATE)
        engine.autoAnswer.value = p.getBoolean("autoAnswer", true)
        val url = p.getString("url", "") ?: ""
        val token = p.getString("token", "") ?: ""
        val name = p.getString("name", "") ?: ""
        if (url.isNotBlank() && token.isNotBlank())
            engine.configure(url, token, name)
        else
            Log.w(TAG, "not configured yet (set URL and token in the app)")
    }

    override fun onDestroy() {
        engine.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------ notif ---

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(
            CH_ONGOING, "PanicCall service",
            NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(
            CH_CALL, "Incoming panic calls",
            NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)        // engine audio is the "ringtone" for now
        })
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE)

    private fun ongoingNotification(text: String): Notification =
        Notification.Builder(this, CH_ONGOING)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(text)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .build()

    private fun notifyOngoing(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ONGOING, ongoingNotification(text))
    }

    private fun postIncomingCallUi() {
        val n = Notification.Builder(this, CH_CALL)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("PanicCall from ${engine.peerName.value}")
            .setCategory(Notification.CATEGORY_CALL)
            .setFullScreenIntent(contentIntent(), true)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_CALL, n)
    }
}
