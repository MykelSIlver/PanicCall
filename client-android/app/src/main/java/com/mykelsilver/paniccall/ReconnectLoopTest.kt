import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Models the token-change reconnect loop in CallEngine.
 *
 * Real sequence: configure() cancels the live socket and immediately
 * opens a new one. OkHttp still delivers onFailure for the cancelled
 * socket a moment later, on a background thread, posted to the main
 * looper. Without a guard that callback looks like a genuine disconnect,
 * schedules a reconnect, and the app ends up holding TWO sockets on one
 * token -- which the relay resolves by evicting one with CLOSE_REPLACED
 * (4003), which this client answers by reconnecting, forever.
 *
 * WITH_GUARD reproduces the fix: callbacks from a socket that is no
 * longer the engine's current one are ignored.
 */
class FakeSocket(val id: Int) { @Volatile var cancelled = false }

class Engine(val withGuard: Boolean) {
    private val main = Executors.newSingleThreadScheduledExecutor()
    private val io = Executors.newCachedThreadPool()
    @Volatile private var ws: FakeSocket? = null
    private var nextId = 1
    var socketsOpened = 0; private set
    var reconnectsScheduled = 0; private set

    /** Stand-in for the relay: a second socket on one token evicts the first. */
    private val relay = object {
        @Volatile var live: FakeSocket? = null
        fun connect(s: FakeSocket, onEvicted: (FakeSocket) -> Unit) {
            val previous = live
            live = s
            if (previous != null && !previous.cancelled)
                io.execute { Thread.sleep(30); onEvicted(previous) }   // 4003
        }
    }

    private fun tryConnect() {
        val s = FakeSocket(nextId++)
        ws = s
        socketsOpened++
        relay.connect(s) { evicted -> onClosed(evicted, 4003) }
    }

    /** configure(): what happens when the token is edited in settings. */
    fun configure() {
        val old = ws
        if (withGuard) ws = null            // the fix: drop it before cancelling
        old?.cancel()
        tryConnect()
    }

    private fun FakeSocket.cancel() {
        cancelled = true
        io.execute { Thread.sleep(10); onFailure(this) }   // OkHttp still calls back
    }

    private fun onFailure(s: FakeSocket) = main.execute {
        // ignore a socket we discarded ourselves
        if (!withGuard || s === ws) handleDisconnect()
    }

    private fun onClosed(s: FakeSocket, code: Int) = main.execute {
        // 4003: "we fight back" -- but not on behalf of a discarded socket
        if (!withGuard || s === ws) handleDisconnect()
    }

    private fun handleDisconnect() {
        reconnectsScheduled++
        main.schedule({ tryConnect() }, 50, TimeUnit.MILLISECONDS)
    }

    fun start() { tryConnect() }
    fun stop() { main.shutdownNow(); io.shutdownNow() }
}

fun main() {
    for (guard in listOf(false, true)) {
        val e = Engine(withGuard = guard)
        e.start()
        Thread.sleep(100)
        e.configure()                       // user edits the token and saves
        Thread.sleep(2000)                  // watch for two seconds
        val label = if (guard) "WITH guard (fixed)   " else "WITHOUT guard (v0.2.11)"
        println("$label sockets opened: %3d   reconnects: %3d"
            .format(e.socketsOpened, e.reconnectsScheduled))
        e.stop()
    }
    println()
    println("Expected: without the guard the count keeps climbing (the flap);")
    println("with it, settling at 2 sockets and no further reconnects.")
}
