package com.mykelsilver.paniccall

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import java.util.concurrent.LinkedBlockingQueue
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder

/**
 * Same audio contract as the Sailfish GStreamer pipelines:
 * 48 kHz mono S16, Opus 24 kbit/s VOIP, 20 ms frames (960 samples),
 * inband FEC on. One Opus packet per WebSocket binary message, wrapped
 * in the 7-byte header (Protocol.packAudio).
 *
 * Two plain threads: capture (AudioRecord -> encode -> sendFrame) and
 * playback (queue -> decode -> AudioTrack). Playback is walkie-talkie
 * style — play as it arrives — mirroring pulsesink sync=false on
 * Sailfish; the real jitter buffer stays a shared roadmap item.
 */
class AudioPipeline(private val sendFrame: (ByteArray) -> Unit) {

    private val sampleRate = 48000
    private val frameSamples = 960                  // 20 ms @ 48 kHz
    // Frames to accumulate before playback starts (~80ms at 20ms/frame,
    // matching the Sailfish side). Proven-safe: only shown to delay the
    // START of playback; not a full mid-call jitter smoother.
    private val jitterPrebufferFrames = 4

    @Volatile private var running = false
    private var captureThread: Thread? = null
    private var playThread: Thread? = null
    private val rxQueue = LinkedBlockingQueue<ByteArray>(64)

    fun start() {
        running = true

        captureThread = Thread({
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, frameSamples * 2 * 4))
            val enc = OpusEncoder(sampleRate, 1, OpusApplication.OPUS_APPLICATION_VOIP)
            enc.bitrate = 24000
            enc.useInbandFEC = true
            val pcm = ShortArray(frameSamples)
            val opus = ByteArray(1275)              // max Opus packet
            var seq = 0
            val t0 = SystemClock.elapsedRealtime()
            rec.startRecording()
            try {
                while (running) {
                    var got = 0
                    while (got < frameSamples && running) {
                        val n = rec.read(pcm, got, frameSamples - got)
                        if (n <= 0) break
                        got += n
                    }
                    if (got < frameSamples) continue
                    val len = enc.encode(pcm, 0, frameSamples, opus, 0, opus.size)
                    val ts = SystemClock.elapsedRealtime() - t0
                    sendFrame(Protocol.packAudio(seq, ts, opus, len))
                    seq = (seq + 1) and 0xffff
                }
            } finally {
                rec.stop(); rec.release()
            }
        }, "paniccall-capture").also { it.start() }

        playThread = Thread({
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
                maxOf(minBuf, frameSamples * 2 * 6),
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE)
            val dec = OpusDecoder(sampleRate, 1)
            val pcm = ShortArray(frameSamples * 6)

            // Proven-safe jitter mitigation: decode and write a few frames
            // into AudioTrack's buffer BEFORE calling play(), so a brief
            // arrival stall right after answering has slack to draw from
            // (leans on AudioTrack's own buffer capacity, sized generously
            // above). Bounded wait so a very short call can't hang here.
            // This delays the START of playback; it does not smooth
            // ongoing mid-call jitter (that needs PTS-based clock sync on
            // a real sink -- testing on the Sailfish side showed that is
            // not a simple property tweak, see docs/CLIENT.md).
            var buffered = 0
            val prebufferDeadline = SystemClock.elapsedRealtime() + 500
            while (buffered < jitterPrebufferFrames && running &&
                    SystemClock.elapsedRealtime() < prebufferDeadline) {
                val opus = rxQueue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS)
                    ?: continue
                val n = dec.decode(opus, 0, opus.size, pcm, 0, pcm.size, false)
                if (n > 0) { track.write(pcm, 0, n); buffered++ }
            }
            track.play()
            try {
                while (running) {
                    val opus = rxQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                        ?: continue
                    val n = dec.decode(opus, 0, opus.size, pcm, 0, pcm.size, false)
                    if (n > 0) track.write(pcm, 0, n)
                }
            } finally {
                track.stop(); track.release()
            }
        }, "paniccall-play").also { it.start() }
    }

    /** Called from the WebSocket thread with a raw wire frame. */
    fun receive(frame: ByteArray) {
        val opus = Protocol.unpackAudio(frame) ?: return
        rxQueue.offer(opus)                          // drop if flooded
    }

    fun stop() {
        running = false
        captureThread?.join(500)
        playThread?.join(500)
        captureThread = null
        playThread = null
        rxQueue.clear()
    }
}
