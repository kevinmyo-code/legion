package com.kevin.midnightai.link

import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Moves pairing messages between phones and one [PairingSession] (spec 3.2, revision 2).
 *
 * - Every session call and every socket write runs on ONE worker thread ([worker]); nothing here
 *   touches the network from the caller's thread (review H3: the screen's buttons used to write
 *   to the socket from the UI thread, where Android forbids it and the `done` silently vanished).
 * - Up to [MAX_PREAUTH] connections may be waiting to authenticate, one per source address, at most
 *   one new connection per address per [RATE_LIMIT_MS]; when full a silent one is evicted before
 *   one mid-hello. Each must send a valid hello within [PREAUTH_TIMEOUT_MS]. Anything else from
 *   them closes that connection only (review M1).
 * - Frames: 4-byte big-endian length (checked before allocating) + UTF-8 JSON. A frame split
 *   across reads is reassembled against an overall deadline (review L6).
 *
 * Plain JVM sockets; the tests drive it over loopback with a fake phone.
 */
class PairingServer(
    private val session: PairingSession,
    bindAddress: InetAddress? = null,
) {
    private val server = ServerSocket(0, MAX_PREAUTH, bindAddress).apply { soTimeout = ACCEPT_POLL_MS }
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "pairing-worker").apply { isDaemon = true } }
    /** A connection not yet authenticated, and whether it has sent any byte yet. */
    private class Slot(val socket: Socket) {
        @Volatile var started = false
    }

    /** Connections not yet authenticated, oldest first, at most one per source address (review M1). */
    private val preAuth = LinkedHashMap<InetAddress, Slot>()

    /** When each address last got a connection in (monotonic ms), for the per-address rate limit. */
    private val lastAcceptMs = HashMap<InetAddress, Long>()
    @Volatile private var claimed: Socket? = null
    @Volatile private var closed = false
    private val _stage = MutableStateFlow(session.stage)

    val port: Int get() = server.localPort
    val stage: StateFlow<PairingSession.Stage> = _stage.asStateFlow()

    /** Tests only: called with the writing thread before each socket write. */
    internal var onWrite: ((Thread) -> Unit)? = null

    private val _codeFeedback = MutableStateFlow<PairingSession.CodeResult?>(null)
    val codeFeedback: StateFlow<PairingSession.CodeResult?> = _codeFeedback.asStateFlow()

    /** Starts accepting (its own thread) and the 1 s timeout tick (the worker). */
    fun start() {
        // Review N1: while this session is open, old-key traffic never drops a PENDING pairing.
        CarLink.pairingSessionStarted(this)
        Thread({ acceptLoop() }, "pairing-accept").apply { isDaemon = true }.start()
        worker.scheduleWithFixedDelay({ onWorker { listOfNotNull(session.tick()) } }, 1, 1, TimeUnit.SECONDS)
    }

    private fun acceptLoop() {
        while (!closed) {
            val socket = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: IOException) {
                break
            }
            if (claimed != null) {
                runCatching { socket.close() }
                continue
            }
            val ip = socket.inetAddress
            val slot = Slot(socket)
            val admitted = synchronized(preAuth) {
                val now = System.nanoTime() / 1_000_000L
                // Rate limit (recheck c53f1fc3): one new connection per address per RATE_LIMIT_MS.
                // A burst is refused; it never displaces anything.
                val last = ip?.let { lastAcceptMs[it] }
                if (last != null && now - last < RATE_LIMIT_MS) {
                    false
                } else {
                    ip?.let { lastAcceptMs[it] = now }
                    // A newer connection from the same address replaces the older one.
                    ip?.let { preAuth.remove(it)?.let { old -> runCatching { old.socket.close() } } }
                    // When full, evict the oldest connection that has sent NOTHING; only if every
                    // slot is mid-message, the oldest of all. Idle sockets never hold the phone out.
                    while (preAuth.size >= MAX_PREAUTH) {
                        val victim = preAuth.entries.firstOrNull { !it.value.started }?.key ?: preAuth.keys.first()
                        preAuth.remove(victim)?.let { old -> runCatching { old.socket.close() } }
                    }
                    ip?.let { preAuth[it] = slot }
                    true
                }
            }
            if (!admitted) {
                runCatching { socket.close() }
                continue
            }
            Thread({ serve(slot) }, "pairing-conn").apply { isDaemon = true }.start()
        }
    }

    private fun serve(slot: Slot) {
        val socket = slot.socket
        try {
            socket.soTimeout = READ_POLL_MS
            val input = socket.getInputStream()
            val hello = readFrame(input, System.nanoTime() + PREAUTH_TIMEOUT_MS * 1_000_000L) { slot.started = true } ?: return
            val verdict = onWorkerWait { session.preAuth(hello) }
            when (verdict) {
                null, PairingSession.PreAuth.Close -> return
                is PairingSession.PreAuth.Fail -> {
                    onWorkerWait { sendNow(socket, listOf(verdict.reply)) }
                    return
                }
                is PairingSession.PreAuth.Claimed -> {
                    claimed = socket
                    // The session is taken: close every other unauthenticated connection now.
                    synchronized(preAuth) {
                        preAuth.values.filter { it.socket !== socket }.forEach { runCatching { it.socket.close() } }
                        preAuth.clear()
                    }
                    onWorker { listOf(verdict.reply) }
                }
            }
            while (!closed) {
                val msg = readFrame(input, Long.MAX_VALUE) ?: break
                onWorker { session.onMessage(msg) }
                if (isOver()) break
            }
        } catch (_: IOException) {
            // A dropped connection is handled below.
        } finally {
            synchronized(preAuth) { socket.inetAddress?.let { if (preAuth[it] === slot) preAuth.remove(it) } }
            if (socket === claimed) {
                onWorkerWait { session.onDisconnect() }
                claimed = null
            }
            runCatching { socket.close() }
            publish()
        }
    }

    /**
     * Reads one frame, or null on EOF, an oversized length or the deadline. Survives read timeouts
     * mid-frame (the bytes already read are kept).
     */
    private fun readFrame(input: InputStream, deadlineNanos: Long, onBytes: (() -> Unit)? = null): JSONObject? {
        val header = readExactly(input, 4, deadlineNanos, onBytes) ?: return null
        val n = ((header[0].toInt() and 0xff) shl 24) or ((header[1].toInt() and 0xff) shl 16) or
            ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff)
        if (n !in 1..MAX_FRAME) return null
        val body = readExactly(input, n, deadlineNanos) ?: return null
        val msg = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull()
        body.fill(0)
        return msg ?: JSONObject().put("t", "unreadable")
    }

    private fun readExactly(input: InputStream, n: Int, deadlineNanos: Long, onBytes: (() -> Unit)? = null): ByteArray? {
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            if (closed || System.nanoTime() > deadlineNanos) return null
            val r = try {
                input.read(out, got, n - got)
            } catch (_: SocketTimeoutException) {
                continue
            }
            if (r < 0) return null
            if (r > 0) onBytes?.invoke()
            got += r
        }
        return out
    }

    /** The driver typed six digits on the pad. */
    fun enterCode(digits: String) {
        worker.execute {
            val result = session.enterCode(digits)
            _codeFeedback.value = result
            val send = when (result) {
                is PairingSession.CodeResult.Correct -> listOf(result.send)
                is PairingSession.CodeResult.Dead -> listOfNotNull(result.send)
                else -> emptyList()
            }
            write(send)
            publish()
        }
    }

    /** Cancel and close; safe from any thread, never touches the network on the caller's thread. */
    fun close() {
        if (closed) return
        worker.execute {
            write(session.cancel())
            closed = true
            CarLink.pairingSessionEnded(this)
            runCatching { claimed?.close() }
            runCatching { server.close() }
            publish()
            worker.shutdown()
        }
    }

    private fun onWorker(block: () -> List<JSONObject>) {
        if (worker.isShutdown) return
        runCatching {
            worker.execute {
                write(block())
                publish()
            }
        }
    }

    private fun <T> onWorkerWait(block: () -> T): T? =
        if (worker.isShutdown) null else runCatching { worker.submit<T> { block().also { publish() } }.get() }.getOrNull()

    private fun write(messages: List<JSONObject>) {
        val socket = claimed ?: return
        sendNow(socket, messages)
    }

    private fun sendNow(socket: Socket, messages: List<JSONObject>) {
        if (messages.isEmpty()) return
        onWrite?.invoke(Thread.currentThread())
        runCatching {
            val out = DataOutputStream(socket.getOutputStream())
            for (m in messages) writeFrame(out, m)
        }
    }

    private fun isOver(): Boolean = _stage.value.let { it is PairingSession.Stage.Paired || it is PairingSession.Stage.Failed }

    private fun publish() {
        _stage.value = session.stage
        // Review P1: the session is over at Paired or Failed, whether or not the screen ever closes;
        // from here the 60 s quiet-period rule (spec 3.7) applies to old-key traffic.
        if (isOver()) CarLink.pairingSessionEnded(this)
    }

    companion object {
        const val MAX_FRAME = 8 * 1024
        /** Pre-auth slots (recheck c53f1fc3: 8, so an evicting attacker needs 8 addresses inside the phone's window). */
        const val MAX_PREAUTH = 8
        /** One new connection per source address per this long; a burst is refused (recheck c53f1fc3). */
        const val RATE_LIMIT_MS = 250L
        /** A real phone sends its hello at once; 3 s is plenty and keeps a squatter's slot short. */
        const val PREAUTH_TIMEOUT_MS = 3_000L
        private const val ACCEPT_POLL_MS = 500
        private const val READ_POLL_MS = 200

        fun writeFrame(out: DataOutputStream, msg: JSONObject) {
            val bytes = msg.toString().toByteArray(Charsets.UTF_8)
            out.writeInt(bytes.size)
            out.write(bytes)
            out.flush()
        }

        fun readFrame(input: java.io.DataInputStream): JSONObject {
            val n = input.readInt()
            require(n in 1..MAX_FRAME) { "frame size" }
            val bytes = ByteArray(n).also { input.readFully(it) }
            return JSONObject(String(bytes, Charsets.UTF_8))
        }
    }
}
