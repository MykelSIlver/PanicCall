import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.Executors

/**
 * Models the Android arrangement exactly:
 *
 *   OkHttp reader thread -> main.post { handleControl(...) }   (Looper)
 *   CallService          -> lifecycleScope.launch { flow.collect { ... } }
 *
 * lifecycleScope defaults to Dispatchers.Main and Handler.post targets the
 * same Looper, so runnables AND coroutine resumptions share ONE FIFO
 * queue. A single-threaded executor used as both "handler" and
 * CoroutineDispatcher reproduces that.
 *
 * Compares the old conflated StateFlow against the new buffered
 * SharedFlow, with a collector body that does real work (as
 * CallService's does: notification + history write + ack).
 */
data class TextEvent(val msgId: String, val message: String)

fun main() = runBlocking {
    val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "looper") }
    val looper = exec.asCoroutineDispatcher()

    suspend fun runStateFlow(n: Int): Int {
        val seen = mutableListOf<String>()
        var nonce = 0L
        val flow = MutableStateFlow<Triple<String, String, Long>?>(null)
        val job = CoroutineScope(looper).launch {
            flow.collectLatest { if (it != null) { work(); seen.add(it.first) } }
        }
        delay(80)
        repeat(n) { i ->
            exec.execute { flow.value = Triple("msg-$i", "hello $i", nonce++) }
        }
        delay(600); job.cancel(); return seen.size
    }

    suspend fun runSharedFlow(n: Int): Int {
        val seen = mutableListOf<String>()
        val flow = MutableSharedFlow<TextEvent>(extraBufferCapacity = 64)
        val job = CoroutineScope(looper).launch {
            flow.collect { work(); seen.add(it.msgId) }
        }
        delay(80)                          // subscribe before emitting
        repeat(n) { i ->
            exec.execute {
                if (!flow.tryEmit(TextEvent("msg-$i", "hello $i")))
                    println("   buffer full!")
            }
        }
        delay(600); job.cancel(); return seen.size
    }

    println("Relay flushes a pending queue: N frames arrive back to back.")
    println()
    println("%-9s %-26s %-26s".format("queued", "StateFlow (before)", "SharedFlow (after)"))
    var bad = 0
    for (n in listOf(1, 2, 3, 5, 7, 10, 20)) {
        val a = runStateFlow(n)
        val b = runSharedFlow(n)
        if (b != n) bad++
        println("%-9d %-26s %-26s".format(
            n, "$a delivered (lost ${n - a})", "$b delivered (lost ${n - b})"))
    }
    exec.shutdown()
    println()
    println(if (bad == 0) "SharedFlow lost nothing at any size."
            else "REGRESSION: SharedFlow lost events in $bad case(s)")
}

/** Stand-in for the collector's real work: notification, history write
 *  (file I/O), and the delivery ack. Non-trivial on purpose. */
private fun work() { Thread.sleep(2) }
