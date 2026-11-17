package com.kevin.legion.vehicle.diag

import android.content.Context
import com.kevin.legion.ai.VerbatimSpeech
import com.kevin.legion.assistant.cards.ToolCards
import com.kevin.legion.vehicle.ActiveVehicle
import com.kevin.legion.vehicle.ClearCodesVoiceGate
import com.kevin.legion.vehicle.DongleCar
import com.kevin.legion.vehicle.DtcClearController
import com.kevin.legion.vehicle.VehicleController
import org.json.JSONArray
import org.json.JSONObject

/**
 * The voice side of module diagnostics: `get_codes` with `all_modules` (behind ask_fleet, no new
 * declaration) and the `car` tool's `clear_codes` with `modules`. A finished scan publishes its
 * `status_list` card ([DiagCard]).
 */
object DiagVoice {
    /** Longest a voice call waits for a scan, inside the 32 s slow-read leash. */
    const val SCAN_WAIT_MS = 28_000L
    private const val REUSE_MS = 3 * 60_000L

    /** The module clear's own yes-check; one yes buys one clear, as for the engine clear. */
    val gate = ClearCodesVoiceGate()

    private data class PendingClear(val scanId: Long, val addresses: Set<String>)

    @Volatile private var pending: PendingClear? = null

    suspend fun carLabel(context: Context, carId: String): String =
        if (DongleCar.isPending(carId)) {
            "car the dongle is in (which car it is has not been answered yet)"
        } else {
            VehicleController.label(VehicleController.vehicleFor(context, carId))
        }

    /**
     * Scans the car the dongle is in (or reuses a scan of it from the last few minutes) and says what
     * it found. Every answer, running and failed included, is spoken by the phone itself (VerbatimSpeech).
     */
    suspend fun scan(context: Context): JSONObject = scanResult(context).also { r ->
        if (!r.has(VerbatimSpeech.KEY)) VerbatimSpeech.mark(r, r.optString("message"), VerbatimSpeech.DIAGNOSTICS)
    }

    private suspend fun scanResult(context: Context): JSONObject {
        val carId = ActiveVehicle.recordingId(context)
        val recent = DiagScanController.state.value as? DiagScanController.State.Done
        // A scan that stopped (key off, moving) is never reused: it would repeat the stop for minutes.
        val reuse = recent?.takeIf {
            it.carId == carId && it.report.stopped == null &&
                System.currentTimeMillis() - it.report.finishedAtMs < REUSE_MS
        }
        if (reuse == null) {
            val started = DiagScanController.start(context, full = false)
            if (started == DiagScanController.Start.NOT_CONNECTED) return fail(DiagScanController.NOT_CONNECTED)
        }
        return when (val s = reuse ?: DiagScanController.await(SCAN_WAIT_MS)) {
            is DiagScanController.State.Done -> done(context, s)
            is DiagScanController.State.Running -> JSONObject()
                .put("success", true)
                .put("running", true)
                .put("message", ScanSay.runningWords(s))
            is DiagScanController.State.Failed -> fail(s.words)
            DiagScanController.State.Idle -> fail("No scan ran.")
        }
    }

    private suspend fun done(context: Context, s: DiagScanController.State.Done): JSONObject {
        val label = carLabel(context, s.carId)
        val previous = DiagScanStore.latest(context, s.carId, 2).firstOrNull { it.row.id != s.scanId }
        val diff = previous?.let { ScanCompare.diff(it.report, s.report) }
        // The status_list card, one row per module (2026-11-16). It replaced the codes card here,
        // which drew "No trouble codes stored." for a scan that stopped or could not read a module.
        ToolCards.publish(DiagCard.scan(label, s.report))
        val meanings = JSONArray(
            DiagShape.allCodes(s.report).map { c ->
                val d = DiagScanStore.describe(context, c)
                JSONObject().put("code", c).put("meaning", d.words).put("source", d.source)
            },
        )
        return ScanSay.answer(label, s.report, diff).put("meanings", meanings)
    }

    /**
     * True when a `clear_codes` call is a module clear: it names modules, or it is the confirmed
     * answer to a module-clear question still waiting. The engine-only clear handles the rest.
     */
    fun handles(modules: String, confirmed: Boolean): Boolean = modules.isNotBlank() || (confirmed && pending != null)

    /** The engine-only clear just asked its own question: a module question left waiting is void. */
    fun forget() {
        pending = null
        gate.reset()
    }

    /**
     * `clear_codes` with `modules`: the first call names the car, the modules and what is left out,
     * and records what a yes would clear; the confirmed call clears exactly that, never the model's
     * new arguments, and only after the user's own words were a plain yes.
     */
    suspend fun clear(context: Context, modules: String, confirmed: Boolean, userText: String?): JSONObject =
        if (confirmed) confirm(context, userText) else ask(context, modules, userText)

    private suspend fun confirm(context: Context, userText: String?): JSONObject {
        val refusal = gate.checkConfirmed(userText)
        val p = pending
        if (refusal != null || p == null) {
            return fail(refusal ?: ClearCodesVoiceGate.NO_QUESTION).put("needsConfirmation", refusal != null)
        }
        pending = null
        val label = carLabel(context, ActiveVehicle.recordingId(context))
        return DiagScanController.clear(context, p.scanId, p.addresses)?.let { run ->
            JSONObject()
                .put("success", run.results.any { it.outcome == ClearOutcome.CONFIRMED })
                .put("message", ScanWords.clearSummary(label, run))
                .put("results", JSONArray(run.results.map(DiagShape::clearResult)))
        } ?: fail(DiagScanController.NOT_CONNECTED)
    }

    private suspend fun ask(context: Context, modules: String, userText: String?): JSONObject {
        val carId = ActiveVehicle.recordingId(context)
        val label = carLabel(context, carId)
        val last = DiagScanStore.latest(context, carId, 1).firstOrNull()
        val plan = last?.let { ClearPlanner.plan(it.report) }
        val chosen = plan?.let { pick(it, modules) }.orEmpty()
        return when {
            last == null || plan == null ->
                fail(
                    "There is no module scan of the $label yet, so nothing was cleared. Ask me to scan every " +
                        "module first, then ask to clear.",
                )
            chosen.isEmpty() -> {
                gate.reset()
                fail(ScanWords.clearQuestion(label, plan.copy(targets = emptyList())))
            }
            else -> {
                pending = PendingClear(last.row.id, chosen.map { it.address }.toSet())
                // One question waits at a time: the engine-only clear's question is void now.
                DtcClearController.voiceGate.reset()
                gate.asked(userText)
                JSONObject()
                    .put("success", false)
                    .put("needsConfirmation", true)
                    .put("message", ScanWords.clearQuestion(label, plan.copy(targets = chosen)))
                    .put("instruction", ClearCodesVoiceGate.ASK_AND_WAIT)
            }
        }
    }

    /** "all" (or blank) = every target; otherwise targets whose label or address the words name. */
    internal fun pick(plan: ClearPlan, words: String): List<ClearTarget> {
        val w = words.trim().lowercase()
        if (w.isEmpty() || w in ALL_WORDS) return plan.targets
        val parts = w.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        return plan.targets.filter { t -> parts.any { p -> p in t.label.lowercase() || p == t.address.lowercase() } }
    }

    private val ALL_WORDS = setOf("all", "every module", "everything", "all modules")

    private fun fail(message: String) = JSONObject().put("success", false).put("message", message)
}
