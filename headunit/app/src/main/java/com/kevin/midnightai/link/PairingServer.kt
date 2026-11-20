package com.kevin.midnightai.link

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Moves pairing messages between one phone and a [PairingSession] (spec section 3.2): a TCP
 * listener on an ephemeral port on the hotspot LAN, one connection at a time, each message a
 * 4-byte big-endian length then that many bytes of UTF-8 JSON (at most [MAX_FRAME]). Plain JVM
 * sockets, so the tests drive it over loopback with a fake phone.
 *
 * The listener lives only while the pairing screen is open ([close] from the screen's dispose)
 * and dies with the session. Nothing it reads or writes is logged.
 */
class PairingServer(
    private val session: PairingSession,
    bindAddress: InetAddress? = null,
) {
    private val server = ServerSocket(0, 1, bindAddress).apply { soTimeout = ACCEPT_TIMEOUT_MS }
    private val lock = Any()
    @Volatile private var client: Socket? = null
    @Volatile private var closed = false
    private val _stage = MutableStateFlow(session.stage)

    val port: Int get() = server.localPort
    val stage: StateFlow<PairingSession.Stage> = _stage.asStateFlow()

    /** Runs until the session ends or [close]; call on a background thread. */
    fun run() {
        while (!closed && !finished()) {
            val socket = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                synchronized(lock) { session.tick() }
                publish()
                continue
            } catch (_: IOException) {
                break
            }
            client = socket
            runCatching { serve(socket) }
            runCatching { socket.close() }
            client = null
        }
        close()
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = READ_TIMEOUT_MS
        val input = DataInputStream(socket.getInputStream())
        while (!closed && !finished()) {
            val length = try {
                input.readInt()
            } catch (_: SocketTimeoutException) {
                send(synchronized(lock) { listOfNotNull(session.tick()) })
                publish()
                continue
            }
            if (length !in 1..MAX_FRAME) {
                send(synchronized(lock) { session.onMessage(JSONObject().put("t", "frame_too_big")) })
                publish()
                return
            }
            val bytes = ByteArray(length).also { input.readFully(it) }
            val msg = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrElse { JSONObject().put("t", "unreadable") }
            bytes.fill(0)
            send(synchronized(lock) { session.onMessage(msg) })
            publish()
        }
    }

    /** The screen's buttons. Each may send a reply (the "done" or an abort) to the phone. */
    fun confirm() = act { session.confirm() }
    fun mismatch() = act { session.mismatch() }
    fun cancel() = act { session.cancel() }

    private fun act(block: () -> List<JSONObject>) {
        send(synchronized(lock) { block() })
        publish()
    }

    private fun send(messages: List<JSONObject>) {
        val socket = client ?: return
        runCatching {
            val out = DataOutputStream(socket.getOutputStream())
            synchronized(socket) {
                for (m in messages) {
                    val bytes = m.toString().toByteArray(Charsets.UTF_8)
                    out.writeInt(bytes.size)
                    out.write(bytes)
                }
                out.flush()
            }
        }
    }

    private fun publish() {
        _stage.value = synchronized(lock) { session.stage }
    }

    private fun finished(): Boolean = synchronized(lock) {
        session.stage is PairingSession.Stage.Paired || session.stage is PairingSession.Stage.Failed
    }

    fun close() {
        closed = true
        runCatching { client?.close() }
        runCatching { server.close() }
        publish()
    }

    companion object {
        const val MAX_FRAME = 8 * 1024
        const val ACCEPT_TIMEOUT_MS = 1_000
        const val READ_TIMEOUT_MS = 1_000

        fun writeFrame(out: DataOutputStream, msg: JSONObject) {
            val bytes = msg.toString().toByteArray(Charsets.UTF_8)
            out.writeInt(bytes.size)
            out.write(bytes)
            out.flush()
        }

        fun readFrame(input: DataInputStream): JSONObject {
            val n = input.readInt()
            require(n in 1..MAX_FRAME) { "frame size" }
            val bytes = ByteArray(n).also { input.readFully(it) }
            return JSONObject(String(bytes, Charsets.UTF_8))
        }
    }
}
