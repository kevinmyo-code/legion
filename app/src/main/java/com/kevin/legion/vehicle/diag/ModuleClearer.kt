package com.kevin.legion.vehicle.diag

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** How one module's clear ended. Only [CONFIRMED] may ever be said as "cleared". */
@Serializable
enum class ClearOutcome(val words: String) {
    CONFIRMED("cleared, confirmed by a re-read"),
    RETURNED("clear sent, but codes came straight back (an active fault)"),
    UNVERIFIED("clear sent, but the re-read failed, so it is not known whether it took"),
    REFUSED("the module refused the clear"),
    NO_ANSWER("the module did not answer the clear"),
}

@Serializable
data class ClearResult(
    val target: ClearTarget,
    val outcome: ClearOutcome,
    val before: List<String>,
    val after: List<String>? = null,
    val detail: String? = null,
    val raw: String = "",
)

/** The whole clear: per-target results, or why nothing was sent. */
@Serializable
data class ClearRun(val results: List<ClearResult>, val refusedAll: String? = null)

/**
 * Sends the clears Kevin confirmed, one target at a time, and re-reads each. "Cleared" comes only
 * from a re-read that found no faults, never from the acknowledgement (the rule `DtcClearController`
 * set for Mode 04). Refused before sending anything when the car is moving or the engine is running.
 */
class ModuleClearer(private val link: DiagLink, private val bus: BusKind) {

    suspend fun clear(targets: List<ClearTarget>, before: ScanReport): ClearRun {
        if (targets.isEmpty()) return ClearRun(emptyList(), "Nothing was chosen to clear.")
        if ((GenericPid.speedKmh(link.send("010D", TIMEOUT_MS)) ?: 0) > 0) return ClearRun(emptyList(), MOVING)
        if ((rpm(link.send("010C", TIMEOUT_MS)) ?: 0) > 0) return ClearRun(emptyList(), RUNNING)
        val engineTargets = targets.filter { it.address == ClearPlanner.ENGINE }
        val modules = targets.filter { it.address != ClearPlanner.ENGINE }
        if (!bus.isCan) return ClearRun(engineTargets.map { engine(it, before) })
        val results = mutableListOf<ClearResult>()
        val setup = ElmSetup(link, bus)
        try {
            if (!setup.begin()) return ClearRun(results, "The adapter refused headers on, so nothing was cleared.")
            if (engineTargets.isNotEmpty()) {
                val reachable = setup.functional()
                engineTargets.forEach {
                    results += if (reachable) engine(it, before) else ClearResult(
                        it, ClearOutcome.NO_ANSWER, emptyList(), detail = "the adapter refused the broadcast header",
                    )
                }
            }
            val request = ModuleRequest(link, setup, bus, TIMEOUT_MS)
            for (t in modules) results += module(t, before, setup, request)
        } finally {
            withContext(NonCancellable) { setup.restore() }
        }
        return ClearRun(results)
    }

    private suspend fun engine(t: ClearTarget, before: ScanReport): ClearResult {
        val was = before.engine?.let { (it.stored.dtcs + it.pending.dtcs).map { d -> d.code }.distinct() }.orEmpty()
        val ack = link.send("04", TIMEOUT_MS)
        val reread = EngineCodes(link, bus).read(TIMEOUT_MS)
        val after = reread.stored.takeIf { it.outcome == Outcome.READ }?.dtcs?.map { it.code }
        val outcome = when {
            after == null -> ClearOutcome.UNVERIFIED
            after.isEmpty() -> ClearOutcome.CONFIRMED
            else -> ClearOutcome.RETURNED
        }
        return ClearResult(t, outcome, was, after, raw = ack + "\n" + reread.stored.raw)
    }

    private suspend fun module(
        t: ClearTarget,
        before: ScanReport,
        setup: ElmSetup,
        request: ModuleRequest,
    ): ClearResult {
        val m = before.modules.firstOrNull { it.address == t.address }
        val was = m?.codes?.dtcs?.filter { it.fault }?.map { it.code }.orEmpty()
        val c = candidateFor(t.address)
        if (c == null || !setup.address(c)) {
            return ClearResult(t, ClearOutcome.NO_ANSWER, was, detail = "the adapter could not address this module")
        }
        val bytes = if (t.service == ClearPlanner.SERVICE_KWP) KWP_CLEAR else UDS_CLEAR
        val sent = request.send(bytes, c.responseId)
        if (sent.outcome != Outcome.READ) {
            val outcome = if (sent.outcome == Outcome.REFUSED) ClearOutcome.REFUSED else ClearOutcome.NO_ANSWER
            return ClearResult(t, outcome, was, detail = sent.detail, raw = sent.raw)
        }
        val reread = ModuleReader(request).codes(c)
        val after = reread.dtcs.filter { it.fault }.map { it.code }.takeIf { reread.outcome == Outcome.READ }
        val outcome = when {
            after == null -> ClearOutcome.UNVERIFIED
            after.isEmpty() -> ClearOutcome.CONFIRMED
            else -> ClearOutcome.RETURNED
        }
        return ClearResult(t, outcome, was, after, raw = sent.raw + "\n" + reread.raw)
    }

    private fun candidateFor(address: String): Candidate? {
        val id = address.toLongOrNull(HEX) ?: return null
        return if (address.length == ID_11_DIGITS) ModuleCandidates.candidate11(id) else ModuleCandidates.candidate29(
            (id shr BYTE) and Hex.BYTE_MAX.toLong(),
        )
    }

    private fun rpm(raw: String): Int? = ElmReply.dataLines(raw)
        .firstOrNull { it.size > RPM_BYTES && it[0] == MODE_01_REPLY && it[1] == RPM_PID }
        ?.let { (it[2] * BYTE_VALUES + it[RPM_BYTES]) / RPM_DIVISOR }

    companion object {
        const val TIMEOUT_MS = 5_000L
        private const val HEX = 16
        private const val BYTE = 8
        private const val ID_11_DIGITS = 3
        private const val MODE_01_REPLY = 0x41
        private const val RPM_PID = 0x0C
        private const val RPM_BYTES = 3
        private const val BYTE_VALUES = 256
        private const val RPM_DIVISOR = 4
        private val UDS_CLEAR = listOf(Uds.CLEAR_DTC, Hex.BYTE_MAX, Hex.BYTE_MAX, Hex.BYTE_MAX)
        private val KWP_CLEAR = listOf(Uds.CLEAR_DTC, Hex.BYTE_MAX, 0x00)
        const val MOVING = "The car is moving. Nothing was sent; clear codes only when parked."
        const val RUNNING = "The engine is running. Nothing was sent: turn the engine off, leave the key at ON, " +
            "and ask again."
    }
}
