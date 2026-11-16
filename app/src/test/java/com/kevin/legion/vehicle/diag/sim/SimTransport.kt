package com.kevin.legion.vehicle.diag.sim

import com.kevin.legion.vehicle.Elm327Io
import com.kevin.legion.vehicle.ObdTransport
import com.kevin.legion.vehicle.diag.DiagLink
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * [SimElm327] behind the real [ObdTransport] byte streams: a command line ends at `\r`, the reply
 * comes back followed by the `>` prompt, exactly as a Bluetooth dongle's serial port delivers it.
 * Driven through the production [Elm327Io], so its drain, framing and prompt handling are exercised.
 */
class SimTransport(val elm: SimElm327) : ObdTransport {
    private val pending = ConcurrentLinkedQueue<Int>()
    private val line = StringBuilder()

    override val label: String = "sim"

    override val inputStream: InputStream = object : InputStream() {
        override fun read(): Int = pending.poll() ?: -1
        override fun available(): Int = pending.size
    }

    override val outputStream: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            val c = (b and 0xFF).toChar()
            if (c == '\r') {
                val reply = elm.handle(line.toString())
                line.clear()
                (reply + "\r\r>").forEach { pending += it.code }
            } else {
                line.append(c)
            }
        }
    }

    override fun close() = Unit

    private val io = Elm327Io(inputStream, outputStream)

    /** The scanner's link: every command through [Elm327Io], as on the phone. */
    val link = DiagLink { command, timeoutMs -> io.exchange(command, timeoutMs) }

    /** What `ObdBluetoothManager.finishConnect` sends before anything else. */
    fun handshake() {
        for (cmd in listOf("ATZ", "ATE0", "ATL0", "ATSP0")) io.exchange(cmd, 1_000)
    }
}
