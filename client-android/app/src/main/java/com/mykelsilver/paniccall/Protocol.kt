package com.mykelsilver.paniccall

import org.json.JSONObject

/**
 * Wire format, mirroring docs/PROTOCOL.md exactly (proto 1, v1.1 name
 * extension). Keep this file free of Android imports: it is the shared
 * truth between platforms and should stay trivially portable/testable.
 */
object Protocol {
    const val VERSION = 1

    const val FRAME_AUDIO: Byte = 0x01
    const val HEADER_LEN = 7

    // WebSocket close codes from the relay
    const val CLOSE_BAD_TOKEN = 4001
    const val CLOSE_REPLACED = 4003
    const val CLOSE_BAD_PROTO = 4004

    fun hello(token: String, name: String): String {
        val o = JSONObject()
        o.put("type", "hello")
        o.put("token", token)
        o.put("proto", VERSION)
        if (name.isNotBlank()) o.put("name", name.trim().take(32))
        return o.toString()
    }

    fun call(): String = JSONObject().put("type", "call").toString()
    fun hangup(): String = JSONObject().put("type", "hangup").toString()
    fun ping(): String = JSONObject().put("type", "ping").toString()
    fun text(message: String): String =
        JSONObject().put("type", "text").put("message", message.trim().take(200)).toString()

    /**
     * 7-byte header + raw Opus packet:
     *   [0]    0x01
     *   [1..2] sequence, uint16 BE (wraps)
     *   [3..6] capture timestamp ms, uint32 BE (wraps)
     */
    fun packAudio(seq: Int, tsMs: Long, opus: ByteArray, opusLen: Int): ByteArray {
        val out = ByteArray(HEADER_LEN + opusLen)
        out[0] = FRAME_AUDIO
        out[1] = ((seq shr 8) and 0xff).toByte()
        out[2] = (seq and 0xff).toByte()
        out[3] = ((tsMs shr 24) and 0xff).toByte()
        out[4] = ((tsMs shr 16) and 0xff).toByte()
        out[5] = ((tsMs shr 8) and 0xff).toByte()
        out[6] = (tsMs and 0xff).toByte()
        System.arraycopy(opus, 0, out, HEADER_LEN, opusLen)
        return out
    }

    /** Returns the Opus payload, or null if this is not an audio frame. */
    fun unpackAudio(frame: ByteArray): ByteArray? {
        if (frame.size <= HEADER_LEN || frame[0] != FRAME_AUDIO) return null
        return frame.copyOfRange(HEADER_LEN, frame.size)
    }
}
