package com.kevin.midnightai.vehicle

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * End-to-end OBD pipeline test with the radio replaced by a simulated dongle.
 *
 * Unlike [ObdResponseParserTest] (canned strings straight into the parser),
 * this stands up a fake ELM327 speaking a 1998 Jeep Cherokee XJ profile
 * (ISO 9141-2) on a loopback TCP socket and drives the *real* [Elm327Io] framing
 * loop and the *real* [ObdResponseParser] across the actual command sequence the
 * app runs - the adapter handshake ([ObdBluetoothManager.finishConnect]) plus
 * every live read [TelemetryRecorder] performs. If the byte framing, prompt
 * detection, echo handling, SEARCHING-noise tolerance, or any decode regresses,
 * this fails without a car. It is the JVM twin of the Python emulator in
 * tools/elm327_xj_sim/.
 */
class ObdPipelineSimTest {

    private lateinit var server: FakeElm327
    private lateinit var socket: Socket
    private lateinit var io: Elm327Io

    @Before fun setUp() {
        server = FakeElm327(XJ_PROFILE).start()
        socket = Socket("127.0.0.1", server.port)
        socket.tcpNoDelay = true
        io = Elm327Io(socket.getInputStream(), socket.getOutputStream())
    }

    @After fun tearDown() {
        runCatching { socket.close() }
        server.stop()
    }

    // --- Handshake (mirrors ObdBluetoothManager.finishConnect) ----------------

    @Test fun handshake_captures_identity_and_protocol() {
        io.exchange("ATZ", 5000)
        io.exchange("ATE0", 3000)
        io.exchange("ATL0", 3000)
        io.exchange("ATSP0", 3000)

        val idString = io.exchange("ATI", 3000).trim()
        io.exchange("0100", 3000)
        val protocolCode = io.exchange("ATDPN", 3000).trim()

        assertTrue("expected ELM identity, got '$idString'", idString.contains("ELM327"))
        // ATSP0 -> auto, ISO 9141-2 == protocol 3, reported as "A3".
        assertEquals("A3", protocolCode)
    }

    // --- Live reads (the 9 PIDs TelemetryRecorder samples + ATRV) -------------

    @Test fun live_reads_decode_across_the_wire() {
        handshake()

        assertEquals(750, ObdResponseParser.rpm(io.exchange("010C", 3000)))
        assertEquals(83, ObdResponseParser.coolantTempC(io.exchange("0105", 3000)))
        assertEquals(20.0, ObdResponseParser.engineLoadPct(io.exchange("0104", 3000))!!, 0.5)
        assertEquals(5.0, ObdResponseParser.mafGramsPerSec(io.exchange("0110", 3000))!!, 0.001)
        assertEquals(0, ObdResponseParser.speedKmh(io.exchange("010D", 3000)))
        assertEquals(50.196, ObdResponseParser.fuelLevelPct(io.exchange("012F", 3000))!!, 0.01)
        assertEquals(0.0, ObdResponseParser.shortFuelTrimPct(io.exchange("0106", 3000))!!, 0.001)
        assertEquals(3.906, ObdResponseParser.longFuelTrimPct(io.exchange("0107", 3000))!!, 0.01)
        assertEquals(20, ObdResponseParser.intakeAirTempC(io.exchange("010F", 3000)))
        assertEquals(13.8, ObdResponseParser.batteryVoltage(io.exchange("ATRV", 3000))!!, 0.001)
    }

    // --- Readiness, DTCs, freeze frame, VIN -----------------------------------

    @Test fun readiness_decodes() {
        handshake()
        val r = ObdResponseParser.readiness(io.exchange("0101", 3000))!!
        assertTrue(!r.milOn)
        assertEquals(0, r.dtcCount)
    }

    @Test fun stored_dtc_decodes() {
        handshake()
        assertEquals(listOf("P0133"), ObdResponseParser.dtcCodes(io.exchange("03", 3000)))
    }

    @Test fun freeze_frame_reads_latched_snapshot() {
        handshake()
        // ObdBluetoothManager.getFreezeFrame() sends "02<pid>00" per PID.
        val rpmBytes = ObdResponseParser.freezeFrameBytes(io.exchange("020C00", 3000), "0C", 2)
        assertEquals(listOf(0x0B, 0xB8), rpmBytes)
    }

    @Test fun vin_unsupported_on_iso9141_returns_null() {
        // Real 1998 XJ (ISO 9141-2) does not answer mode-09 PID 02; the app must
        // degrade to null, not crash or invent a VIN.
        handshake()
        assertNull(ObdResponseParser.vin(io.exchange("0902", 5000)))
    }

    // --- Framing robustness ---------------------------------------------------

    @Test fun searching_noise_on_first_pid_is_tolerated() {
        // ISO 9141-2 emits "SEARCHING..." before the first live answer; the
        // framing loop must not let it leak into the next command's response.
        io.exchange("ATZ", 5000)
        io.exchange("ATE0", 3000)
        val first = io.exchange("0100", 3000)
        assertTrue("first PID should carry SEARCHING noise", first.contains("SEARCHING"))
        // Immediately after, a clean read must still decode.
        assertEquals(750, ObdResponseParser.rpm(io.exchange("010C", 3000)))
    }

    @Test fun stale_bytes_from_prior_timeout_are_drained() {
        handshake()
        // Ask the fake to dribble a late, unsolicited line, then issue a read.
        // drainInput must discard the leftovers so parsing sees only 41 05 ...
        server.injectUnsolicited("STOPPED\r")
        Thread.sleep(50)
        assertEquals(83, ObdResponseParser.coolantTempC(io.exchange("0105", 3000)))
    }

    // --- helpers --------------------------------------------------------------

    private fun handshake() {
        io.exchange("ATZ", 5000)
        io.exchange("ATE0", 3000)
        io.exchange("ATL0", 3000)
        io.exchange("ATSP0", 3000)
        io.exchange("ATI", 3000)
        io.exchange("0100", 3000)
        io.exchange("ATDPN", 3000)
    }

    companion object {
        // 1998 Jeep Cherokee XJ, 4.0L, ISO 9141-2. Values chosen for a warm idle.
        // Keys are the exact command strings the app sends; values are the data
        // line the ECU returns (the fake wraps them in CR + ">" prompt framing).
        private val XJ_PROFILE: Map<String, String> = mapOf(
            "ATI" to "ELM327 v1.5",
            "ATE0" to "OK",
            "ATL0" to "OK",
            "ATSP0" to "OK",
            "ATDPN" to "A3",              // auto-selected ISO 9141-2
            "ATRV" to "13.8V",            // engine running (alternator)
            "0100" to "41 00 BE 3E B8 11",// supported PIDs 01-20
            "0101" to "41 01 00 07 E5 00",// readiness: MIL off, all monitors complete
            "010C" to "41 0C 0B B8",      // 750 rpm
            "0105" to "41 05 7B",         // 83 C coolant
            "0104" to "41 04 33",         // ~20% load
            "0110" to "41 10 01 F4",      // 5.00 g/s MAF
            "010D" to "41 0D 00",         // 0 km/h
            "012F" to "41 2F 80",         // ~50% fuel
            "0106" to "41 06 80",         // 0% STFT
            "0107" to "41 07 85",         // +3.9% LTFT
            "010F" to "41 0F 3C",         // 20 C IAT
            "03" to "43 01 33 00 00",     // one stored code: P0133
            "020C00" to "42 0C 00 0B B8", // freeze-frame rpm latched at code set
            // 0902 (VIN) deliberately absent -> fake answers "NO DATA".
        )
    }
}

/**
 * A minimal ELM327 over a loopback TCP socket: reads CR-terminated commands,
 * honors ATE echo, and replies with a profile line wrapped in the adapter's
 * "\r\r>" framing. Unknown commands answer "NO DATA". Emits a one-time
 * "SEARCHING..." prefix on the first mode-01 request, like a real ISO 9141-2
 * adapter negotiating the bus.
 */
private class FakeElm327(private val profile: Map<String, String>) {
    private val server = ServerSocket(0)
    val port: Int get() = server.localPort

    @Volatile private var running = true
    @Volatile private var out: OutputStream? = null
    private var echo = true
    private var searched = false

    fun start(): FakeElm327 {
        thread(isDaemon = true, name = "FakeElm327") {
            try {
                val conn = server.accept()
                conn.tcpNoDelay = true
                val input = conn.getInputStream()
                out = conn.getOutputStream()
                serve(input, out!!)
            } catch (_: Exception) {
                // socket closed during teardown
            }
        }
        return this
    }

    /** Push a line the client did not ask for, to exercise drainInput. */
    fun injectUnsolicited(text: String) {
        out?.let {
            synchronized(it) {
                it.write(text.toByteArray())
                it.flush()
            }
        }
    }

    fun stop() {
        running = false
        runCatching { server.close() }
    }

    private fun serve(input: InputStream, output: OutputStream) {
        val line = StringBuilder()
        while (running) {
            val b = input.read()
            if (b == -1) break
            val c = b.toChar()
            if (c == '\r' || c == '\n') {
                if (line.isNotBlankCmd()) handle(line.toString().trim(), output)
                line.setLength(0)
            } else {
                line.append(c)
            }
        }
    }

    private fun StringBuilder.isNotBlankCmd() = this.toString().trim().isNotEmpty()

    private fun handle(cmd: String, output: OutputStream) {
        synchronized(output) {
            if (echo) output.write("$cmd\r".toByteArray())

            when {
                cmd.equals("ATZ", true) -> {
                    echo = true          // reset re-enables echo
                    searched = false
                    output.write("ELM327 v1.5\r\r>".toByteArray())
                    output.flush()
                    return
                }
                cmd.equals("ATE0", true) -> echo = false
                cmd.equals("ATE1", true) -> echo = true
            }

            val body = profile[cmd.uppercase()] ?: "NO DATA"
            val prefix = if (!searched && cmd.uppercase().startsWith("01")) {
                searched = true
                "SEARCHING...\r"
            } else ""
            output.write("$prefix$body\r\r>".toByteArray())
            output.flush()
        }
    }
}
