package com.kevin.legion.vehicle.diag

import kotlinx.serialization.Serializable

/** One trouble code as a module reported it. [status] null = the service carries no status byte. */
@Serializable
data class ModuleDtc(
    val code: String,
    /** UDS failure-type byte (the third DTC byte), e.g. "1A"; null for 2-byte codes. */
    val ftb: String? = null,
    val status: Int? = null,
    val words: List<String> = emptyList(),
    val fault: Boolean = true,
)

/** Byte layouts of the trouble-code answers this scanner reads. Pure. */
object DtcBytes {
    private const val LETTER_SHIFT = 6
    private const val LETTER_MASK = 0x03
    private const val FIRST_DIGIT_SHIFT = 4
    private const val FIRST_DIGIT_MASK = 0x03
    private const val NIBBLE = 0x0F
    private const val UDS_RECORD = 4
    private const val UDS_HEADER = 2 // sub-function echo + availability mask
    private const val KWP_RECORD = 3
    private const val PAIR = 2
    private const val UDS_STATUS_AT = 3

    /** SAE J2012 text of a 2-byte code: `01 33` is P0133, `C1 00` is U0100. */
    fun sae(hi: Int, lo: Int): String {
        val letter = "PCBU"[(hi shr LETTER_SHIFT) and LETTER_MASK]
        val first = (hi shr FIRST_DIGIT_SHIFT) and FIRST_DIGIT_MASK
        return "$letter$first${"%X".format(hi and NIBBLE)}${Hex.byte(lo)}"
    }

    /**
     * UDS 0x59 0x02 payload (after the 0x59): sub-function echo, the module's status-availability
     * mask, then 4 bytes per code (3 code bytes + status).
     */
    fun udsByStatus(payload: List<Int>): List<ModuleDtc> {
        if (payload.size < UDS_HEADER) return emptyList()
        return payload.drop(UDS_HEADER).chunked(UDS_RECORD).filter { it.size == UDS_RECORD }.map { r ->
            val status = r[UDS_STATUS_AT]
            ModuleDtc(
                code = sae(r[0], r[1]),
                ftb = Hex.byte(r[2]),
                status = status,
                words = DtcStatus.words(status),
                fault = DtcStatus.isFault(status),
            )
        }
    }

    /**
     * KWP2000 0x58 payload (after the 0x58): a count, then 2 code bytes + 1 status byte each. KWP
     * status bits are not the UDS ones and differ by maker, so they are kept as a number, not worded.
     */
    fun kwpByStatus(payload: List<Int>): List<ModuleDtc> =
        payload.drop(1).chunked(KWP_RECORD).filter { it.size == KWP_RECORD && (it[0] != 0 || it[1] != 0) }.map { r ->
            ModuleDtc(
                code = sae(r[0], r[1]),
                status = r[2],
                words = listOf("stored (KWP status 0x${Hex.byte(r[2])}, not decoded)"),
            )
        }

    /** KWP2000 0x53 payload: a count, then 2 code bytes each. */
    fun kwpPlain(payload: List<Int>): List<ModuleDtc> =
        pairs(payload.drop(1)).map { ModuleDtc(it, words = listOf("stored")) }

    /**
     * Generic OBD Mode 03/07/0A data (after the 0x43/0x47/0x4A byte). On CAN (ISO 15765-4) the
     * first byte is the NUMBER of codes; the legacy buses (J1850, ISO 9141, KWP) have no count byte
     * and pad each 6-byte line with zero pairs.
     */
    fun obdCodes(afterService: List<Int>, can: Boolean): List<String> =
        if (can) {
            val count = afterService.firstOrNull() ?: 0
            pairs(afterService.drop(1).take(count * PAIR))
        } else {
            pairs(afterService)
        }

    private fun pairs(bytes: List<Int>): List<String> =
        bytes.chunked(PAIR).filter { it.size == PAIR && (it[0] != 0 || it[1] != 0) }.map { sae(it[0], it[1]) }
}
