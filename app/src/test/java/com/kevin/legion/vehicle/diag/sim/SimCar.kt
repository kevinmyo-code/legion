package com.kevin.legion.vehicle.diag.sim

import com.kevin.legion.vehicle.diag.BusKind

/** What a simulated module does with one request. */
sealed interface SimAnswer {
    data class Bytes(val bytes: List<Int>) : SimAnswer

    /** Says "response pending" (7F sid 78) first; the real answer only lands inside a long adapter wait. */
    data class PendingThen(val bytes: List<Int>) : SimAnswer

    data object Silent : SimAnswer
}

/** Generic OBD codes an emissions ECU holds (Mode 03 / 07 / 0A). [permanentSupported] false = NO DATA. */
data class SimObd(
    val stored: List<String> = emptyList(),
    val pending: List<String> = emptyList(),
    val permanent: List<String> = emptyList(),
    val permanentSupported: Boolean = true,
)

/** One module on a CAN bus, answering physical requests to [requestId] from [responseId]. */
class SimModule(
    val requestId: Long,
    val responseId: Long,
    var obd: SimObd? = null,
    val handler: (List<Int>) -> SimAnswer,
) {
    /** Every request this module received, for tests that check what was (and was not) sent. */
    val received = mutableListOf<List<Int>>()
}

/**
 * A scripted vehicle. [bus] is what auto-detect negotiates. A legacy car (J1850, ISO 9141) has no
 * [modules]: only [legacyObd] answers, generic requests only. [latencyMs] is the simulated time
 * one answered request costs; a request nobody answers costs the adapter's response wait instead.
 */
class SimCar(
    val bus: BusKind,
    val modules: List<SimModule> = emptyList(),
    var legacyObd: SimObd? = null,
    var ignitionOn: Boolean = true,
    var speedKmh: Int = 0,
    var rpm: Int = 0,
    val latencyMs: Long = 30,
)

/** Codes as bytes, the SAE J2012 packing in reverse. */
object SimCodes {
    private const val LETTERS = "PCBU"

    fun bytes(code: String): List<Int> {
        val hi = (LETTERS.indexOf(code[0]) shl 6) or (code[1].digitToInt() shl 4) or code[2].digitToInt(16)
        val lo = code.substring(3, 5).toInt(16)
        return listOf(hi, lo)
    }

    /** UDS 59 02 reply: availability mask FF, then each (code, failure type, status). */
    fun uds(dtcs: List<Triple<String, Int, Int>>): List<Int> =
        listOf(0x59, 0x02, 0xFF) + dtcs.flatMap { (code, ftb, status) -> bytes(code) + ftb + status }

    fun ascii(text: String): List<Int> = text.map { it.code }
}
