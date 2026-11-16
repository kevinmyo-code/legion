package com.kevin.legion.vehicle.diag

/** Reads a make's pack rows (11-bit CAN only), each labelled unverified until its row says otherwise. */
class PackReader(private val setup: ElmSetup, private val request: ModuleRequest) {

    suspend fun read(rows: List<PackReading>): List<PackReadingResult> = rows.map { row ->
        val id = row.module.toLongOrNull(HEX)
        if (id == null || !setup.address(ModuleCandidates.candidate11(id))) {
            return@map result(row, Outcome.NOT_READ, null, "")
        }
        val r = request.send(row.requestBytes(), ModuleCandidates.candidate11(id).responseId)
        val payload = (r.answer as? Uds.Answer.Positive)?.payload?.takeIf { r.outcome == Outcome.READ }
        val value = payload?.let { row.decode(row.dataAfterEcho(it)) }
        when {
            value != null -> result(row, Outcome.READ, value, r.raw)
            payload != null -> result(row, Outcome.PARTIAL, "reply too short for this reading", r.raw)
            else -> result(row, r.outcome, r.detail, r.raw)
        }
    }

    private fun result(row: PackReading, outcome: Outcome, value: String?, raw: String) = PackReadingResult(
        key = row.key,
        label = row.label,
        address = row.module,
        outcome = outcome,
        value = value,
        unit = row.unit,
        verified = row.verified,
        raw = raw,
        source = "${row.sourceUrl} (${row.applies})",
    )

    private companion object {
        const val HEX = 16
    }
}
