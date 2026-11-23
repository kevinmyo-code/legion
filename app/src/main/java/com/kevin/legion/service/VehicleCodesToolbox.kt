package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.assistant.cards.ToolCards
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.service.LiveToolbox.agentResult
import com.kevin.legion.service.LiveToolbox.result
import com.kevin.legion.service.LiveToolbox.withResolvedVehicle
import com.kevin.legion.util.Temp
import com.kevin.legion.vehicle.ActiveVehicle
import com.kevin.legion.vehicle.CarToolbelt
import com.kevin.legion.vehicle.DiagnosticAgent
import com.kevin.legion.vehicle.DtcClearController
import com.kevin.legion.vehicle.DtcDescriptions
import com.kevin.legion.vehicle.DtcLookup
import com.kevin.legion.vehicle.DtcMeanings
import com.kevin.legion.vehicle.ObdBluetoothManager
import com.kevin.legion.vehicle.VehicleController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The trouble-code tools (`get_codes`, `diagnose_codes`, `get_code_history`, `clear_codes`), split out
 * of [LiveToolbox] (shrink pass, 2026-11-22) along the [EngineToolbox.dispatch] pattern:
 * [LiveToolbox.dispatch] runs its guards first (the connected-car check and the unnamed-car write
 * refusal included), then asks this object, which answers the names it owns and returns null for
 * every other. `clear_codes` reads [userText], the words of the turn, for its yes-check.
 */
object VehicleCodesToolbox {

    /** Null when [name] is not one of these tools, so [LiveToolbox.dispatch] can fall through. */
    suspend fun dispatch(
        context: Context,
        name: String,
        args: JSONObject,
        userText: String? = null,
    ): JSONObject? = when (name) {
        // 2026-11-15: all_modules reads every module the dongle can reach (DiagVoice), not just
        // the engine's generic codes; the engine-only read is unchanged.
        "get_codes" -> if (args.optBoolean("all_modules", false)) {
            com.kevin.legion.vehicle.diag.DiagVoice.scan(context)
        } else {
            getCodes(context)
        }
        "diagnose_codes" -> diagnoseCodes(context, args)
        "get_code_history" -> withResolvedVehicle(context, args) { vehicle ->
            val limit = args.optInt("limit", 5).coerceIn(1, 25)
            // Structured rows with every freeze-frame reading (2026-11-03), not a text blob
            // that carried three of eight PIDs and said nothing when a frame was missing.
            val events = CarToolbelt.codeHistoryEvents(context, vehicle.obdMac, limit)
            ToolCards.codeHistory(vehicle, events)
            // The codes card pops up whenever there is something to show, the same inline
            // show-on-data pattern get_codes uses for its glance card. Automatic rather than a
            // show_codes tool: this tool sits behind ask_fleet, so the Live model never sees
            // it to pair a UI call with, and a new Live declaration costs setup bytes.
            if (events.length() > 0) VoiceModalController.show(VoiceModalTarget.CODES, vehicle.obdMac)
            JSONObject()
                .put("success", true)
                // Ticket 04's label rule: this "vehicle" field is a single identifier the model
                // is expected to speak back (unlike list_vehicles' decomposed name/year/make/
                // model/trim tuple below, which stays raw), so it goes through the one rule too.
                .put("vehicle", VehicleController.label(vehicle))
                .put("count", events.length())
                .put("events", events)
                .apply {
                    if (events.length() == 0) put("message", "No trouble-code events are recorded for this car.")
                    else put("shown", "The codes card with each freeze frame is up in the LEGION app.")
                }
        }
        // 2026-11-15: a module clear (modules named, or the yes to a module question) goes to
        // DiagVoice and its own yes-check; everything else is the engine's Mode 04 below.
        // 2026-11-16: an ask needs his words to ask for a clear (ClearCodesVoiceGate.notAskedToClear);
        // words that only read get no read here, only where the read is (car read_codes, review P2-1).
        "clear_codes" -> if (com.kevin.legion.vehicle.ClearCodesVoiceGate
                .notAskedToClear(args.optBoolean("confirmed", false), userText) != null
        ) {
            clearNotAsked(userText.orEmpty())
        } else if (com.kevin.legion.vehicle.AdapterBinding.clearRefusalNow() != null) {
            // Engine or module clear: never while the adapter's car is unknown or only recognised.
            result(false, com.kevin.legion.vehicle.AdapterBinding.clearRefusalNow().orEmpty())
        } else if (com.kevin.legion.vehicle.diag.DiagVoice.handles(
                args.optString("modules"),
                args.optBoolean("confirmed", false),
            )
        ) {
            moduleClear(context, args, userText)
        } else {
            clearCodes(context, args, userText)
        }
        else -> null
    }

    /** `clear_codes` for named modules (DiagVoice): refused in words with no dongle, nothing sent. */
    private suspend fun moduleClear(context: Context, args: JSONObject, userText: String?): JSONObject =
        if (!ObdBluetoothManager.isConnected) {
            result(false, com.kevin.legion.vehicle.diag.DiagScanController.NOT_CONNECTED)
        } else {
            com.kevin.legion.vehicle.diag.DiagVoice.clear(
                context,
                args.optString("modules").trim(),
                args.optBoolean("confirmed", false),
                userText,
            )
        }

    /** The voiced time of the clip behind this call, when a voice turn carries it. */
    private suspend fun heardVoicedMs(): Long? =
        kotlin.coroutines.coroutineContext[com.kevin.legion.vehicle.ClearCodesVoiceGate.HeardVoice]?.voicedMs

    @Suppress("ReturnCount") // refused, the link dropped during the read, or the outcome
    private suspend fun clearCodes(context: Context, args: JSONObject, userText: String?): JSONObject {
        val confirmed = args.optBoolean("confirmed", false)
        val connected = ObdBluetoothManager.isConnected
        // 2026-11-05: confirmed=true is the model's word, so the user's own words this turn must be
        // a yes to the question the unconfirmed call put (ClearCodesVoiceGate). Refused before
        // anything is read from or sent to the car.
        val gate = DtcClearController.voiceGate
        // The question and its yes belong to this connection and the car it records under.
        val subject = com.kevin.legion.vehicle.DongleCarController.connectionKey()
        val refusal = when {
            !connected -> "The OBD adapter isn't connected, so I can't clear anything."
            confirmed -> gate.checkConfirmed(userText, heardVoicedMs(), subject)
            else -> null
        }
        if (refusal != null) {
            return result(false, refusal).apply { if (connected) put("needsConfirmation", true) }
        }
        val vehicle = VehicleController.currentVehicle(context)
        val r = DtcClearController.dispatchAndRecord(context, vehicle, confirmed)
        val out = result(success = r.outcome == DtcClearController.ClearOutcome.CLEARED, message = r.message)
        if (r.outcome == DtcClearController.ClearOutcome.NOT_PARKED) {
            // Moving or speed unknown: nothing was read or sent. Said by the phone word for word, so no
            // model sentence ("Cleared.") can follow it into speech (DiagFilter drops one).
            spokenRead(out, r.message)
        }
        if (r.outcome == null) {
            // The confirm question: remember it was asked, and tell the model to ask and wait.
            // A module-clear question left waiting is void now (one question at a time).
            com.kevin.legion.vehicle.diag.DiagVoice.forget()
            gate.asked(userText, subject)
            // The question names the car, so his yes is a yes to that car.
            out.put("message", "This is for the ${VehicleController.label(vehicle)}. ${r.message}")
            // The adapter may have dropped during the read: armed first, then checked, so a disconnect
            // on either side of this line leaves no question waiting (2026-11-18 review).
            if (!ObdBluetoothManager.isConnected) {
                gate.reset()
                return result(false, "The OBD adapter disconnected, so I can't clear anything.")
            }
            out.put("needsConfirmation", true)
                .put("instruction", com.kevin.legion.vehicle.ClearCodesVoiceGate.ASK_AND_WAIT)
        } else {
            gate.reset()
        }
        return out
    }

    /** Outlives the get_codes call, so the glance card can be redrawn after the lookup settles. */
    private val glanceRefreshScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.Default,
    )

    private fun spokenCode(
        code: String,
        seed: Map<String, Pair<String, String>>,
        info: Map<String, Pair<String, String>>,
        /** The AI label as heard: the short spoken form, or null once it was said ([SpokenCarLines]). */
        aiLabel: String? = "(${DtcMeanings.AI_LABEL})",
    ): String {
        val name = info[code]?.first
        return when {
            name == null -> code
            code in seed -> "$code, $name"
            aiLabel == null -> "$code, $name"
            else -> "$code, $name $aiLabel".trimEnd()
        }
    }

    /**
     * Unknown codes (2026-11-03): the glance card says "Looking this up..." and the diagnostics
     * sub-agent is asked in the background, never inside get_codes' budget. When it settles, the
     * same card (if still showing) is redrawn with the AI-labelled answer or "No description yet";
     * the redraw restarts the card's 5 s dismiss, which is intended.
     */
    private suspend fun lookUpUnknownCodes(
        context: Context,
        codes: List<String>,
        seed: Map<String, Pair<String, String>>,
        learned: Map<String, Pair<String, String>>,
        glance: GlanceCardPayload,
    ) {
        val unknown = codes.filter { it !in seed && it !in learned }
        if (unknown.isEmpty()) return
        val lookup = DtcLookup.get(context)
        val label = ActiveVehicle.connectedCarId(context)?.let { m ->
            runCatching { com.kevin.legion.vehicle.FleetEngineStore.getByMac(context, m) }.getOrNull()
                ?.let { VehicleController.label(it) }
        }.orEmpty()
        lookup.request(label, unknown)
        glanceRefreshScope.launch {
            lookup.awaitSettled(unknown)
            val rows = codeGlanceRows(codes, seed, learned, lookup.state.value)
            GlanceCardController.replace(glance.shownAt, glance.sourceTool) { it.copy(rows = rows) }
        }
    }

    /**
     * What an unconfirmed `clear_codes` does when his words did not ask to clear (review 2026-11-19,
     * P2-1): nothing is read, cleared or asked here, and no code data comes back. The result names
     * the read door, `car read_codes` (with `all_modules` for a module-scan request), whose answer
     * code speaks through the diagnostics filter; a read through this door reached the model
     * unfiltered. [com.kevin.legion.vehicle.ClearCodesVoiceGate] stays the last check before a
     * real clear.
     */
    internal fun clearNotAsked(words: String): JSONObject {
        val scan = com.kevin.legion.vehicle.diag.ScanSay.isModuleScanQuestion(words)
        return result(false, com.kevin.legion.vehicle.ClearCodesVoiceGate.NOT_ASKED)
            .put("cleared", false).put("call_instead", "car read_codes").put("all_modules", scan)
    }

    /**
     * get_codes' empty answer, scoped to what it read (2026-11-16): one generic read of the engine's
     * stored codes. Never "everything" or "the car is clear": pending codes and every other module
     * were not read by it.
     */
    internal const val NO_ENGINE_CODES = "No stored engine codes. That is the engine's generic stored-code " +
        "read only: pending codes and the other modules were not read."

    internal const val ENGINE_CODES_NOT_READ = "The engine's stored-code read (OBD mode 03) got no real answer, so " +
        "its codes are not known and nothing is known to be clear."

    /** [ENGINE_CODES_NOT_READ] as heard: the same claim without the parenthesis read aloud (2026-11-17). */
    internal const val ENGINE_CODES_NOT_READ_SPOKEN = "The engine's code read got no real answer, so its codes " +
        "are not known and nothing is known to be clear."

    /**
     * The engine code read's words, spoken by the phone itself, word for word (stage 7 diagnostics,
     * 2026-11-17: "No stored engine codes ..." came out as "Clean bill" through the model).
     */
    private fun spokenRead(result: JSONObject, words: String): JSONObject =
        com.kevin.legion.ai.VerbatimSpeech.mark(result, words, com.kevin.legion.ai.VerbatimSpeech.DIAGNOSTICS)

    @Suppress("CyclomaticComplexMethod", "ReturnCount") // read, empty, failed, each code
    private suspend fun getCodes(context: Context): JSONObject {
        if (!ObdBluetoothManager.isConnected) {
            return result(false, "The OBD adapter isn't connected, so I can't read any codes.")
        }
        // 2026-11-16: one Mode 03 read of the engine's stored codes. A reply that is not a real
        // Mode 03 answer is a failed read and is said as one, never as "no codes".
        val raw = ObdBluetoothManager.getDtcCodesRaw()
        val parser = com.kevin.legion.vehicle.ObdResponseParser
        if (!parser.isValidDtcReply(raw)) {
            return spokenRead(result(false, ENGINE_CODES_NOT_READ), ENGINE_CODES_NOT_READ_SPOKEN)
        }
        val codes = parser.dtcCodes(raw)
        if (codes.isEmpty()) {
            ToolCards.codes(context, ActiveVehicle.connectedCarId(context), codes, emptyMap())
            return spokenRead(result(true, NO_ENGINE_CODES), NO_ENGINE_CODES)
        }

        val seed = withContext(Dispatchers.IO) { DtcDescriptions.loadSeed(context) }
        val learned = withContext(Dispatchers.IO) { DtcDescriptions.loadLearned(context) }
        val info = seed + learned
        ToolCards.codes(context, ActiveVehicle.connectedCarId(context), codes, info)
        val glance = GlanceCardPayload(
            shape = GlanceShape.LIST,
            title = "Codes",
            rows = codeGlanceRows(codes, seed, learned, DtcLookup.get(context).state.value),
            sourceTool = "get_codes",
        )
        GlanceCardController.show(glance)
        lookUpUnknownCodes(context, codes, seed, learned, glance)
        val spoken = codes.joinToString("; ") { spokenCode(it, seed, info) }
        // Heard: the AI label in its short form, at most once a conversation (2026-11-17: the whole
        // parenthesis was read aloud on every answer). The result and the screen keep it whole.
        val aiCodes = codes.any { it !in seed && info[it] != null }
        val heardLabel = if (aiCodes && SpokenCarLines.sayAiLabel()) SpokenCarLines.AI_LABEL_SPOKEN else null
        val heardCodes = codes.joinToString("; ") { spokenCode(it, seed, info, heardLabel) }
        // Each live code's freeze frame (2026-11-03): the snapshot saved with the newest stored
        // event that carries the code. Mode 02 is not re-read here - ten more PID round trips
        // inside an 8 s tool budget - and the stored frame is the ECU's same latched snapshot.
        val mac = ActiveVehicle.connectedCarId(context)
        val stored = mac?.let { m ->
            withContext(Dispatchers.IO) { CarDatabase.getDatabase(context).codeEventDao().getAll(m) }
        }.orEmpty().sortedByDescending { it.timestamp }
        val unit = com.kevin.legion.util.Temp.unit(context)
        val perCode = JSONArray()
        for (code in codes) {
            val o = JSONObject().put("code", code)
            info[code]?.first?.let {
                o.put("meaning", it).put("meaningSource", if (code in seed) "code_standard" else "ai_explanation")
            }
            val ff = stored.firstOrNull { code in CarToolbelt.codeList(it.codesJson) }
                ?.let { com.kevin.legion.vehicle.FreezeFrame.fields(it.freezeFrameJson, unit) }
                .orEmpty()
            if (ff.isEmpty()) {
                o.put("freezeFrame", JSONObject.NULL).put("snapshot", com.kevin.legion.vehicle.FreezeFrame.NO_SNAPSHOT)
            } else {
                o.put("freezeFrame", com.kevin.legion.vehicle.FreezeFrame.toJson(ff))
            }
            perCode.put(o)
        }
        if (mac != null && stored.isNotEmpty()) VoiceModalController.show(VoiceModalTarget.CODES, mac)
        val said = (if (codes.size == 1) "Stored engine code: " else "Stored engine codes: ") + "$heardCodes."
        return spokenRead(result(true, "${codes.size} code(s) stored: $spoken.").put("codes", perCode), said)
    }

    private suspend fun diagnoseCodes(context: Context, args: JSONObject): JSONObject {
        val question = args.optString("question")
        val named = args.optString("codes")
            .split(",").map { it.trim().uppercase() }.filter { it.isNotBlank() }
        val codes = when {
            named.isNotEmpty() -> named
            ObdBluetoothManager.isConnected -> ObdBluetoothManager.getDtcCodes()
            else -> emptyList()
        }

        if (codes.isEmpty()) {
            val why = if (ObdBluetoothManager.isConnected)
                "The port's connected but there are no stored codes right now - nothing on file to diagnose."
            else
                "The OBD adapter isn't connected, so I can't read any codes - plug it in, or tell me the code you're seeing."
            return result(success = false, message = why)
        }

        // Deliberately displayLabel, not VehicleController.label (ticket 04's label rule is about
        // naming a car TO the driver; this string is grounding text fed INTO a diagnostic
        // specialist's own reasoning, where the trim it drops can be mechanically relevant, e.g.
        // "330i" vs "330Ci ZHP" - a nickname-preferring label would tell the specialist less, not
        // just render differently).
        val label = VehicleController.displayLabel(VehicleController.currentVehicle(context))

        return agentResult("Could not reach the diagnostics specialist. Try again shortly.") {
            DiagnosticAgent.diagnose(context, label, codes, question)
        }
    }
}
