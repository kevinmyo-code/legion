package com.kevin.legion.vehicle.diag

/**
 * Generic OBD trouble codes from the emissions ECUs: Mode 03 (stored), 07 (pending), 0A (permanent).
 * On CAN they are asked at the functional address with headers on, so each answering ECU is named
 * by its id. On the legacy buses (J1850, ISO 9141, KWP on the K-line) they are asked exactly as the
 * telemetry poller asks, headers off, with the slow-bus wait.
 */
class EngineCodes(private val link: DiagLink, private val bus: BusKind) {

    suspend fun read(timeoutMs: Long): EngineReport {
        val stored = one(MODE_STORED, timeoutMs)
        val pending = one(MODE_PENDING, timeoutMs)
        val permanent = one(MODE_PERMANENT, timeoutMs)
        val responders = listOf(stored, pending, permanent).flatMap { it.second }.distinct()
        return EngineReport(stored.first, pending.first, permanent.first, responders)
    }

    private suspend fun one(mode: Int, timeoutMs: Long): Pair<CodeRead, List<String>> {
        val raw = link.send(Hex.byte(mode), timeoutMs)
        val name = "OBD mode ${Hex.byte(mode)}"
        return if (bus.isCan) can(raw, mode, name) else legacy(raw, mode, name)
    }

    private fun can(raw: String, mode: Int, name: String): Pair<CodeRead, List<String>> =
        when (val reply = ElmReply.parseCan(raw, bus)) {
            is ElmReply.Messages -> {
                val answers = reply.messages.filter { it.data.firstOrNull() == mode + Uds.POSITIVE_OFFSET }
                if (answers.isEmpty()) {
                    CodeRead(Outcome.NO_ANSWER, name, detail = "no ECU answered this mode", raw = raw) to emptyList()
                } else {
                    val codes = answers.flatMap { DtcBytes.obdCodes(it.data.drop(1), can = true) }.distinct()
                    val cut = answers.any { !it.complete }
                    CodeRead(
                        outcome = if (cut) Outcome.PARTIAL else Outcome.READ,
                        service = name,
                        dtcs = codes.map { ModuleDtc(it, words = listOf(wordFor(mode))) },
                        detail = if (cut) ModuleRequest.CUT_WORDS else null,
                        raw = raw,
                    ) to answers.map { Hex.id(it.sender) }
                }
            }
            else -> failed(reply, mode, name, raw) to emptyList()
        }

    private fun legacy(raw: String, mode: Int, name: String): Pair<CodeRead, List<String>> {
        val failure = ElmReply.failure(ElmReply.lines(raw))
        val lines = ElmReply.dataLines(raw).filter { it.first() == mode + Uds.POSITIVE_OFFSET }
        val codes = lines.flatMap { DtcBytes.obdCodes(it.drop(1), can = false) }.distinct()
        return when {
            failure != null -> failed(failure, mode, name, raw) to emptyList()
            lines.isEmpty() -> CodeRead(Outcome.NO_ANSWER, name, detail = "unreadable reply", raw = raw) to emptyList()
            else -> {
                val dtcs = codes.map { ModuleDtc(it, words = listOf(wordFor(mode))) }
                CodeRead(Outcome.READ, name, dtcs, raw = raw) to listOf("engine (generic)")
            }
        }
    }

    private fun failed(reply: ElmReply, mode: Int, name: String, raw: String): CodeRead = when (reply) {
        ElmReply.NotUnderstood -> CodeRead(Outcome.NOT_SUPPORTED, name, raw = raw)
        is ElmReply.BusError -> CodeRead(Outcome.NO_ANSWER, name, detail = "adapter reported ${reply.word}", raw = raw)
        else -> CodeRead(
            Outcome.NO_ANSWER,
            name,
            detail = if (mode == MODE_PERMANENT && !bus.isCan) PERMANENT_LEGACY else "no ECU answered this mode",
            raw = raw,
        )
    }

    private fun wordFor(mode: Int) = when (mode) {
        MODE_STORED -> "stored"
        MODE_PENDING -> "pending"
        else -> "permanent"
    }

    companion object {
        const val MODE_STORED = 0x03
        const val MODE_PENDING = 0x07
        const val MODE_PERMANENT = 0x0A
        const val PERMANENT_LEGACY =
            "permanent codes (Mode 0A) arrived with the 2010s CAN cars; this bus does not carry them"
    }
}
