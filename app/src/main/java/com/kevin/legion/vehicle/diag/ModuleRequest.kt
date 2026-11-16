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
    /** What one request produced. [raw] is the adapter's reply, kept with the result (§4 rule 8). */
    data class Result(val outcome: Outcome, val answer: Uds.Answer?, val raw: String, val detail: String? = null)

    suspend fun send(bytes: List<Int>, responseId: Long): Result {
        val service = bytes.first()
        val raw = link.send(Hex.bytes(bytes), timeoutMs)
        val first = interpret(raw, service, responseId)
        if (!isPending(first)) return first
        if (!setup.stretchTimeout()) return first.copy(outcome = Outcome.NO_ANSWER, detail = PENDING_WORDS)
        val again = link.send(Hex.bytes(bytes), timeoutMs)
        setup.normalTimeout()
        val second = interpret(again, service, responseId).let { it.copy(raw = raw + "\n" + it.raw) }
        return if (isPending(second)) second.copy(outcome = Outcome.NO_ANSWER, detail = PENDING_WORDS) else second
    }

    private fun isPending(r: Result) = (r.answer as? Uds.Answer.Negative)?.nrc == Uds.NRC_PENDING

    private fun interpret(raw: String, service: Int, responseId: Long): Result {
        return when (val reply = ElmReply.parseCan(raw, bus)) {
            ElmReply.NoData, ElmReply.Silent -> Result(Outcome.NO_ANSWER, null, raw)
            ElmReply.NotUnderstood -> Result(Outcome.NOT_SUPPORTED, null, raw)
            is ElmReply.BusError -> Result(Outcome.NO_ANSWER, null, raw, "adapter reported ${reply.word}")
            is ElmReply.Messages -> fromMessages(reply.messages.filter { it.sender == responseId }, service, raw)
        }
    }

    private fun fromMessages(mine: List<CanMessage>, service: Int, raw: String): Result {
        // The last answer wins: a pending 0x78 is followed by the real one in the same reply.
        val answered = mine.map { it to Uds.classify(it, service) }.lastOrNull { it.second != Uds.Answer.Other }
        return when {
            mine.isEmpty() -> Result(Outcome.NO_ANSWER, null, raw, "only other modules answered")
            answered == null -> Result(Outcome.NO_ANSWER, null, raw, "the answer was not to this request")
            !answered.first.complete -> Result(Outcome.PARTIAL, answered.second, raw, CUT_WORDS)
            answered.second is Uds.Answer.Negative -> {
                val nrc = (answered.second as Uds.Answer.Negative).nrc
                Result(Outcome.REFUSED, answered.second, raw, Uds.nrcWords(nrc))
            }
            else -> Result(Outcome.READ, answered.second, raw)
        }
    }

    companion object {
        const val PENDING_WORDS = "the module said it was busy and never gave its answer"
        const val CUT_WORDS =
            "the reply was cut off after its first part: the adapter's flow control did not reach the module"
    }
}
