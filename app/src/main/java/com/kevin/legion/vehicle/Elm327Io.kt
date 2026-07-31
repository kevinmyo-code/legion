package com.kevin.legion.vehicle

import java.io.InputStream
import java.io.OutputStream

/**
 * The byte-level ELM327 conversation: drain stale bytes, write "cmd\r", read
 * back until the ">" prompt (or a timeout). Split out of [ObdBluetoothManager]
 * (which owns the socket and the coroutine/mutex plumbing) for the same reason
 * [ObdResponseParser] is - so the fiddly, off-by-one-prone framing logic can be
 * driven end-to-end against a fake ELM327 over a loopback socket in a plain JVM
 * unit test, no Android and no Bluetooth radio required. Operates on generic
 * streams so it doesn't care whether they came from RFCOMM or TCP.
 */
class Elm327Io(
    private val input: InputStream,
    private val output: OutputStream,
) {
    /**
     * Sends [cmd] and returns the raw adapter response up to (not including) the
     * ">" prompt. Throws [java.io.IOException] on a broken stream so the caller
     * can tear the connection down; a plain timeout (no prompt within
     * [timeoutMs]) returns whatever arrived so far rather than throwing.
     */
    fun exchange(cmd: String, timeoutMs: Long): String {
        // Drop any stale bytes left over from a previous command that timed out
        // before its ">" prompt arrived, so they don't get prepended to this
        // response and break parsing.
        drainInput()
        output.write("$cmd\r".toByteArray())
        output.flush()
        return readUntilPrompt(timeoutMs)
    }

    private fun drainInput() {
        val available = input.available()
        if (available > 0) {
            input.read(ByteArray(available))
        }
    }

    /** Reads bytes until the ELM327 ">" prompt or [timeoutMs] elapses. */
    private fun readUntilPrompt(timeoutMs: Long): String {
        val sb = StringBuilder()
        val buffer = ByteArray(1)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val n = input.read(buffer)
                if (n <= 0) break
                val c = buffer[0].toInt().toChar()
                if (c == '>') break
                sb.append(c)
            } else {
                Thread.sleep(20)
            }
        }
        return sb.toString()
    }
}
