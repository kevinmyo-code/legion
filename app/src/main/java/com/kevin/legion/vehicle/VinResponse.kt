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

    /** ELM327 error words that mean the bus or the adapter failed, not that the car answered. */
    private val ERROR_WORDS = listOf(
        "UNABLE", "STOPPED", "CAN ERROR", "BUS ERROR", "BUS BUSY", "DATA ERROR", "FB ERROR",
        "RX ERROR", "BUFFER FULL", "ERR",
    )

    /** True when [candidate] is 17 characters of the VIN alphabet and not one character repeated. */
    fun isValidVin(candidate: String): Boolean =
        VIN_ALPHABET.matches(candidate) && candidate.toSet().size > 1

    @Suppress("ReturnCount") // one named outcome per failure shape, in order
    fun parse(raw: String): Result {
        val lines = raw.uppercase()
            .split('\r', '\n')
            .map { it.trim().trimStart('>').trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("SEARCHING") || (it.startsWith("BUS INIT") && "ERR" !in it) }
            .filterNot { it.replace(" ", "") == "0902" } // a command echo, if ATE0 did not take
        if (lines.isEmpty()) return Result.NoResponse
        val joined = lines.joinToString(" ")
        if ("NO DATA" in joined) return Result.NoData
        if (lines.all { it == "?" }) return Result.Unsupported
        ERROR_WORDS.firstOrNull { it in joined }?.let { return Result.Unreadable("adapter reported $it") }

        val candidates = when {
            lines.any { INDEXED_LINE.matches(it) } -> fromIndexed(lines)
            lines.any { isHeaderedCan(it) } -> fromHeadered(lines)
            lines.count { legacyMessage(bytesOf(it)) != null } >= 2 -> fromLegacy(lines)
            else -> listOfNotNull(vinAfterMarker(lines.flatMap { bytesOf(it).orEmpty() }))
        }
        if (candidates.isEmpty()) return Result.Unreadable("no VIN in the reply")
        val valid = candidates.filter { isValidVin(it) }.distinct()
        return when {
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
            if (COUNT_LINE.matches(line)) {
                if (current.isNotEmpty()) groups += current
                current = mutableMapOf()
                continue
            }
            val m = INDEXED_LINE.matchEntire(line) ?: continue
            val index = m.groupValues[1].toInt(16)
            val bytes = bytesOf(m.groupValues[2]) ?: continue
            if (index in current) {
                groups += current
                current = mutableMapOf()
            }
            current[index] = bytes
        }
        if (current.isNotEmpty()) groups += current
        return groups.mapNotNull { g -> vinAfterMarker(g.toSortedMap().values.flatten()) }
    }

    private fun isHeaderedCan(line: String): Boolean {
        val compact = line.replace(" ", "")
        if (!HEX_ONLY.matches(compact)) return false
        val firstToken = line.split(' ').first()
        return (firstToken.length == 3 && firstToken.startsWith("7E")) ||
            (compact.length % 2 == 1 && compact.startsWith("7E")) ||
            compact.startsWith("18DA")
    }

    /** CAN with headers on: reassemble each sender's ISO-TP frames separately. */
    @Suppress("MagicNumber") // ISO 15765-2 PCI nibbles
    private fun fromHeadered(lines: List<String>): List<String> {
        val bySender = linkedMapOf<String, MutableList<List<Int>>>()
        for (line in lines) {
            val compact = line.replace(" ", "")
            if (!HEX_ONLY.matches(compact)) continue
            val headerLength = if (compact.startsWith("18DA")) 8 else 3
            if (compact.length <= headerLength) continue
            val payload = bytesOf(compact.substring(headerLength)) ?: continue
            bySender.getOrPut(compact.substring(0, headerLength)) { mutableListOf() } += payload
        }
        return bySender.values.mapNotNull { frames ->
            val data = mutableListOf<Int>()
            var expected = -1
            for (frame in frames) {
                if (frame.isEmpty()) continue
                when (frame[0] shr 4) {
                    0 -> { expected = frame[0] and 0x0F; data.clear(); data += frame.drop(1) }
                    1 -> {
                        if (frame.size < 2) continue
                        expected = ((frame[0] and 0x0F) shl 8) or frame[1]
                        data.clear(); data += frame.drop(2)
                    }
                    2 -> data += frame.drop(1)
                    else -> Unit // flow control or noise
                }
            }
            val whole = if (expected in 1..data.size) data.take(expected) else data
            vinAfterMarker(whole)
        }
    }

    /** One legacy line: (message number 1-5, its 4 data bytes), or null. */
    private fun legacyMessage(bytes: List<Int>?): Pair<Int, List<Int>>? {
        if (bytes == null) return null
        val at = (0 until bytes.size - 1).firstOrNull { bytes[it] == MODE_09_REPLY && bytes[it + 1] == PID_VIN }
            ?: return null
        val number = bytes.getOrNull(at + 2) ?: return null
        if (number !in 1..LEGACY_MESSAGES) return null
        val data = bytes.drop(at + 3).take(LEGACY_DATA_BYTES)
        return if (data.size == LEGACY_DATA_BYTES) number to data else null
    }

    /** Legacy protocols: messages 1-5 in order; a repeated message number starts another ECU. */
    private fun fromLegacy(lines: List<String>): List<String> {
        val groups = mutableListOf<MutableMap<Int, List<Int>>>()
        var current = mutableMapOf<Int, List<Int>>()
        for (line in lines) {
            val (number, data) = legacyMessage(bytesOf(line)) ?: continue
            if (number in current) {
                groups += current
                current = mutableMapOf()
            }
            current[number] = data
        }
        if (current.isNotEmpty()) groups += current
        return groups.mapNotNull { g ->
            if (g.size != LEGACY_MESSAGES) return@mapNotNull null
            ascii(g.toSortedMap().values.flatten().dropWhile { it == 0 })
        }
    }

    /** The VIN bytes after `49 02` (and the `01` item count when present), as text, or null. */
    private fun vinAfterMarker(bytes: List<Int>): String? {
        val at = (0 until bytes.size - 1).firstOrNull { bytes[it] == MODE_09_REPLY && bytes[it + 1] == PID_VIN }
            ?: return null
        var rest = bytes.drop(at + 2)
        if (rest.size > VIN_LENGTH && rest.first() == 1) rest = rest.drop(1)
        rest = rest.dropWhile { it == 0 }
        if (rest.size < VIN_LENGTH) return null
        return ascii(rest.take(VIN_LENGTH))
    }

    private fun ascii(bytes: List<Int>): String? =
        if (bytes.all { it in 0x20..0x7E }) bytes.joinToString("") { it.toChar().toString() } else null

    /** Hex text (spaces optional) to bytes, or null when it is not whole hex bytes. */
    private fun bytesOf(text: String): List<Int>? {
        val compact = text.replace(" ", "")
        if (compact.isEmpty() || compact.length % 2 != 0 || !HEX_ONLY.matches(compact)) return null
        return compact.chunked(2).map { it.toInt(16) }
    }
}

/**
 * One VIN read per connect: up to [attempts] tries of [send], stopping at the first VIN. Bounded
 * by the caller's command timeout per try; never throws (a thrown send counts as no response), so
 * a failed read can never stop OBD data from flowing.
 */
object VinReader {
    const val DEFAULT_ATTEMPTS = 2

    @Suppress("TooGenericExceptionCaught") // any failed send is "no answer", never a crash
    suspend fun read(attempts: Int = DEFAULT_ATTEMPTS, send: suspend () -> String): VinResponse.Result {
        var last: VinResponse.Result = VinResponse.Result.NoResponse
        repeat(attempts.coerceAtLeast(1)) {
            val raw = try {
                send()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ""
            }
            last = VinResponse.parse(raw)
            if (last is VinResponse.Result.Vin) return last
        }
        return last
    }
}
