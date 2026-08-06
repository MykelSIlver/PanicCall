package com.mykelsilver.paniccall

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
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
    private var toneGen: ToneGenerator? = null

    /** Emits the caller's name on each incoming call (for notifications). */
    val incomingCall = MutableStateFlow<String?>(null)

    /**
     * `nonce` exists purely so two consecutive IDENTICAL messages from the
     * same peer still produce distinct StateFlow values -- StateFlow only
     * notifies collectors when the value actually *changes*, so without a
     * nonce a repeated "call me on MeshChat" in a row would silently not
     * re-trigger the notification on the second send. `msgId` is the real
     * protocol id (see docs/PROTOCOL.md) -- used to correlate this event
     * to the right row in local message history, and to send back
     * text_delivered with the correct id.
     */
    data class TextEvent(val msgId: String, val from: String, val message: String, val nonce: Long)
    val textReceived = MutableStateFlow<TextEvent?>(null)
    private var textEventCounter = 0L

    /** Feedback for our own sendText(): delivered vs queued. Same nonce
     * reasoning as TextEvent. */
    data class TextSentEvent(val msgId: String, val queued: Boolean, val nonce: Long)
    val textSent = MutableStateFlow<TextSentEvent?>(null)
    private var textSentCounter = 0L

    /** The peer's client has processed our text -- the single checkmark. */
    data class TextDeliveredEvent(val msgId: String, val nonce: Long)
    val textDelivered = MutableStateFlow<TextDeliveredEvent?>(null)
    private var textDeliveredCounter = 0L

    var onCallSetupMeasured: ((Long) -> Unit)? = null

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
                if (notifyPresence.value) playPresenceBlip(online = false)
            }
            "text" -> {
                val msgId = o.optString("id")
                val from = o.optString("from")
                val message = o.optString("message")
                textReceived.value = TextEvent(msgId, from, message, textEventCounter++)
            }
            "text_sent" -> {
                textSent.value = TextSentEvent(
                    o.optString("id"), o.optBoolean("queued"), textSentCounter++)
            }
            "text_delivered" -> {
                textDelivered.value =
                    TextDeliveredEvent(o.optString("id"), textDeliveredCounter++)
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
}
