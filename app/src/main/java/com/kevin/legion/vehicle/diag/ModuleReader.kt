package com.kevin.legion.vehicle.diag

/**
 * Reads one module the adapter is already pointed at: is anyone there, its trouble codes, and who
 * it says it is. Every request is a read (TesterPresent, ReadDTCInformation, ReadDataByIdentifier,
 * and the KWP2000 reads) in the default session. Nothing here changes a module.
 */
class ModuleReader(private val request: ModuleRequest) {

    /** True when anything answered at [c], even a refusal: a refusal proves a module is there. */
    suspend fun present(c: Candidate): Boolean {
        val r = request.send(listOf(Uds.TESTER_PRESENT, 0x00), c.responseId)
        return r.outcome == Outcome.READ || r.outcome == Outcome.REFUSED || r.outcome == Outcome.PARTIAL
    }

    /**
     * UDS 0x19 0x02 (all status bits). When the module says that is not how to ask it, KWP2000 0x18
     * (by status), then 0x13. The UDS refusal is the one reported when all three are refused.
     */
    suspend fun codes(c: Candidate): CodeRead {
        val uds = request.send(listOf(Uds.READ_DTC, Uds.REPORT_BY_STATUS, Uds.STATUS_MASK_ALL), c.responseId)
        positive(uds)?.let { return CodeRead(uds.outcome, UDS_NAME, DtcBytes.udsByStatus(it), uds.detail, uds.raw) }
        if (!worthFallback(uds)) return CodeRead(uds.outcome, UDS_NAME, detail = uds.detail, raw = uds.raw)

        val kwp = request.send(listOf(Uds.KWP_READ_DTC_BY_STATUS, 0x00, KWP_GROUP_ALL, 0x00), c.responseId)
        positive(kwp)?.let {
            return CodeRead(kwp.outcome, KWP_STATUS_NAME, DtcBytes.kwpByStatus(it), kwp.detail, kwp.raw)
        }
        val plain = request.send(listOf(Uds.KWP_READ_DTC), c.responseId)
        positive(plain)?.let {
            return CodeRead(plain.outcome, KWP_PLAIN_NAME, DtcBytes.kwpPlain(it), plain.detail, plain.raw)
        }
        return CodeRead(
            outcome = uds.outcome,
            service = UDS_NAME,
            detail = uds.detail + "; KWP reads: " + (kwp.detail ?: kwp.outcome.words) + ", " +
                (plain.detail ?: plain.outcome.words),
            raw = listOf(uds.raw, kwp.raw, plain.raw).joinToString("\n"),
        )
    }

    /** The identity DIDs, in order, while [hasTime] says yes. Unread ones are listed as not read. */
    suspend fun ids(c: Candidate, hasTime: () -> Boolean): List<IdRead> = ID_DIDS.map { (did, label) ->
        if (!hasTime()) return@map IdRead(Hex.byte(did shr BYTE) + Hex.byte(did), label, Outcome.NOT_READ)
        val r = request.send(listOf(Uds.READ_DID, did shr BYTE, did and Hex.BYTE_MAX), c.responseId)
        val value = positive(r)?.drop(2)?.let(::text)
        val outcome = if (value != null) Outcome.READ else r.outcome
        IdRead(Hex.byte(did shr BYTE) + Hex.byte(did), label, outcome, value ?: r.detail)
    }

    private fun positive(r: ModuleRequest.Result): List<Int>? =
        (r.answer as? Uds.Answer.Positive)?.payload
            ?.takeIf { r.outcome == Outcome.READ || r.outcome == Outcome.PARTIAL }

    private fun worthFallback(r: ModuleRequest.Result): Boolean =
        (r.answer as? Uds.Answer.Negative)?.let { Uds.meansTryAnotherService(it.nrc) } ?: false

    companion object {
        const val UDS_NAME = "UDS 19 02 FF"
        const val KWP_STATUS_NAME = "KWP2000 18"
        const val KWP_PLAIN_NAME = "KWP2000 13"
        private const val KWP_GROUP_ALL = 0xFF
        private const val BYTE = 8
        private const val PRINTABLE_LOW = 0x20
        private const val PRINTABLE_HIGH = 0x7E

        /** VIN, spare part number, software number, serial, Ford-style assembly ids, system name. */
        @Suppress("MagicNumber") // ISO 14229-1 C.1 identifiers (F1xx) and Ford's F111/F113
        val ID_DIDS: List<Pair<Int, String>> = listOf(
            0xF190 to "VIN",
            0xF197 to "system name",
            0xF187 to "part number",
            0xF188 to "software number",
            0xF18C to "serial number",
            0xF111 to "core assembly (Ford style)",
            0xF113 to "assembly (Ford style)",
        )

        /** ASCII when every byte is printable (trailing 00/FF padding dropped), hex otherwise. */
        fun text(bytes: List<Int>): String? {
            val trimmed = bytes.dropLastWhile { it == 0 || it == Hex.BYTE_MAX }
            if (trimmed.isEmpty()) return null
            return if (trimmed.all { it in PRINTABLE_LOW..PRINTABLE_HIGH }) {
                trimmed.map { it.toChar() }.joinToString("").trim()
            } else {
                Hex.bytes(trimmed)
            }
        }
    }
}
