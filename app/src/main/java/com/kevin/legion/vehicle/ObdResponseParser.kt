package com.kevin.legion.vehicle

/**
 * Pure parsing of ELM327 / OBD-II text responses into values. Kept separate
 * from [ObdBluetoothManager] (which owns the Bluetooth socket) so this logic -
 * the part most prone to off-by-one and decoding bugs - can be unit-tested with
 * canned responses, no hardware required.
 *
 * All functions tolerate the usual noise in real adapter output: CR/LF line
 * endings, extra whitespace, a leading "SEARCHING..." line, and a command echo
 * before the data (in case ATE0 didn't take).
 */
/** One emissions monitor's state from a mode-01 PID 01 readiness read. */
data class MonitorStatus(val name: String, val available: Boolean, val complete: Boolean)

/** Full readiness picture: MIL lamp, stored-code count, and per-monitor status. */
data class ReadinessStatus(val milOn: Boolean, val dtcCount: Int, val monitors: List<MonitorStatus>)

object ObdResponseParser {

    /**
     * True when [response] is the adapter saying "nothing usable came back from
     * the vehicle" rather than real data. On real ISO 9141-2 hardware (the 1998
     * XJ) a dead K-line session answers with a TEXT error string, not a blank
     * response - a caller that only checked [String.isBlank] (drive-notes-2
     * ticket 02) never saw the failure at all.
     *
     * The vocabulary here is the full documented ELM327 error set, not just
     * "NO DATA" (broadened 2026-07-19, drive-notes-2 ticket 02 second pass: the
     * "NO DATA"-only version STILL left the gauges dead for a whole drive, so a
     * dead K-line is evidently answering with one of the BUS/DATA/init errors
     * below rather than "NO DATA"). Substring match, same as the original, since
     * these arrive as standalone response lines. Deliberately NOT matched: a bare
     * "?" (ELM327's "unknown command" reply - a bug in OUR command, not a bus
     * failure) and "SEARCHING..." alone (a handshake in progress, not a failure).
     */
    fun isFailureResponse(response: String): Boolean {
        if (response.isBlank()) return true
        val upper = response.uppercase()
        return FAILURE_MARKERS.any { it in upper }
    }

    private val FAILURE_MARKERS = listOf(
        "NO DATA",
        "UNABLE",        // "UNABLE TO CONNECT"
        "STOPPED",
        "CAN ERROR",
        "BUS ERROR",
        "BUS INIT",      // "BUS INIT: ...ERROR" or a hung init line
        "BUS BUSY",
        "DATA ERROR",
        "FB ERROR",      // feedback error on the K-line
        "RX ERROR",      // "<RX ERROR"
        "BUFFER FULL",
    )

    /** Coolant temp in Celsius from a mode-01 PID 05 response ("41 05 XX"). */
    fun coolantTempC(response: String): Int? {
        val bytes = dataBytes(response, "41 05") ?: return null
        if (bytes.isEmpty()) return null
        return bytes[0] - 40
    }

    /** Engine speed in RPM from a mode-01 PID 0C response ("41 0C XX XX"). */
    fun rpm(response: String): Int? {
        val bytes = dataBytes(response, "41 0C") ?: return null
        if (bytes.size < 2) return null
        return ((bytes[0] * 256) + bytes[1]) / 4
    }

    /** Calculated engine load % from a mode-01 PID 04 response ("41 04 XX"). */
    fun engineLoadPct(response: String): Double? {
        val bytes = dataBytes(response, "41 04") ?: return null
        if (bytes.isEmpty()) return null
        return bytes[0] * 100.0 / 255.0
    }

    /** Short-term fuel trim % (bank 1) from a mode-01 PID 06 response. Negative = pulling fuel. */
    fun shortFuelTrimPct(response: String): Double? {
        val bytes = dataBytes(response, "41 06") ?: return null
        if (bytes.isEmpty()) return null
        return (bytes[0] - 128) * 100.0 / 128.0
    }

    /** Long-term fuel trim % (bank 1) from a mode-01 PID 07 response. */
    fun longFuelTrimPct(response: String): Double? {
        val bytes = dataBytes(response, "41 07") ?: return null
        if (bytes.isEmpty()) return null
        return (bytes[0] - 128) * 100.0 / 128.0
    }

    /** Vehicle speed in km/h from a mode-01 PID 0D response ("41 0D XX"). */
    fun speedKmh(response: String): Int? {
        val bytes = dataBytes(response, "41 0D") ?: return null
        if (bytes.isEmpty()) return null
        return bytes[0]
    }

    /** Intake air temp in Celsius from a mode-01 PID 0F response ("41 0F XX"). */
    fun intakeAirTempC(response: String): Int? {
        val bytes = dataBytes(response, "41 0F") ?: return null
        if (bytes.isEmpty()) return null
        return bytes[0] - 40
    }

    /** Mass air flow in g/s from a mode-01 PID 10 response ("41 10 XX XX"). */
    fun mafGramsPerSec(response: String): Double? {
        val bytes = dataBytes(response, "41 10") ?: return null
        if (bytes.size < 2) return null
        return ((bytes[0] * 256) + bytes[1]) / 100.0
    }

    /** Fuel tank level % from a mode-01 PID 2F response ("41 2F XX"). */
    fun fuelLevelPct(response: String): Double? {
        val bytes = dataBytes(response, "41 2F") ?: return null
        if (bytes.isEmpty()) return null
        return bytes[0] * 100.0 / 255.0
    }

    /**
     * Emissions readiness from a mode-01 PID 01 response ("41 01 AA BB CC DD").
     * A: bit7 = MIL lamp, bits0-6 = stored DTC count. B: continuous monitors -
     * bits0-2 available (misfire / fuel system / components), bits4-6 the same
     * three incomplete. C/D: non-continuous available/incomplete bitmaps using
     * the spark-ignition mapping (bit4, A/C refrigerant, is obsolete - skipped).
     * State-inspection critical: incomplete monitors fail an emissions test.
     */
    fun readiness(response: String): ReadinessStatus? {
        val bytes = dataBytes(response, "41 01") ?: return null
        if (bytes.size < 4) return null
        val a = bytes[0]; val b = bytes[1]; val c = bytes[2]; val d = bytes[3]

        val monitors = mutableListOf<MonitorStatus>()
        val continuous = listOf("Misfire" to 0, "Fuel system" to 1, "Components" to 2)
        for ((name, bit) in continuous) {
            val available = (b shr bit) and 1 == 1
            if (available) {
                val incomplete = (b shr (bit + 4)) and 1 == 1
                monitors.add(MonitorStatus(name, available = true, complete = !incomplete))
            }
        }
        val nonContinuous = listOf(
            "Catalyst" to 0, "Heated catalyst" to 1, "Evap system" to 2,
            "Secondary air" to 3, "O2 sensor" to 5, "O2 heater" to 6, "EGR system" to 7,
        )
        for ((name, bit) in nonContinuous) {
            val available = (c shr bit) and 1 == 1
            if (available) {
                val incomplete = (d shr bit) and 1 == 1
                monitors.add(MonitorStatus(name, available = true, complete = !incomplete))
            }
        }
        return ReadinessStatus(milOn = (a shr 7) and 1 == 1, dtcCount = a and 0x7F, monitors = monitors)
    }

    /**
     * Data bytes from a mode-02 (freeze frame) response ("42 XX [00] ...").
     * Mode-02 replies may echo the requested frame number (a 00 byte) between
     * the PID echo and the data; some clones omit it. [expectedLen] is the
     * PID's data length - if one extra leading byte is present and it's 00,
     * it's the frame number and gets dropped. Returns exactly [expectedLen]
     * bytes or null.
     */
    fun freezeFrameBytes(response: String, pidByte: String, expectedLen: Int): List<Int>? {
        var bytes = dataBytes(response, "42 $pidByte") ?: return null
        if (bytes.size > expectedLen && bytes[0] == 0x00) bytes = bytes.drop(1)
        if (bytes.size < expectedLen) return null
        return bytes.take(expectedLen)
    }

    /**
     * Battery/adapter voltage from an ELM327 "ATRV" response, which comes back
     * like "12.5V" (sometimes with echo/whitespace/CR). Returns the volts, or
     * null if no number is present.
     */
    fun batteryVoltage(response: String): Double? {
        // Prefer a number immediately before a "V" ("12.5V"); fall back to the
        // first decimal number anywhere in the response.
        val volts = Regex("""(\d{1,2}(?:\.\d+)?)\s*V""", RegexOption.IGNORE_CASE).find(response)?.groupValues?.get(1)
            ?: Regex("""\d{1,2}\.\d+""").find(response)?.value
        return volts?.toDoubleOrNull()
    }

    /**
     * Stored DTCs (e.g. "P0301") from a mode-03 response ("43 ..."), empty if
     * none. On ISO 9141-2 / KWP a car with more than three codes answers with
     * several lines, each re-prefixed with the "43" mode byte
     * ("43 01 33\r43 04 20\r..."). We must strip that echo from *every* frame,
     * not just the first line, or the second "43" gets decoded as a bogus code.
     * Each line's leading "43" is dropped, then all data bytes are paired.
     */
    fun dtcCodes(response: String): List<String> {
        val upper = response.uppercase()
        if (response.isBlank() || "NO DATA" in upper || "UNABLE" in upper) {
            return emptyList()
        }

        val data = mutableListOf<String>()
        var sawFrame = false
        for (line in response.split('\r', '\n')) {
            val tokens = line.trim().split(Regex("\\s+"))
                .filter { it.length == 2 && it.toIntOrNull(16) != null }
            if (tokens.isEmpty()) continue
            if (tokens[0].equals("43", ignoreCase = true)) {
                sawFrame = true
                data.addAll(tokens.drop(1))
            } else if (sawFrame) {
                // A continuation line with no mode echo (some adapters wrap).
                data.addAll(tokens)
            }
        }
        if (!sawFrame) return emptyList()

        val codes = mutableListOf<String>()
        var i = 0
        while (i + 1 < data.size) {
            val b1 = data[i].toInt(16)
            val b2 = data[i + 1].toInt(16)
            if (b1 != 0 || b2 != 0) {
                val letter = "PCBU"[(b1 shr 6) and 0x03]
                codes.add(String.format("%s%02X%02X", letter, b1 and 0x3F, b2))
            }
            i += 2
        }
        return codes
    }

    /**
     * VIN from a mode-09 PID 02 response. This is a multi-frame ISO-TP reply, so
     * ELM327 output varies: it may carry line-number prefixes ("0:", "1:"), a
     * leading byte-count line, and the "49 02 01" mode/PID/count echo before the
     * 17 ASCII VIN bytes. We strip everything that isn't a hex byte, find the
     * "49 02" marker, skip the count byte, then read the remaining bytes as ASCII
     * and keep the 17 alphanumeric VIN characters. Returns null if no valid
     * 17-char VIN is present.
     */
    fun vin(response: String): String? {
        val cleaned = response.uppercase().replace("\r", " ").replace("\n", " ")
        if ("NO DATA" in cleaned || "UNABLE" in cleaned || "CAN ERROR" in cleaned) return null

        // Keep only 2-char hex tokens (drops "0:" line prefixes, count lines, echoes).
        val tokens = cleaned.split(Regex("[\\s:]+"))
            .filter { it.length == 2 && it.toIntOrNull(16) != null }

        val marker = (0 until tokens.size - 1).firstOrNull { tokens[it] == "49" && tokens[it + 1] == "02" }
            ?: return null
        var i = marker + 2
        if (i < tokens.size && tokens[i] == "01") i++ // skip the data-item-count byte

        val vin = tokens.drop(i)
            .mapNotNull { it.toIntOrNull(16)?.toChar() }
            .filter { it.isLetterOrDigit() }
            .joinToString("")
            .take(17)
        return vin.takeIf { it.length == 17 }
    }

    /**
     * Extracts the data bytes from a response, skipping past the expected
     * mode/PID echo prefix (e.g. "41 05"). Returns null if the prefix isn't
     * found (error response, no data, etc).
     */
    fun dataBytes(response: String, prefix: String): List<Int>? {
        val tokens = response.replace("\r", " ").replace("\n", " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        val prefixTokens = prefix.split(" ")

        val idx = (0..(tokens.size - prefixTokens.size)).firstOrNull { i ->
            prefixTokens.indices.all { j -> tokens[i + j].equals(prefixTokens[j], ignoreCase = true) }
        } ?: return null

        return tokens.drop(idx + prefixTokens.size).mapNotNull { it.toIntOrNull(16) }
    }

    /**
     * The set of supported Mode-01 PID numbers from a "supported PIDs" bitmask
     * reply ([base] = 0x00, 0x20, or 0x40 - one 32-PID window each). The four
     * data bytes are a big-endian 32-bit mask where the most-significant bit is
     * PID [base]+1 and the least-significant is PID [base]+32, so "41 00 BE 1F
     * A8 13" lists which of PIDs 0x01-0x20 the car answers. Returns null if the
     * echo/data bytes aren't present (error or no response). The bit for the
     * next window's marker PID (0x20 / 0x40 / 0x60) being set is how the caller
     * knows to query the next window.
     */
    fun supportedPids(response: String, base: Int): Set<Int>? {
        val bytes = dataBytes(response, "41 %02X".format(base)) ?: return null
        if (bytes.size < 4) return null
        val mask = ((bytes[0] and 0xFF).toLong() shl 24) or
            ((bytes[1] and 0xFF).toLong() shl 16) or
            ((bytes[2] and 0xFF).toLong() shl 8) or
            (bytes[3] and 0xFF).toLong()
        val supported = sortedSetOf<Int>()
        for (i in 0 until 32) {
            if ((mask shr (31 - i)) and 1L == 1L) supported.add(base + i + 1)
        }
        return supported
    }
}
