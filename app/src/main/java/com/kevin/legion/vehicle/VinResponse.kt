package com.kevin.legion.vehicle

/**
 * Parses an ELM327 answer to Mode 09 PID 02 (the VIN) into a VIN or an honest reason there is none
 * (OBD VIN attribution, 2026-11-15).
 *
 * **Why this replaced the old `ObdResponseParser.vin`.** That one kept every 2-char hex token after
 * the first `49 02`, read them as ASCII and kept letters and digits. Three reply shapes broke it:
 * a K-line car repeats `49 02 0N` on every line, so each repeated `49` decoded to an `I` inside the
 * VIN; a reply with spaces off (`ATS0`) has no 2-char tokens at all; and two ECUs answering at
 * once interleave two copies. It also accepted I, O and Q, which no VIN contains. A wrong VIN is
 * worse than none here, because it decides which car a whole drive is filed under.
 *
 * Shapes handled (all with or without spaces, with or without a `SEARCHING...` line):
 *  - CAN, headers off: a byte-count line (`014`) then `0:`, `1:`, `2:` indexed lines.
 *  - CAN, headers on: `7E8 10 14 49 02 01 ...` first frame, `7E8 21 ...` consecutive frames, or
 *    29-bit `18DAF110 ...`. Frames are grouped by header, so two ECUs do not interleave.
 *  - Legacy (J1850, ISO 9141-2, KWP): five lines `49 02 0N` plus four data bytes, the first
 *    padded with three zero bytes.
 *  - A flat line holding `49 02 [01]` and the VIN bytes, which some clones send.
 *
 * Accepts only 17 characters from the VIN alphabet (digits and A-Z without I, O, Q). The check
 * digit is NOT required: VINs outside North America do not use it. 17 copies of one character is
 * refused too (an unprogrammed ECU answers with filler, not a VIN). When the reply holds more than
 * one valid VIN that disagree, the result is [Result.Unreadable], never a pick between them.
 */
object VinResponse {

    sealed interface Result {
        /** A valid VIN, upper case. */
        data class Vin(val vin: String) : Result

        /** Nothing came back: a timeout, or the link dropped mid-read. */
        data object NoResponse : Result

        /** The adapter said NO DATA: the car did not answer Mode 09, or does not support it. */
        data object NoData : Result

        /** The adapter answered `?`: it did not understand the command (some clones). */
        data object Unsupported : Result

        /** Something came back that is not a usable VIN. [why] is for the log, in plain words. */
        data class Unreadable(val why: String) : Result
    }

    private val VIN_ALPHABET = Regex("^[A-HJ-NPR-Z0-9]{17}$")
    private val INDEXED_LINE = Regex("^([0-9A-F]):\\s*(.*)$")
    private val COUNT_LINE = Regex("^[0-9A-F]{3}$")
    private val HEX_ONLY = Regex("^[0-9A-F]+$")
    private const val VIN_LENGTH = 17
    private const val MODE_09_REPLY = 0x49
    private const val PID_VIN = 0x02
    private const val LEGACY_MESSAGES = 5
    private const val LEGACY_DATA_BYTES = 4
    private const val LEGACY_DATA_OFFSET = 3
    private const val HEX_RADIX = 16
    private const val CAN_11BIT_HEADER = 3
    private const val CAN_29BIT_HEADER = 8
    private const val NIBBLE = 4
    private const val LOW_NIBBLE = 0x0F
    private const val BYTE_BITS = 8
    private const val PCI_SINGLE = 0
    private const val PCI_FIRST = 1
    private const val PCI_CONSECUTIVE = 2
    private val PRINTABLE = 0x20..0x7E

    /** ELM327 error words that mean the bus or the adapter failed, not that the car answered. */
    private val ERROR_WORDS = listOf(
        "UNABLE", "STOPPED", "CAN ERROR", "BUS ERROR", "BUS BUSY", "DATA ERROR", "FB ERROR",
        "RX ERROR", "BUFFER FULL", "ERR",
    )

    /** True when [candidate] is 17 characters of the VIN alphabet and not one character repeated. */
    fun isValidVin(candidate: String): Boolean =
        VIN_ALPHABET.matches(candidate) && candidate.toSet().size > 1

    fun parse(raw: String): Result {
        val lines = raw.uppercase()
            .split('\r', '\n')
            .map { it.trim().trimStart('>').trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("SEARCHING") || (it.startsWith("BUS INIT") && "ERR" !in it) }
            .filterNot { it.replace(" ", "") == "0902" } // a command echo, if ATE0 did not take
        return failureOf(lines) ?: verdict(candidates(lines))
    }

    /** The adapter's own failure words, or null when the reply may hold a VIN. */
    private fun failureOf(lines: List<String>): Result? {
        val joined = lines.joinToString(" ")
        return when {
            lines.isEmpty() -> Result.NoResponse
            "NO DATA" in joined -> Result.NoData
            lines.all { it == "?" } -> Result.Unsupported
            else -> ERROR_WORDS.firstOrNull { it in joined }?.let { Result.Unreadable("adapter reported $it") }
        }
    }

    private fun candidates(lines: List<String>): List<String> = when {
        lines.any { INDEXED_LINE.matches(it) } -> fromIndexed(lines)
        lines.any { isHeaderedCan(it) } -> fromHeadered(lines)
        lines.count { legacyMessage(bytesOf(it)) != null } >= 2 -> fromLegacy(lines)
        else -> listOfNotNull(vinAfterMarker(lines.flatMap { bytesOf(it).orEmpty() }))
    }

    private fun verdict(candidates: List<String>): Result {
        val valid = candidates.filter { isValidVin(it) }.distinct()
        return when {
            candidates.isEmpty() -> Result.Unreadable("no VIN in the reply")
            valid.size == 1 -> Result.Vin(valid.single())
            valid.size > 1 -> Result.Unreadable("two control units gave different VINs")
            else -> Result.Unreadable("the reply was not a valid VIN")
        }
    }

    /** CAN with headers off: optional count line, then `N:` lines; a new `0:` starts a new ECU. */
    private fun fromIndexed(lines: List<String>): List<String> {
        val groups = mutableListOf<MutableMap<Int, List<Int>>>()
        var current = mutableMapOf<Int, List<Int>>()
        for (line in lines) {
            val frame = indexedFrame(line)
            val startsNew = COUNT_LINE.matches(line) || (frame != null && frame.first in current)
            if (startsNew && current.isNotEmpty()) {
                groups += current
                current = mutableMapOf()
            }
            if (frame != null) current[frame.first] = frame.second
        }
        if (current.isNotEmpty()) groups += current
        return groups.mapNotNull { g -> vinAfterMarker(g.toSortedMap().values.flatten()) }
    }

    private fun indexedFrame(line: String): Pair<Int, List<Int>>? {
        val m = INDEXED_LINE.matchEntire(line) ?: return null
        return bytesOf(m.groupValues[2])?.let { m.groupValues[1].toInt(HEX_RADIX) to it }
    }

    private fun isHeaderedCan(line: String): Boolean {
        val compact = line.replace(" ", "")
        val firstToken = line.split(' ').first()
        val elevenBit = (firstToken.length == CAN_11BIT_HEADER || compact.length % 2 == 1) && compact.startsWith("7E")
        return HEX_ONLY.matches(compact) && (elevenBit || compact.startsWith("18DA"))
    }

    /** CAN with headers on: reassemble each sender's ISO-TP frames separately. */
    private fun fromHeadered(lines: List<String>): List<String> =
        lines.mapNotNull { headeredFrame(it) }
            .groupBy({ it.first }, { it.second })
            .values
            .mapNotNull { vinAfterMarker(reassemble(it)) }

    /** One headered line as (sender, payload), or null. */
    private fun headeredFrame(line: String): Pair<String, List<Int>>? {
        val compact = line.replace(" ", "")
        val headerLength = if (compact.startsWith("18DA")) CAN_29BIT_HEADER else CAN_11BIT_HEADER
        if (!HEX_ONLY.matches(compact) || compact.length <= headerLength) return null
        return bytesOf(compact.substring(headerLength))?.let { compact.substring(0, headerLength) to it }
    }

    /** ISO 15765-2: a first frame (or single frame) then consecutive frames, cut to the stated length. */
    private fun reassemble(frames: List<List<Int>>): List<Int> {
        val data = mutableListOf<Int>()
        var expected = -1
        for (frame in frames.filter { it.isNotEmpty() }) {
            when (frame[0] shr NIBBLE) {
                PCI_SINGLE -> {
                    expected = frame[0] and LOW_NIBBLE
                    data.clear()
                    data += frame.drop(1)
                }
                PCI_FIRST -> if (frame.size >= 2) {
                    expected = ((frame[0] and LOW_NIBBLE) shl BYTE_BITS) or frame[1]
                    data.clear()
                    data += frame.drop(2)
                }
                PCI_CONSECUTIVE -> data += frame.drop(1)
                else -> Unit // flow control or noise
            }
        }
        return if (expected in 1..data.size) data.take(expected) else data
    }

    /** One legacy line: (message number 1-5, its 4 data bytes), or null. */
    private fun legacyMessage(bytes: List<Int>?): Pair<Int, List<Int>>? {
        val at = bytes?.let { markerAt(it) } ?: return null
        val number = bytes.getOrNull(at + 2)?.takeIf { it in 1..LEGACY_MESSAGES }
        val data = bytes.drop(at + LEGACY_DATA_OFFSET).take(LEGACY_DATA_BYTES)
        return if (number != null && data.size == LEGACY_DATA_BYTES) number to data else null
    }

    /** Legacy protocols: messages 1-5 in order; a repeated message number starts another ECU. */
    private fun fromLegacy(lines: List<String>): List<String> {
        val groups = mutableListOf<MutableMap<Int, List<Int>>>()
        var current = mutableMapOf<Int, List<Int>>()
        for ((number, data) in lines.mapNotNull { legacyMessage(bytesOf(it)) }) {
            if (number in current) {
                groups += current
                current = mutableMapOf()
            }
            current[number] = data
        }
        if (current.isNotEmpty()) groups += current
        return groups.filter { it.size == LEGACY_MESSAGES }
            .mapNotNull { g -> ascii(g.toSortedMap().values.flatten().dropWhile { it == 0 }) }
    }

    private fun markerAt(bytes: List<Int>): Int? =
        (0 until bytes.size - 1).firstOrNull { bytes[it] == MODE_09_REPLY && bytes[it + 1] == PID_VIN }

    /** The VIN bytes after `49 02` (and the `01` item count when present), as text, or null. */
    private fun vinAfterMarker(bytes: List<Int>): String? {
        val at = markerAt(bytes) ?: return null
        var rest = bytes.drop(at + 2)
        if (rest.size > VIN_LENGTH && rest.first() == 1) rest = rest.drop(1)
        rest = rest.dropWhile { it == 0 }
        return if (rest.size < VIN_LENGTH) null else ascii(rest.take(VIN_LENGTH))
    }

    private fun ascii(bytes: List<Int>): String? =
        if (bytes.all { it in PRINTABLE }) bytes.joinToString("") { it.toChar().toString() } else null

    /** Hex text (spaces optional) to bytes, or null when it is not whole hex bytes. */
    private fun bytesOf(text: String): List<Int>? {
        val compact = text.replace(" ", "")
        if (compact.isEmpty() || compact.length % 2 != 0 || !HEX_ONLY.matches(compact)) return null
        return compact.chunked(2).map { it.toInt(HEX_RADIX) }
    }
}

/**
 * One VIN read per connect: up to [attempts] tries of [send], stopping at the first VIN. Bounded
 * by the caller's command timeout per try; never throws (a thrown send counts as no response), so
 * a failed read can never stop OBD data from flowing.
 */
object VinReader {
    const val DEFAULT_ATTEMPTS = 2

    suspend fun read(attempts: Int = DEFAULT_ATTEMPTS, send: suspend () -> String): VinResponse.Result {
        var last: VinResponse.Result = VinResponse.Result.NoResponse
        repeat(attempts.coerceAtLeast(1)) {
            // Any failed send is "no answer", never a crash; cancellation still propagates.
            val raw = runCatching { send() }
                .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else "" }
            last = VinResponse.parse(raw)
            if (last is VinResponse.Result.Vin) return last
        }
        return last
    }
}
