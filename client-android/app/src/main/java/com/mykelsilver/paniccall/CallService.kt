package com.mykelsilver.paniccall

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
        private const val CH_TEXT = "paniccall_text"
        private const val NOTIF_ONGOING = 1
        private const val NOTIF_CALL = 2
        private const val NOTIF_TEXT = 3

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, CallService::class.java))
        }
    }

    val engine = CallEngine()
    lateinit var history: MessageHistory   // Context-dependent; see onCreate()

    /**
     * Whether this foreground service currently carries the `microphone`
     * type, i.e. whether it is allowed to open AudioRecord.
     *
     * The service has two modes. In STANDBY it only holds the relay
     * WebSocket open, which needs no microphone at all, and runs as
     * `specialUse` -- the one type a BOOT_COMPLETED receiver is allowed
     * to start on Android 14/15+. Before a call it is promoted to
     * `specialUse|microphone` and then stays there for the rest of the
     * service's life; re-promoting on every call would just add another
     * chance to be refused, and the type is a declared capability, not
     * live microphone use (the privacy indicator follows AudioRecord,
     * not this flag).
     */
    @Volatile var callCapable = false
        private set

    // Kept so ensureCallCapable() can re-post the same ongoing notification
    // it would otherwise have to invent; startForeground() needs one.
    private var ongoingText = "PanicCall standby"

    // Speakerphone control. Android routes VOICE_COMMUNICATION streams to
    // the earpiece by default (like a regular phone call, for privacy) —
    // wrong default for a panic/baby-monitor call, where being heard is
    // the point. We default to the loudspeaker and let the UI toggle it.
    // Default is loudspeaker (see applySettings); user-configurable.
    val speakerOn = MutableStateFlow(true)
    private lateinit var audioManager: AudioManager
    private var previousAudioMode = AudioManager.MODE_NORMAL
    inner class LocalBinder : Binder() { val service get() = this@CallService }
    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        history = MessageHistory(this)
        createChannels()
        startForegroundInCurrentMode()

        // CallEngine auto-answers from inside its own websocket handling,
        // synchronously, before any StateFlow collector in this class gets
        // a turn -- so the promotion has to hang off a direct hook rather
        // than off engine.state, or it would always run too late.
        engine.ensureMicrophoneAllowed = { ensureCallCapable() }

        // Subscribe BEFORE applySettings() opens the websocket. The text
        // flows are SharedFlows with replay = 0, so an event emitted while
        // nobody is collecting is gone -- and the relay delivers its whole
        // pending queue immediately after the hello handshake. With the
        // old conflated StateFlows a late subscriber still saw the last
        // value, which masked this ordering; it does not any more.
        lifecycleScope.launch {
            // collect, not collectLatest: every text event must be handled
            // to completion. collectLatest would cancel a half-finished
            // handler (history write + notification + ack) the moment the
            // next message arrived -- the very burst this is here to
            // survive. State updates below can still use collectLatest,
            // where superseding really is the desired behaviour.
            engine.textReceived.collect { event ->
                postTextNotification(event.from, event.message)
                history.addReceived(event.msgId, event.from, event.message)
                // Ack back to the sender so their history can show the
                // single checkmark. Not queued if they're offline right
                // now (see docs/PROTOCOL.md) -- a known, accepted gap.
                engine.sendTextDelivered(event.msgId)
            }
        }
        lifecycleScope.launch {
            engine.textSent.collect { event ->
                history.markStatus(event.msgId,
                    if (event.queued) MessageHistory.Status.QUEUED
                    else MessageHistory.Status.SENT)
            }
        }
        lifecycleScope.launch {
            engine.textDelivered.collect { event ->
                history.markStatus(event.msgId, MessageHistory.Status.DELIVERED)
            }
        }

        applySettings()

        lifecycleScope.launch {
            engine.state.collectLatest { s ->
                notifyOngoing("PanicCall: $s")
                if (s == "in_call" || s == "ringing") postIncomingCallUi()
                if (s == "in_call") enterCallAudioMode()
                if (s == "idle" || s == "disconnected") exitCallAudioMode()
                // collectLatest cancels this whole block the instant s changes
                // again, so ringLoop()'s finally{} (below) fires automatically
                // on answer/hangup/peer-hangup/disconnect -- one place, same
                // "can't forget to stop it" guarantee as the Sailfish side.
                if (s == "ringing") ringLoop()
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

    // ------------------------------------------------- foreground type ---

    /**
     * (Re)enters the foreground with the type set appropriate to the
     * current [callCapable] mode. Calling startForeground() again on an
     * already-foreground service is the only way to change its type.
     *
     * Below API 34 there is no specialUse type and no BOOT_COMPLETED type
     * restriction either, so the service simply always carries the
     * microphone type, exactly as it did before this split existed.
     */
    private fun startForegroundInCurrentMode() {
        val notif = ongoingNotification(ongoingText)
        when {
            Build.VERSION.SDK_INT >= 34 -> {
                val types = if (callCapable)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                startForeground(NOTIF_ONGOING, notif, types)
            }
            Build.VERSION.SDK_INT >= 29 -> {
                callCapable = true
                startForeground(NOTIF_ONGOING, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            }
            else -> {
                callCapable = true
                startForeground(NOTIF_ONGOING, notif)   // no FGS types before API 29
            }
        }
    }

    /**
     * Takes on the microphone foreground-service type so a call can open
     * AudioRecord. Idempotent and safe to call from anywhere.
     *
     * IMPORTANT -- what the return value does and does not mean.
     * `startForeground()` not throwing is NOT the system granting
     * microphone access. Android decides that separately, based on
     * whether the app was in the foreground at the moment the service
     * entered the foreground state, and it records its verdict in its own
     * log ("Foreground service started from background can not have
     * location/camera/microphone access: service .../.CallService") with
     * no API for the app to read it back. Observed on a Samsung S22 Ultra
     * after a cold boot: startForeground succeeded, and that line appeared
     * anyway. So this reports the two things it can genuinely observe --
     * whether the call threw, and whether the app was in the foreground --
     * and deliberately does not claim more than that.
     *
     * Practical consequence: promotion attempted while the app is in the
     * background is likely to leave the microphone restricted until the
     * user opens the app once. Quick messages and their notifications are
     * unaffected either way; they need no microphone.
     */
    fun ensureCallCapable(): Boolean {
        if (callCapable) return true
        val fg = appIsInForeground()
        return try {
            callCapable = true            // read by startForegroundInCurrentMode()
            startForegroundInCurrentMode()
            Log.w(TAG, "METRIC fgs_promote startForeground=ok appInForeground=$fg" +
                    if (!fg) " WARNING: promoted from background, the system may " +
                            "still refuse microphone access; check logcat for " +
                            "\"can not have location/camera/microphone access\"" else "")
            true
        } catch (e: Exception) {
            callCapable = false
            Log.w(TAG, "METRIC fgs_promote startForeground=refused " +
                    "appInForeground=$fg err=${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * Whether this app currently has a visible/foreground process. Used
     * only to qualify the promotion metric above -- it is the same input
     * the platform uses for the while-in-use decision, so it is the best
     * proxy available from inside the app.
     */
    private fun appIsInForeground(): Boolean = try {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
    } catch (e: Exception) {
        false
    }

    /**
     * Sends a text AND records it in local history.
     *
     * Every UI send path must go through here rather than calling
     * engine.sendText() directly. On Sailfish the engine owns its
     * MessageHistory and records the row itself; on Android the history
     * needs a Context, so it lives here on the service instead, and
     * sending is therefore two steps that are easy to get half-right.
     * That is exactly what happened in v0.2.10: the reply field on the
     * history screen called engine.sendText() on its own, so replies went
     * out over the wire but never appeared in history. One entry point
     * means a future send path cannot forget the second half.
     *
     * Returns the message id, or "" if the message was blank and nothing
     * was sent.
     */
    fun sendText(message: String): String {
        val id = engine.sendText(message)
        if (id.isNotEmpty())
            history.addSent(id, engine.peerName.value, message.trim())
        return id
    }

    /** Re-read settings (called by the UI after the settings dialog). */
    fun applySettings() {
        val p = getSharedPreferences("paniccall", MODE_PRIVATE)
        engine.autoAnswer.value = p.getBoolean("autoAnswer", true)
        speakerOn.value = p.getBoolean("defaultSpeaker", true)
        engine.notifyPresence.value = p.getBoolean("notifyPresence", false)
        engine.notifyTextReceived.value = p.getBoolean("notifyTextReceived", true)
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

    // ------------------------------------------------------------ audio ---

    /**
     * Plays the device's own ringtone (respects the user's chosen tone,
     * volume, and Do Not Disturb / silent-mode policy automatically --
     * unlike a bundled sound we'd have to reinvent all of that ourselves)
     * for as long as this coroutine runs. [Ringtone] has no built-in loop,
     * so this polls [Ringtone.isPlaying] and restarts it when it stops.
     *
     * Cancellation IS the stop mechanism: called only from inside
     * `engine.state.collectLatest { ... }` guarded on `s == "ringing"`,
     * so entering any other state cancels this coroutine and the finally
     * block below runs -- mirrors how the Sailfish side centralizes
     * ringtone start/stop in one place (setState) rather than at every
     * answer/hangup/disconnect call site.
     */
    private suspend fun ringLoop() {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(
            this, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val ringtone: Ringtone? = try {
            RingtoneManager.getRingtone(this, uri)
        } catch (e: Exception) {
            Log.w(TAG, "ringtone: could not load default ringtone: ${e.message}")
            null
        }
        if (ringtone == null) {
            Log.w(TAG, "ringtone: no default ringtone available on this device")
            return
        }
        ringtone.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        try {
            while (true) {
                if (!ringtone.isPlaying) ringtone.play()
                delay(400)   // isPlaying-poll interval; short enough that a
                             // dropped/short tone gets restarted promptly
            }
        } finally {
            ringtone.stop()
        }
    }

    /** Enter voice-call audio mode and route to the current speakerOn choice. */
    private fun enterCallAudioMode() {
        previousAudioMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        applyRouting(speakerOn.value)
    }

    private fun exitCallAudioMode() {
        audioManager.mode = previousAudioMode
        if (Build.VERSION.SDK_INT < 31) {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        }
    }

    /** Toggle called from the UI; only meaningful during a call. */
    fun toggleSpeaker() {
        speakerOn.value = !speakerOn.value
        if (engine.state.value == "in_call") applyRouting(speakerOn.value)
    }

    private fun applyRouting(speaker: Boolean) {
        if (Build.VERSION.SDK_INT >= 31) {
            // Modern API: pick an explicit communication device rather than
            // the deprecated isSpeakerphoneOn boolean.
            val devices = audioManager.availableCommunicationDevices
            val wantType = if (speaker) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                          else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            val target = devices.firstOrNull { it.type == wantType }
            if (target != null) {
                val ok = audioManager.setCommunicationDevice(target)
                if (!ok) Log.w(TAG, "setCommunicationDevice failed for type $wantType")
            } else {
                Log.w(TAG, "no communication device of type $wantType " +
                        "(devices: ${devices.map { it.type }})")
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = speaker
        }
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
                // Sound is handled by ringLoop() via RingtoneManager, not by
                // this notification -- avoids playing the ringtone twice.
                setSound(null, null)
        })
        nm.createNotificationChannel(NotificationChannel(
            CH_TEXT, "Quick messages",
            // HIGH, not DEFAULT: a heads-up banner that appears over
            // whatever is on screen, so a message is noticed without the
            // app taking the screen over the way an incoming call does.
            // Deliberately NOT a full-screen intent -- that would make
            // "on my way" look exactly like an emergency, which destroys
            // the distinction the whole app is built around. Users who
            // find this too loud can turn the channel down in Android's
            // own notification settings; that is what channels are for.
            NotificationManager.IMPORTANCE_HIGH))
    }

    /**
     * Intent for the ongoing/call notifications: just bring the app up.
     *
     * requestCode 0 is deliberate and must stay distinct from the one used
     * by textContentIntent(). PendingIntent equality ignores *extras*, so
     * two getActivity() calls sharing a request code and component hand
     * back the same cached object -- the second one's extras would be
     * silently thrown away.
     */
    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE)

    /**
     * Intent for a quick-message notification: bring the app up AND open
     * the message history, which is where the message you just got is,
     * and where the reply field lives.
     *
     * FLAG_ACTIVITY_SINGLE_TOP together with android:launchMode="singleTop"
     * in the manifest is what makes this work when the app is already
     * running: without it the system just brings the existing task to the
     * front and never delivers this intent, so the extra is only ever seen
     * on a cold start -- the one case that matters least.
     *
     * FLAG_UPDATE_CURRENT so the extra in a cached PendingIntent is
     * refreshed rather than reused.
     */
    private fun textContentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 1,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_SHOW_HISTORY, true),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun ongoingNotification(text: String): Notification =
        Notification.Builder(this, CH_ONGOING)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(text)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .build()

    private fun notifyOngoing(text: String) {
        ongoingText = text
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

    private fun postTextNotification(from: String, message: String) {
        val n = Notification.Builder(this, CH_TEXT)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(from)
            .setContentText(message)
            .setContentIntent(textContentIntent())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_TEXT, n)
    }
}
