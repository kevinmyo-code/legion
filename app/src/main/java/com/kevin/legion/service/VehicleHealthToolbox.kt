package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.service.LiveToolbox.agentResult
import com.kevin.legion.service.LiveToolbox.result
import com.kevin.legion.service.LiveToolbox.wantsWrite
import com.kevin.legion.service.LiveToolbox.withResolvedVehicle
import com.kevin.legion.util.Temp
import com.kevin.legion.vehicle.ActiveVehicle
import com.kevin.legion.vehicle.CarToolbelt
import com.kevin.legion.vehicle.ColdStartAgent
import com.kevin.legion.vehicle.MpgTrust
import com.kevin.legion.vehicle.ObdBluetoothManager
import com.kevin.legion.vehicle.SymptomAgent
import com.kevin.legion.vehicle.TelemetryRecorder
import com.kevin.legion.vehicle.VehicleController
import org.json.JSONArray
import org.json.JSONObject

/**
 * The car health reads (`triage_symptom`, `get_health`, `get_trend`, `get_mpg`, `check_readiness`,
 * `check_cold_start`), split out of [LiveToolbox] (shrink pass, 2026-11-22) along the
 * [EngineToolbox.dispatch] pattern: [LiveToolbox.dispatch] runs its guards first (the connected-car
 * check included), then asks this object, which answers the names it owns and returns null for
 * every other.
 */
object VehicleHealthToolbox {

    /** Null when [name] is not one of these tools, so [LiveToolbox.dispatch] can fall through. */
    suspend fun dispatch(context: Context, name: String, args: JSONObject): JSONObject? = when (name) {
        "triage_symptom" -> triageSymptom(context, args)
        "get_health" -> getHealth(context)
        "get_trend" -> withResolvedVehicle(context, args) { getTrend(context, args, it.obdMac) }
        "get_mpg" -> withResolvedVehicle(context, args) { getMpg(context, it) }
        "check_readiness" -> checkReadiness()
        "check_cold_start" -> checkColdStart(context)
        else -> null
    }

    /**
     * Delegates to the symptom-triage specialist ([SymptomAgent]). Reads whatever
     * live values are available off the port (so the worker can weigh them) and
     * hands them plus the symptom to the worker. Another mid-conversation
     * round-trip, so Zero typically says "let me think..." first.
     */
    private suspend fun triageSymptom(context: Context, args: JSONObject): JSONObject {
        val symptom = args.optString("symptom")
        if (symptom.isBlank()) return result(success = false,
            message = "Tell me what it's doing - a noise, a smell, how it's driving - and I'll work through it.")

        val vehicle = VehicleController.currentVehicle(context)
        // displayLabel, not label - specialist grounding text, same reasoning as diagnose_codes
        // above (ticket 04's label rule governs naming the car TO the driver, not what gets fed
        // into a reasoning specialist's own prompt).
        val label = VehicleController.displayLabel(vehicle)

        val readings = StringBuilder()
        var codes = emptyList<String>()
        if (ObdBluetoothManager.isConnected) {
            ObdBluetoothManager.getCoolantTemp()?.let { readings.append("coolant ${Temp.text(context, it.toDouble())} (user's unit, already converted - do not convert again); ") }
            ObdBluetoothManager.getRpm()?.let { readings.append("RPM $it; ") }
            ObdBluetoothManager.getBatteryVoltage()?.let { readings.append("battery ${"%.1f".format(it)}V; ") }
            codes = ObdBluetoothManager.getDtcCodes()
        }

        return agentResult("Could not reach the diagnostics specialist. Try again shortly.") {
            SymptomAgent.triage(context, label, readings.toString().trim().trimEnd(';'), codes, symptom)
        }
    }

    /**
     * Quick OBD health snapshot for "is the car okay?" / pre-trip checks. Returns
     * the raw readings plus any obvious concern flags for Zero to phrase; the
     * model interprets and speaks them in character.
     */
    private suspend fun getHealth(context: Context): JSONObject {
        if (!ObdBluetoothManager.isConnected) {
            return JSONObject().put("connected", false)
                .put("note", "OBD adapter not connected; tell the user to check it's plugged in.")
        }
        val coolant = ObdBluetoothManager.getCoolantTemp()
        val voltage = ObdBluetoothManager.getBatteryVoltage()
        val codes = ObdBluetoothManager.getDtcCodes()
        val coolantUnit = Temp.unit(context)

        val concerns = JSONArray()
        if (voltage != null && voltage < LOW_VOLTAGE) concerns.put("Battery voltage is low (${"%.1f".format(voltage)}V) - it may be weak or not charging.")
        if (coolant != null && coolant >= HOT_COOLANT_C) concerns.put("Coolant is running hot (${Temp.text(context, coolant.toDouble())}).")
        if (codes.isNotEmpty()) concerns.put("${codes.size} stored trouble code(s): ${codes.joinToString(", ")}.")
        val healthSummary = if (concerns.length() == 0) "Everything reads normal - no codes, voltage and temperature look fine." else null

        val glanceRows = buildList {
            if (voltage != null) add(GlanceRow("Battery", "${"%.1f".format(voltage)}V"))
            if (coolant != null) add(GlanceRow("Coolant", Temp.text(context, coolant.toDouble())))
            for (i in 0 until concerns.length()) add(GlanceRow("Note", concerns.getString(i)))
        }
        GlanceCardController.show(
            GlanceCardPayload(
                shape = GlanceShape.HEADLINE_LIST,
                title = "Health",
                headline = healthSummary ?: if (concerns.length() == 1) "1 concern" else "${concerns.length()} concerns",
                rows = glanceRows,
                sourceTool = "get_health",
            )
        )

        return JSONObject().put("connected", true).apply {
            if (voltage != null) put("batteryVolts", voltage)
            if (coolant != null) put("coolant", Temp.convert(coolant.toDouble(), coolantUnit)).put("coolantUnit", coolantUnit.symbol)
            put("storedCodeCount", codes.size)
            put("concerns", concerns)
            if (healthSummary != null) put("summary", healthSummary)
        }
    }

    private const val LOW_VOLTAGE = 12.0
    private const val HOT_COOLANT_C = 105

    /**
     * Trend summary over the recorded obd_samples history. The aggregation now
     * lives in [CarToolbelt.trendSummary] (one source of truth, shared with the
     * investigating sub-agents); this just wraps its text for the Live model.
     * [vehicleId] is the resolved fleet-wide-voice override (ticket 01) - null
     * means the active car.
     */
    private suspend fun getTrend(context: Context, args: JSONObject, vehicleId: String? = null): JSONObject {
        // **This used to read the flag off the sentence**: `val ok = !text.startsWith("Unknown
        // metric") && !text.startsWith("Not enough history")`, with the comment "preserve the
        // pre-delegation success flag for the two soft-fail sentences". There were three, not two -
        // MpgTrust.VOICE_REFUSAL matched neither prefix - so `get_trend` with metric="mpg" reported
        // success over a refusal. The flag comes from [CarToolbelt.Trend] now; see its own doc.
        val trend = CarToolbelt.trendSummary(context, args.optString("metric"), args.optInt("days", 30), vehicleId)
        return result(trend.hasData, trend.text)
    }

    /**
     * Measured fuel economy: current drive, lifetime, and recent per-drive
     * numbers. [vehicle] is the already-resolved car (ticket 01 fleet-wide
     * voice).
     *
     * `currentDriveMpg`/`lifetimeMpg` come from [TelemetryRecorder], which
     * ticket 01 §2 explicitly excludes from vehicle-override threading - it
     * writes/reads live telemetry for whichever car is ACTUALLY being driven,
     * never a named one. So when [vehicle] is the second, non-active car,
     * those two fields would silently be the ACTIVE car's numbers mislabeled
     * under the requested car - the exact mislabeled-answer failure ticket 01
     * §0 exists to remove. They're only included when [vehicle] IS the active
     * car; asking about the other car gets its recorded per-drive trips only
     * (correctly scoped, straight from `odb_samples`), with a note rather than
     * a silently-wrong figure.
     *
     * **Suppressed under [MpgTrust.SHOW_MPG] == false** (ticket 09,
     * `.scratch/drive-ui/issues/09-mpg-scale-bug.md`): every branch below this refusal is dead
     * code while the flag is off, kept intact rather than deleted so re-enabling is the flag alone.
     * The refusal never reaches [TelemetryRecorder] or the DB at all - there is nothing to hedge
     * partial data against, the model must simply not be handed a number to speak.
     */
    private suspend fun getMpg(context: Context, vehicle: Vehicle): JSONObject {
        if (!MpgTrust.SHOW_MPG) return result(false, MpgTrust.VOICE_REFUSAL)

        val trips = CarDatabase.getDatabase(context).odbSampleDao()
            .getLatest(vehicle.obdMac, "MPG_TRIP", 5)
        val isActiveCar = vehicle.obdMac == ActiveVehicle.current(context)
        val current = if (isActiveCar) TelemetryRecorder.currentDriveMpg() else null
        val lifetime = if (isActiveCar) TelemetryRecorder.lifetimeMpg(context) else null
        if (current == null && lifetime == null && trips.isEmpty()) {
            return result(false, "No fuel data yet - MPG needs the OBD link and some miles on the clock.")
        }

        val glanceRows = buildList {
            if (lifetime != null) add(GlanceRow("Lifetime avg", "%.1f".format(lifetime)))
            trips.forEachIndexed { i, sample ->
                // i is a zero-based index, so the label needs i+1 - "$i drives ago"
                // rendered the second row as "1 drives ago".
                add(GlanceRow(if (i == 0) "Last drive" else "${i + 1} drives ago", "%.1f".format(sample.value)))
            }
        }
        GlanceCardController.show(
            GlanceCardPayload(
                shape = GlanceShape.HEADLINE_LIST,
                title = "MPG",
                headline = current?.let { "%.1f mpg this drive".format(it) }
                    ?: lifetime?.let { "%.1f mpg lifetime avg".format(it) },
                rows = glanceRows,
                sourceTool = "get_mpg",
            )
        )

        return JSONObject().put("success", true).apply {
            if (current != null) put("currentDriveMpg", "%.1f".format(current))
            if (lifetime != null) put("lifetimeMpg", "%.1f".format(lifetime))
            if (trips.isNotEmpty()) {
                put("recentDrivesMpg", JSONArray(trips.map { "%.1f".format(it.value) }))
            }
            if (!isActiveCar) {
                put("note", "Live/lifetime MPG only tracks the car actually being driven right now - " +
                    "these are ${VehicleController.label(vehicle)}'s recorded per-drive figures only.")
            }
        }
    }

    /** Live emissions-readiness read - which monitors are done, which need drive time. */
    private suspend fun checkReadiness(): JSONObject {
        if (!ObdBluetoothManager.isConnected) {
            return result(false, "Couldn't read readiness - is the OBD link up?")
        }
        val r = ObdBluetoothManager.getReadiness()
            ?: return result(false, "The car didn't answer the readiness request.")
        val incomplete = r.monitors.filter { !it.complete }.map { it.name }
        val summary = buildString {
            append(if (r.milOn) "Check-engine light is ON" else "Check-engine light is off")
            if (r.dtcCount > 0) append(" with ${r.dtcCount} stored code(s)")
            append(". ")
            append("${r.monitors.count { it.complete }} of ${r.monitors.size} monitors complete.")
            if (incomplete.isNotEmpty()) {
                append(" Not ready yet: ${incomplete.joinToString(", ")} - ")
                append("drive a normal mixed cycle before an inspection.")
            } else {
                append(" All ready - good to go for an inspection.")
            }
        }

        val headline = buildString {
            append(if (r.milOn) "Check engine ON" else "Check engine off")
            append(" · ${r.dtcCount} code(s)")
        }
        GlanceCardController.show(
            GlanceCardPayload(
                shape = GlanceShape.STATUS_GRID,
                title = "Readiness",
                headline = headline,
                cells = r.monitors.map { GlanceCell(it.name, it.complete) },
                sourceTool = "check_readiness",
            )
        )

        return result(true, summary)
    }

    /**
     * Hands the cold-start question to the investigating [ColdStartAgent], which
     * pulls the recorded bursts itself via the toolbelt. We keep only the cheap
     * "nothing captured yet" early-out. Extra round-trip - Zero typically says
     * "let me look..." first.
     */
    private suspend fun checkColdStart(context: Context): JSONObject {
        val vehicle = VehicleController.currentVehicle(context)
        // The `label` computed below (for ColdStartAgent) stays on displayLabel deliberately -
        // see diagnose_codes' comment above for why specialist grounding text is exempt from
        // ticket 04's label rule.
        val markers = CarDatabase.getDatabase(context).odbSampleDao()
            .getLatest(vehicle.obdMac, "COLD_START", 1)
        if (markers.isEmpty()) {
            return result(false, "No cold start captured yet - I watch the first minute after a cold morning start.")
        }
        val label = VehicleController.displayLabel(vehicle)
        return agentResult("Could not reach the cold-start analyst. Try again shortly.") {
            ColdStartAgent.analyze(context, label)
        }
    }
}
