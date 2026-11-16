package com.kevin.legion.vehicle.diag

import com.kevin.legion.vehicle.DtcClearController
import com.kevin.legion.vehicle.ObdBluetoothManager
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** How one module's clear ended. Only [CONFIRMED] may ever be said as "cleared". */
@Serializable
enum class ClearOutcome(val words: String) {
    CONFIRMED("cleared, confirmed by a re-read"),
    RETURNED("clear sent, but codes came straight back (an active fault)"),
    UNVERIFIED("clear sent, but the re-read failed, so it is not known whether it took"),
    NOTHING("nothing stored when re-read just before, so nothing was sent"),
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

/**
 * The whole clear: per-target results, or why nothing was sent ([refusedAll]), plus a [note] when
 * part of it could not run. [engineTransaction] is the engine's Mode 04 transaction as
 * [DtcClearController] ran it, kept so the caller records it exactly as the engine clear always has.
 */
@Serializable
data class ClearRun(
    val results: List<ClearResult>,
    val refusedAll: String? = null,
    val note: String? = null,
    @Transient val engineTransaction: DtcClearController.ClearResult? = null,
)

/**
 * Sends the clears Kevin confirmed, one target at a time, and re-reads each. "Cleared" comes only
 * from a re-read that found no faults, never from the acknowledgement. Refused before sending
 * anything when the car is moving or the engine is running. The engine target runs through
 * [DtcClearController.clear], the same transaction the engine-only clear uses (fresh re-read,
 * freeze frame saved, Mode 04, re-read), with headers still off.
 */
class ModuleClearer(private val link: DiagLink, private val bus: BusKind) {

    suspend fun clear(targets: List<ClearTarget>, before: ScanReport): ClearRun {
        // Fails closed on speed: a clear is a write, so a speed the car did not give is not "parked".
        val speed = if (targets.isEmpty()) null else GenericPid.speedKmh(link.send("010D", TIMEOUT_MS))
        val refusal = when {
            targets.isEmpty() -> "Nothing was chosen to clear."
            speed == null -> SPEED_UNKNOWN
            speed > 0 -> MOVING
            (rpm(link.send("010C", TIMEOUT_MS)) ?: 0) > 0 -> RUNNING
            else -> null
        }
        return if (refusal != null) ClearRun(emptyList(), refusal) else send(targets, before)
    }

    private suspend fun send(targets: List<ClearTarget>, before: ScanReport): ClearRun {
        val engineTarget = targets.firstOrNull { it.address == ClearPlanner.ENGINE }
        val engine = engineTarget?.let { engine(it) }
        val modules = targets.filter { it.address != ClearPlanner.ENGINE }
        val (moduleResults, note) = if (modules.isEmpty() || !bus.isCan) {
            emptyList<ClearResult>() to null
        } else {
            modules(modules, before)
        }
        return ClearRun(listOfNotNull(engine?.first) + moduleResults, note = note, engineTransaction = engine?.second)
    }

    private suspend fun engine(t: ClearTarget): Pair<ClearResult, DtcClearController.ClearResult> {
        val tx = DtcClearController.clear(
            confirmed = true,
            engineRunning = false,
            readCodesRaw = { link.send("03", TIMEOUT_MS) },
            readFreezeFrame = { ObdBluetoothManager.readFreezeFrame { cmd -> link.send(cmd, TIMEOUT_MS) } },
            sendClearRaw = { link.send("04", TIMEOUT_MS) },
        )
        val outcome = when (tx.outcome) {
            DtcClearController.ClearOutcome.CLEARED -> ClearOutcome.CONFIRMED
            DtcClearController.ClearOutcome.RETURNED -> ClearOutcome.RETURNED
            DtcClearController.ClearOutcome.UNVERIFIED -> ClearOutcome.UNVERIFIED
            DtcClearController.ClearOutcome.NOTHING_TO_CLEAR -> ClearOutcome.NOTHING
            DtcClearController.ClearOutcome.REFUSED, null -> ClearOutcome.NO_ANSWER
        }
        return ClearResult(t, outcome, tx.codesBefore, tx.codesAfter, tx.message, tx.ackRaw) to tx
    }

    private suspend fun modules(targets: List<ClearTarget>, before: ScanReport): Pair<List<ClearResult>, String?> {
        val setup = ElmSetup(link, bus)
        try {
            if (!setup.begin()) return emptyList<ClearResult>() to HEADERS_REFUSED
            val request = ModuleRequest(link, setup, bus, TIMEOUT_MS)
            return targets.map { module(it, before, setup, request) } to null
        } finally {
            withContext(NonCancellable) { setup.restore() }
        }
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
        val sent = request.send(if (t.service == ClearPlanner.SERVICE_KWP) KWP_CLEAR else UDS_CLEAR, c.responseId)
        return if (sent.outcome == Outcome.READ) verify(t, was, c, sent, request) else ClearResult(
            t,
            if (sent.outcome == Outcome.REFUSED) ClearOutcome.REFUSED else ClearOutcome.NO_ANSWER,
            was,
            detail = sent.detail,
            raw = sent.raw,
        )
    }

    private suspend fun verify(
        t: ClearTarget,
        was: List<String>,
        c: Candidate,
        sent: ModuleRequest.Result,
        request: ModuleRequest,
    ): ClearResult {
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
        return if (address.length == ID_11_DIGITS) {
            ModuleCandidates.candidate11(id)
        } else {
            ModuleCandidates.candidate29((id shr BYTE) and Hex.BYTE_MAX.toLong())
        }
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
        const val SPEED_UNKNOWN = "The car did not give its speed, so it is not known to be parked. Nothing was " +
            "sent; key at ON, engine off, and ask again."
        const val RUNNING = "The engine is running. Nothing was sent: turn the engine off, leave the key at ON, " +
            "and ask again."
        const val HEADERS_REFUSED = "The adapter refused headers on, so no module beyond the engine was cleared."
    }
}
