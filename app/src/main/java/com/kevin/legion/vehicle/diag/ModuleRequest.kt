package com.kevin.legion.vehicle.diag

/**
 * One physical request to the module the adapter is pointed at, read back as a service answer.
 * A "response pending" (0x7F xx 0x78) gets one retry with the adapter's longest wait; a first frame
 * whose rest never came is reported as cut off, never as an answer.
 */
class ModuleRequest(
    private val link: DiagLink,
    private val setup: ElmSetup,
    private val bus: BusKind,
    private val timeoutMs: Long,
) {
    /**
     * What one request produced. [raw] is the adapter's reply, kept with the result (section 4
     * rule 8). [linkTrouble] is true when the ADAPTER failed (nothing at all came back, or it
     * reported a bus error), as opposed to `NO DATA`, which is the adapter saying nobody answered.
     */
    data class Result(
        val outcome: Outcome,
        val answer: Uds.Answer?,
        val raw: String,
        val detail: String? = null,
        val linkTrouble: Boolean = false,
    )

    suspend fun send(bytes: List<Int>, responseId: Long): Result {
        val service = bytes.first()
        val raw = link.send(Hex.bytes(bytes), timeoutMs)
        val first = interpret(raw, service, responseId)
        return when {
            !isPending(first) -> first
            !setup.stretchTimeout() -> first.copy(outcome = Outcome.NO_ANSWER, detail = PENDING_WORDS)
            else -> retry(bytes, responseId, raw)
        }
    }

    private suspend fun retry(bytes: List<Int>, responseId: Long, firstRaw: String): Result {
        val again = link.send(Hex.bytes(bytes), timeoutMs)
        setup.normalTimeout()
        val second = interpret(again, bytes.first(), responseId).let { it.copy(raw = firstRaw + "\n" + it.raw) }
        return if (isPending(second)) second.copy(outcome = Outcome.NO_ANSWER, detail = PENDING_WORDS) else second
    }

    private fun isPending(r: Result) = (r.answer as? Uds.Answer.Negative)?.nrc == Uds.NRC_PENDING

    private fun interpret(raw: String, service: Int, responseId: Long): Result =
        when (val reply = ElmReply.parseCan(raw, bus)) {
            ElmReply.NoData -> Result(Outcome.NO_ANSWER, null, raw)
            ElmReply.Silent -> Result(Outcome.NO_ANSWER, null, raw, SILENT_WORDS, linkTrouble = true)
            ElmReply.NotUnderstood -> Result(Outcome.NOT_SUPPORTED, null, raw)
            is ElmReply.BusError ->
                Result(Outcome.NO_ANSWER, null, raw, "adapter reported ${reply.word}", linkTrouble = true)
            is ElmReply.Messages -> fromMessages(reply.messages.filter { it.sender == responseId }, service, raw)
        }

    private fun fromMessages(mine: List<CanMessage>, service: Int, raw: String): Result {
        // The last answer wins: a pending 0x78 is followed by the real one in the same reply.
        val answered = mine.map { it to Uds.classify(it, service) }.lastOrNull { it.second != Uds.Answer.Other }
        val negative = answered?.second as? Uds.Answer.Negative
        return when {
            mine.isEmpty() -> Result(Outcome.NO_ANSWER, null, raw, "only other modules answered")
            answered == null -> Result(Outcome.NO_ANSWER, null, raw, "the answer was not to this request")
            !answered.first.complete -> Result(Outcome.PARTIAL, answered.second, raw, CUT_WORDS)
            negative != null -> Result(Outcome.REFUSED, negative, raw, Uds.nrcWords(negative.nrc))
            else -> Result(Outcome.READ, answered.second, raw)
        }
    }

    companion object {
        const val PENDING_WORDS = "the module said it was busy and never gave its answer"
        const val SILENT_WORDS = "the adapter returned nothing at all (not even NO DATA)"
        const val CUT_WORDS =
            "the reply was cut off after its first part: the adapter's flow control did not reach the module"
    }
}
