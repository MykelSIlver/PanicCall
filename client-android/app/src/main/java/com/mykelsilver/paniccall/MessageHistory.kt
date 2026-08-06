package com.mykelsilver.paniccall

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local, per-device message history: what YOU sent (with delivery status)
 * and what you received. Persisted as a small JSON file in app-internal
 * storage -- deliberately NOT stored on the relay (see docs/PROTOCOL.md:
 * the relay keeps no message content beyond a single in-flight pending
 * message). Survives app restarts; does not survive an uninstall/reinstall,
 * same tradeoff already accepted for the relay's own durability design,
 * and each device only ever sees its own side of the conversation.
 */
class MessageHistory(context: Context) {

    enum class Direction { SENT, RECEIVED }
    enum class Status { PENDING, SENT, QUEUED, DELIVERED }

    data class Entry(
        val id: String,
        val direction: Direction,
        val peer: String,          // the other person's display name
        val message: String,
        val timestampMs: Long,
        val status: Status,
    )

    private val file = File(context.filesDir, "message_history.json")
    private val entries = mutableListOf<Entry>()   // newest first

    val history = MutableStateFlow<List<Entry>>(emptyList())

    init { load() }

    /** Call right after CallEngine.sendText() returns its id. */
    @Synchronized
    fun addSent(id: String, peer: String, message: String) {
        entries.add(0, Entry(id, Direction.SENT, peer, message,
            System.currentTimeMillis(), Status.PENDING))
        trimAndPublish()
    }

    /** Call when CallEngine.textReceived fires. */
    @Synchronized
    fun addReceived(id: String, peer: String, message: String) {
        entries.add(0, Entry(id, Direction.RECEIVED, peer, message,
            System.currentTimeMillis(), Status.DELIVERED))
        trimAndPublish()
    }

    /** Call on text_sent (SENT/QUEUED) and text_delivered (DELIVERED) acks. */
    @Synchronized
    fun markStatus(id: String, status: Status) {
        val idx = entries.indexOfFirst { it.id == id && it.direction == Direction.SENT }
        if (idx < 0) return             // e.g. a delivered-ack for a message
        entries[idx] = entries[idx].copy(status = status)   // this device never sent
        save()
        history.value = entries.toList()
    }

    private fun trimAndPublish() {
        // A quick-message log, not an unbounded chat archive -- keep it
        // small on purpose, matching the feature's own scope.
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.size - 1)
        save()
        history.value = entries.toList()
    }

    private fun load() {
        entries.clear()
        try {
            if (file.exists()) {
                val arr = JSONArray(file.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    entries.add(Entry(
                        id = o.optString("id"),
                        direction = Direction.valueOf(o.optString("direction", "SENT")),
                        peer = o.optString("peer"),
                        message = o.optString("message"),
                        timestampMs = o.optLong("timestampMs"),
                        status = Status.valueOf(o.optString("status", "SENT")),
                    ))
                }
            }
        } catch (e: Exception) {
            // Corrupt/unreadable history must never crash the app; start
            // fresh -- same "warn and continue, never fatal" spirit as the
            // relay's own corrupt-pending-state handling.
            entries.clear()
        }
        history.value = entries.toList()
    }

    private fun save() {
        try {
            val arr = JSONArray()
            for (e in entries) {
                arr.put(JSONObject()
                    .put("id", e.id)
                    .put("direction", e.direction.name)
                    .put("peer", e.peer)
                    .put("message", e.message)
                    .put("timestampMs", e.timestampMs)
                    .put("status", e.status.name))
            }
            file.writeText(arr.toString())
        } catch (e: Exception) {
            // Best-effort: losing a history write is far less bad than
            // crashing the call/text feature over it.
        }
    }

    companion object {
        private const val MAX_ENTRIES = 200
    }
}
