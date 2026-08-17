package com.mykelsilver.paniccall

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Mirror of the Sailfish CallEngine: one WebSocket to the relay, the same
 * state machine ("disconnected" | "connecting" | "idle" | "ringing" |
 * "in_call"), reconnect with 1/2/5 s backoff, persist-on-takeover.
 * Owns the AudioPipeline during a call.
 *
 * Threading: OkHttp callbacks arrive on its own threads; everything is
 * funneled to the main handler so state is single-threaded, mirroring the
 * Qt::QueuedConnection design on Sailfish.
 */
class CallEngine {
    companion object { private const val TAG = "PanicCall" }

    val state = MutableStateFlow("disconnected")
    val peerName = MutableStateFlow("")
    val selfName = MutableStateFlow("")
    val peerOnline = MutableStateFlow(false)
    val lastError = MutableStateFlow("")
    val autoAnswer = MutableStateFlow(true)

    /** Off by default: short chirp when the peer comes online/offline. */
    val notifyPresence = MutableStateFlow(false)
    /** On by default: unlike presence, a message is actual content you'd
     * want to notice, not ambient status -- see the README discussion. */
    val notifyTextReceived = MutableStateFlow(true)
    private var toneGen: ToneGenerator? = null

    /** Emits the caller's name on each incoming call (for notifications). */
    val incomingCall = MutableStateFlow<String?>(null)

    /**
     * One-shot text events.
     *
     * These are SharedFlows, not StateFlows, and that is load-bearing.
     * StateFlow is *conflated*: if several values are set before the
     * collector gets a turn, only the last survives. On Android that is a
     * live race, not a theoretical one -- OkHttp's reader thread posts
     * each frame to the main Looper, and the collector resumes on that
     * same Looper, so two frames landing in the queue back to back means
     * the first is overwritten before anyone reads it. Measured with
     * kotlinx-coroutines on a shared single-threaded dispatcher: from 3
     * simultaneous events onwards, losses every run; at 10 events, 8 or 9
     * lost. The relay flushing a pending queue on reconnect (up to
     * --max-pending messages back to back) is exactly that shape, and so
     * is the burst of text_delivered acks it triggers in return.
     *
     * Losing one is silent and total: for textReceived the collector in
     * CallService is the only thing that writes the history row, posts
     * the notification AND sends the delivery ack, so a dropped event
     * loses all three with nothing in the log.
     *
     * `extraBufferCapacity` gives tryEmit() somewhere to put events when
     * the collector is behind; 64 is far more than any realistic burst.
     * replay = 0 (the default) because these are events, not state -- a
     * late subscriber must not be handed a stale message to re-notify
     * about. The nonce fields the StateFlow version needed to distinguish
     * two identical consecutive messages are gone: SharedFlow delivers
     * every emission, equal or not.
     *
     * `msgId` is the real protocol id (see docs/PROTOCOL.md) -- used to
     * correlate the event to the right row in local message history, and
     * to send back text_delivered with the correct id.
     */
    data class TextEvent(val msgId: String, val from: String, val message: String)
    val textReceived = MutableSharedFlow<TextEvent>(extraBufferCapacity = 64)

    /** Feedback for our own sendText(): delivered vs queued. */
    data class TextSentEvent(val msgId: String, val queued: Boolean)
    val textSent = MutableSharedFlow<TextSentEvent>(extraBufferCapacity = 64)

    /** The peer's client has processed our text -- the single checkmark. */
    data class TextDeliveredEvent(val msgId: String)
    val textDelivered = MutableSharedFlow<TextDeliveredEvent>(extraBufferCapacity = 64)

    /**
     * tryEmit() cannot suspend, so it is safe to call from handleControl
     * on the main Looper. It only fails if the buffer is genuinely full,
     * which would mean 64 unread events -- worth a log line, because that
     * is the one remaining way an event can be lost.
     */
    private fun <T> MutableSharedFlow<T>.emitEvent(what: String, value: T) {
        if (!tryEmit(value))
            Log.w(TAG, "event buffer full, DROPPED $what")
    }

    var onCallSetupMeasured: ((Long) -> Unit)? = null

    /**
     * Called synchronously just before AudioRecord is opened, so the host
     * service can take on the `microphone` foreground-service type first
     * (it runs as `specialUse` while merely holding the socket open, which
     * is what lets it be started from a BOOT_COMPLETED receiver at all --
     * see CallService.ensureCallCapable()).
     *
     * Returning false does not abort the attempt: the audio start is tried
     * anyway and its existing catch turns a refusal into a normal
     * lastError, which is strictly better than pre-emptively refusing to
     * ring for a panic call. Null (no host) means "nothing to arrange".
     */
    var ensureMicrophoneAllowed: (() -> Boolean)? = null

    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)      // WS-level keepalive
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var ws: WebSocket? = null
    private var url = ""
    private var token = ""
    private var myName = ""
    private var wantConnected = false
    private var backoffIdx = 0
    private val backoffMs = longArrayOf(1000, 2000, 5000)
    private var audio: AudioPipeline? = null
    private var callSetupStart = 0L
    var reconnects = 0; private set

    fun configure(url: String, token: String, name: String) {
        val n = name.trim().take(32)
        if (url == this.url && token == this.token && n == myName
                && wantConnected && ws != null) return   // idempotent
        this.url = url
        this.token = token
        myName = n
        wantConnected = true
        backoffIdx = 0
        ws?.cancel()
        tryConnect()
    }

    fun startCall() = main.post {
        if (state.value != "idle") return@post
        ws?.send(Protocol.call())
        if (startAudio()) setState("in_call")
    }

    fun answer() = main.post { if (startAudio()) setState("in_call") }

    fun hangup() = main.post {
        ws?.send(Protocol.hangup())
        stopAudio()
        if (state.value == "in_call" || state.value == "ringing") setState("idle")
    }

    fun sendKeepalivePing() { ws?.send(Protocol.ping()) }

    /**
     * Returns the generated message id immediately (id generation has no
     * thread affinity) so the caller can log a "pending" row in local
     * history under the right id right away, without waiting for the
     * actual send -- which still happens on the main looper, same as
     * every other ws.send() in this class.
     */
    fun sendText(message: String): String {
        val trimmed = message.trim()
        val id = java.util.UUID.randomUUID().toString()
        if (trimmed.isNotEmpty()) main.post { ws?.send(Protocol.text(id, trimmed)) }
        return id
    }

    fun sendTextDelivered(id: String) = main.post {
        ws?.send(Protocol.textDelivered(id))
    }

    fun shutdown() = main.post {
        wantConnected = false
        stopAudio()
        ws?.cancel()
        setState("disconnected")
    }

    // ------------------------------------------------------------------

    private fun tryConnect() {
        if (!wantConnected || url.isBlank()) return
        setState("connecting")
        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, listener)
    }

    private fun scheduleReconnect() {
        if (!wantConnected) return
        val ms = backoffMs[minOf(backoffIdx, backoffMs.size - 1)]
        if (backoffIdx < backoffMs.size) backoffIdx++
        reconnects++
        main.postDelayed({ tryConnect() }, ms)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(Protocol.hello(token, myName))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            main.post { handleControl(text) }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // Audio: decode straight on OkHttp's thread; the pipeline's
            // player has its own thread-safe queue.
            audio?.receive(bytes.toByteArray())
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            main.post { handleDisconnect(code) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, r: Response?) {
            main.post {
                Log.w(TAG, "ws failure: ${t.message}")
                handleDisconnect(0)
            }
        }
    }

    private fun handleDisconnect(code: Int) {
        stopAudio()
        when (code) {
            Protocol.CLOSE_BAD_TOKEN ->
                { setError("Server does not know this token"); wantConnected = false }
            Protocol.CLOSE_BAD_PROTO ->
                { setError("Protocol version not supported"); wantConnected = false }
            Protocol.CLOSE_REPLACED ->
                setError("Token taken over — reconnecting")   // persist: we fight back
        }
        peerOnline.value = false
        setState("disconnected")
        scheduleReconnect()
    }

    private fun handleControl(text: String) {
        val o = try { JSONObject(text) } catch (e: Exception) { return }
        when (o.optString("type")) {
            "welcome" -> {
                backoffIdx = 0
                selfName.value = o.optString("you")
                peerName.value = o.optString("peer")
                peerOnline.value = o.optBoolean("peer_online")
                setError("")
                setState("idle")
            }
            "peer_online" -> {
                peerOnline.value = true
                if (notifyPresence.value) playPresenceBlip(online = true)
            }
            "peer_offline" -> {
                peerOnline.value = false
                // A peer that vanishes mid-call (dead battery, out of
                // coverage, tunnel) ends the call. The relay keeps no call
                // state of its own and drops audio for an absent peer, so
                // there is nothing left to reconnect to: without this the
                // call would sit in "in_call" forever with the microphone
                // open, streaming into the void, and the state would go
                // asymmetric the moment the peer reconnects (their welcome
                // puts them back in "idle"). Same handling as the "hangup"
                // branch below, minus sending a hangup nobody can hear.
                stopAudio()
                if (state.value == "in_call" || state.value == "ringing")
                    setState("idle")
                if (notifyPresence.value) playPresenceBlip(online = false)
            }
            "text" -> {
                val msgId = o.optString("id")
                val from = o.optString("from")
                val message = o.optString("message")
                textReceived.emitEvent("text $msgId",
                    TextEvent(msgId, from, message))
                if (notifyTextReceived.value) playTextReceivedTone()
            }
            "text_sent" -> {
                val id = o.optString("id")
                textSent.emitEvent("text_sent $id",
                    TextSentEvent(id, o.optBoolean("queued")))
            }
            "text_delivered" -> {
                val id = o.optString("id")
                textDelivered.emitEvent("text_delivered $id",
                    TextDeliveredEvent(id))
            }
            "peer_name" -> o.optString("name").takeIf { it.isNotEmpty() }
                ?.let { peerName.value = it }
            "incoming_call" -> {
                callSetupStart = System.currentTimeMillis()
                incomingCall.value = o.optString("from")
                if (state.value == "idle") {
                    if (autoAnswer.value) {
                        if (startAudio()) {
                            setState("in_call")
                            val ms = System.currentTimeMillis() - callSetupStart
                            Log.w(TAG, "METRIC call_setup_ms=$ms")
                            onCallSetupMeasured?.invoke(ms)
                        }
                    } else setState("ringing")
                }
            }
            "hangup" -> {
                stopAudio()
                if (state.value == "in_call" || state.value == "ringing")
                    setState("idle")
            }
            "error" -> setError(o.optString("reason"))
        }
    }

    private fun startAudio(): Boolean {
        if (audio != null) return true
        if (ensureMicrophoneAllowed?.invoke() == false) {
            Log.w(TAG, "starting audio without the microphone FGS type; " +
                    "AudioRecord may be refused")
        }
        return try {
            audio = AudioPipeline { frame -> ws?.send(frame.toByteString()) }
                .also { it.start() }
            true
        } catch (e: Exception) {
            setError("audio: ${e.message}")
            audio = null
            false
        }
    }

    private fun stopAudio() {
        audio?.stop()
        audio = null
    }

    private fun setState(s: String) {
        if (state.value != s) {
            state.value = s
            Log.w(TAG, "state $s")
        }
    }

    private fun setError(e: String) {
        lastError.value = e
        if (e.isNotEmpty()) Log.w(TAG, "error: $e")
    }

    /**
     * Short built-in confirmation/rejection tone -- no custom frequency
     * table needed, unlike the Sailfish side, since Android's ToneGenerator
     * already ships an ACK/NACK pair for exactly this "something changed"
     * signal. Needs no Context (unlike RingtoneManager, which is why the
     * ringtone lives in CallService but this lives here in CallEngine).
     */
    private fun playPresenceBlip(online: Boolean) {
        try {
            val tg = toneGen ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
                .also { toneGen = it }
            tg.startTone(
                if (online) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_NACK,
                150)
        } catch (e: Exception) {
            Log.w(TAG, "presence blip failed: ${e.message}")
        }
    }

    /**
     * Two identical beeps -- unlike the ACK/NACK pair above (which differ
     * in pitch/character), this is meant to be tellable apart by ear at a
     * glance. Same ToneGenerator instance, no Context needed, same
     * reasoning as playPresenceBlip(). The gap between beeps uses this
     * class's existing Handler-based delay pattern (see connect()'s
     * postDelayed reconnect), not coroutines -- no coroutine scope exists
     * in this class otherwise, and one beep-gap isn't reason to add one.
     */
    private fun playTextReceivedTone() {
        try {
            val tg = toneGen ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
                .also { toneGen = it }
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
            main.postDelayed({
                try {
                    tg.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
                } catch (e: Exception) {
                    Log.w(TAG, "text-received tone (2nd beep) failed: ${e.message}")
                }
            }, 160)
        } catch (e: Exception) {
            Log.w(TAG, "text-received tone failed: ${e.message}")
        }
    }
}
