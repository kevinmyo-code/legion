package com.kevin.legion.vehicle

import android.content.Context
import com.kevin.legion.ai.AgentTool
import com.kevin.legion.ai.SubAgent
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.LibraryTrack
import com.kevin.legion.data.local.Mixtape
import com.kevin.legion.data.local.MusicPlay
import com.kevin.legion.util.shortDate
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * The shared read-only toolbelt the investigating specialists ([DiagnosticAgent],
 * [SymptomAgent], [MaintenanceAgent], [ColdStartAgent], [MusicAgent]) pull car data through.
 *
 * Two layers:
 *  - Formatters ([trendSummary], [codeHistory], ...): each returns a compact
 *    plain-text block, degrading to a "none recorded" / "OBD not connected"
 *    sentence rather than throwing. They are also reused by the Live voice tools
 *    (LiveToolbox delegates to them) so there is one source of formatting truth.
 *  - Belt builders ([forDiagnostics] etc.): each returns the [AgentTool]s a given
 *    specialist may call. The worker model decides which to pull mid-reasoning,
 *    so we stop guessing what to pre-inject.
 *
 * `web_lookup` is the ONLY way these workers reach the internet: a nested
 * one-shot search-grounded [SubAgent.ask], because google_search cannot be mixed
 * with function declarations in one request on Flash-class models.
 */
object CarToolbelt {

    // --- Formatters ---------------------------------------------------------

    /**
     * Trend over recorded obd_samples: count, min/max/avg, and a first-half vs
     * second-half comparison. Temperatures are converted to Fahrenheit. This is
     * the aggregation the Live `get_trend` tool delegates to.
     */
    suspend fun trendSummary(context: Context, metric: String, days: Int): String {
        val d = days.coerceIn(1, 365)
        val pid = when (metric) {
            "coolant" -> "0105"; "rpm" -> "010C"; "voltage" -> "ATRV"
            "load" -> "0104"; "fuel_trim" -> "0107"; "mpg" -> "MPG_TRIP"
            else -> return "Unknown metric '$metric'."
        }
        val vehicle = VehicleController.currentVehicle(context)
        val now = System.currentTimeMillis()
        val samples = CarDatabase.getDatabase(context).odbSampleDao()
            .getRange(vehicle.obdMac, pid, now - d * 86_400_000L, now)
        if (samples.size < 5) return "Not enough history yet for $metric - keep driving and it builds up."

        val isTemp = pid == "0105"
        fun conv(v: Double) = if (isTemp) v * 9 / 5 + 32 else v
        val values = samples.map { conv(it.value) }
        val unit = if (isTemp) "F" else samples.first().unit
        val half = values.size / 2
        val earlier = values.take(half).average()
        val recent = values.drop(half).average()
        fun f(v: Double) = if (v >= 100) "%.0f".format(v) else "%.1f".format(v)

        return "$metric over $d days: ${values.size} samples, avg ${f(values.average())}$unit " +
            "(min ${f(values.min())}, max ${f(values.max())}). " +
            "Recent average ${f(recent)}$unit vs ${f(earlier)}$unit earlier."
    }

    /**
     * Listening-history taste summary over recorded [MusicPlay] rows: top artists
     * and tracks by completed plays, overall skip rate, and a night-vs-day split.
     * This is the aggregation the Live `get_music_taste` tool delegates to.
     */
    suspend fun musicTasteSummary(context: Context, days: Int): String {
        val d = days.coerceIn(1, 730)
        val now = System.currentTimeMillis()
        val plays = CarDatabase.getDatabase(context).musicPlayDao().getSince(now - d * 86_400_000L)
        if (plays.size < 8) return "Not enough listening history yet - keep playing music and it builds up."

        val completed = plays.filter { it.completed }
        val skipRate = ((plays.size - completed.size).toDouble() / plays.size * 100).roundToInt()

        fun topBy(selector: (MusicPlay) -> String, limit: Int) =
            completed.groupBy(selector).entries
                .sortedByDescending { it.value.size }
                .take(limit)
                .map { it.key to it.value.size }

        val topArtists = topBy({ it.artist.ifBlank { "Unknown artist" } }, 5)
        val topTracks = topBy({ "${it.title} - ${it.artist.ifBlank { "Unknown artist" }}" }, 5)

        val cal = java.util.Calendar.getInstance()
        fun isNight(ts: Long): Boolean {
            cal.timeInMillis = ts
            val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
            return hour >= 20 || hour < 5
        }
        val nightCount = plays.count { isNight(it.timestamp) }
        val nightPct = (nightCount.toDouble() / plays.size * 100).roundToInt()

        return buildString {
            append("$d-day listening: ${plays.size} tracks played, $skipRate% skipped.\n")
            if (topArtists.isNotEmpty()) {
                append("Top artists: ${topArtists.joinToString(", ") { (a, n) -> "$a ($n)" }}.\n")
            }
            if (topTracks.isNotEmpty()) {
                append("Top tracks: ${topTracks.joinToString(", ") { (t, n) -> "$t ($n)" }}.\n")
            }
            append("$nightPct% of plays happen at night (8pm-5am).")
        }
    }

    /**
     * What's already saved on the head unit: the general library ("the drawer")
     * plus the driver's assembled mixtapes. Pre-seeded into MusicAgent's prompt
     * so it never recommends something already sitting on the car.
     */
    suspend fun savedMusicSummary(context: Context): String {
        val db = CarDatabase.getDatabase(context)
        val tracks = db.libraryTrackDao().getAll()
        val tapes = db.mixtapeDao().getAll()
        return formatSavedMusic(tracks, tapes)
    }

    /** Pure formatting half of [savedMusicSummary], split out for unit testing. */
    fun formatSavedMusic(tracks: List<LibraryTrack>, tapes: List<Mixtape>): String {
        if (tracks.isEmpty() && tapes.isEmpty()) return "Nothing saved on the head unit yet."
        return buildString {
            if (tracks.isNotEmpty()) {
                val artists = tracks.map { it.artist.ifBlank { "Unknown artist" } }.distinct()
                append("${tracks.size} track(s) saved on the head unit from ${artists.size} artist(s): ")
                append(artists.take(10).joinToString(", "))
                if (artists.size > 10) append(", ...")
                append(".\n")
                val albums = tracks.map { it.album }.filter { it.isNotBlank() }.distinct()
                if (albums.isNotEmpty()) {
                    append("Albums: ").append(albums.take(8).joinToString(", "))
                    if (albums.size > 8) append(", ...")
                    append(".\n")
                }
            } else {
                append("No individual tracks saved yet.\n")
            }
            if (tapes.isNotEmpty()) {
                append("Saved mixtapes: ").append(tapes.joinToString(", ") { it.name }).append(".")
            } else {
                append("No mixtapes assembled yet.")
            }
        }
    }

    /** Recent DTC events with the freeze-frame highlights latched at trip time. */
    suspend fun codeHistory(context: Context, limit: Int = 5): String {
        val vehicle = VehicleController.currentVehicle(context)
        val events = CarDatabase.getDatabase(context).codeEventDao().getAll(vehicle.obdMac)
        if (events.isEmpty()) return "No trouble-code events recorded."
        return events.sortedByDescending { it.timestamp }.take(limit).joinToString("\n") { e ->
            buildString {
                append(shortDate(e.timestamp)).append(": ").append(codesOf(e.codesJson))
                e.mileage?.let { append(" at ${"%,d".format(it)} mi") }
                val ff = freezeHighlights(e.freezeFrameJson)
                if (ff.isNotBlank()) append(" [").append(ff).append("]")
            }
        }
    }

    /** Recent service records (newest first). */
    suspend fun serviceHistory(context: Context, limit: Int = 10): String {
        val vehicle = VehicleController.currentVehicle(context)
        val recs = CarDatabase.getDatabase(context).serviceRecordDao()
            .getRecentForVehicle(vehicle.obdMac, limit)
        if (recs.isEmpty()) return "No service history logged yet."
        return recs.joinToString("\n") { r ->
            buildString {
                append(shortDate(r.date)).append(": ").append(r.serviceName)
                append(" at ${"%,d".format(r.mileage)} mi")
                r.cost?.let { append(" ($").append("%.0f".format(it)).append(")") }
            }
        }
    }

    /** Maintenance schedule with current mileage and which items read as due. */
    suspend fun maintenanceSchedule(context: Context): String {
        val vehicle = VehicleController.currentVehicle(context)
        val items = CarDatabase.getDatabase(context).maintenanceItemDao().getForVehicle(vehicle.obdMac)
        val mileage = VehicleController.currentMileage(vehicle)
        if (items.isEmpty()) {
            return "No maintenance schedule on file yet. Odometer about ${"%,d".format(mileage)} mi (estimated)."
        }
        val due = VehicleController.dueItems(context, vehicle).map { it.serviceName }.toSet()
        return buildString {
            append("Odometer about ${"%,d".format(mileage)} mi (estimated).\n")
            append(items.joinToString("\n") { item ->
                val interval = listOfNotNull(
                    item.intervalMiles?.let { "every ${"%,d".format(it)} mi" },
                    item.intervalMonths?.let { "every $it mo" },
                ).joinToString(" / ").ifBlank { "no interval" }
                val last = listOfNotNull(
                    item.lastDoneMileage?.let { "at ${"%,d".format(it)} mi" },
                    item.lastDoneDate?.let { "on ${shortDate(it)}" },
                ).joinToString(" ").ifBlank { "never logged" }
                val flag = if (item.serviceName in due) " - DUE" else ""
                "- ${item.serviceName}: $interval; last $last$flag"
            })
        }
    }

    /**
     * The recorded cold-start bursts: the newest sample-by-sample, plus a one-line
     * summary per earlier start. Read by the Live `check_cold_start` tool and
     * pre-seeded into ColdStartAgent's one-shot prompt.
     */
    suspend fun coldStartReport(context: Context, limit: Int = 5): String {
        val vehicle = VehicleController.currentVehicle(context)
        val dao = CarDatabase.getDatabase(context).odbSampleDao()
        val markers = dao.getLatest(vehicle.obdMac, "COLD_START", limit)
        if (markers.isEmpty()) {
            return "No cold start captured yet - the first minute after a cold morning start hasn't been recorded."
        }
        val burstPids = listOf("010C" to "rpm", "0105" to "coolant_c", "0106" to "stft", "0107" to "ltft", "010F" to "iat_c")
        suspend fun burstLines(markerTs: Long): String {
            val byTime = sortedMapOf<Long, MutableList<String>>()
            for ((pid, lbl) in burstPids) {
                for (s in dao.getRange(vehicle.obdMac, pid, markerTs, markerTs + 90_000)) {
                    byTime.getOrPut(s.timestamp) { mutableListOf() }
                        .add("$lbl ${if (s.value % 1.0 == 0.0) s.value.toInt().toString() else "%.1f".format(s.value)}")
                }
            }
            return byTime.entries.joinToString("\n") { (ts, vals) ->
                "t+${(ts - markerTs) / 1000}s: ${vals.joinToString(", ")}"
            }
        }
        val newest = markers.first()
        return buildString {
            append("Latest cold start, started at ${newest.value.toInt()}C coolant:\n")
            append(burstLines(newest.timestamp))
            val priors = markers.drop(1)
            if (priors.isNotEmpty()) {
                append("\n\nEarlier cold starts:\n")
                val priorLines = mutableListOf<String>()
                for (m in priors) {
                    val rpms = dao.getRange(vehicle.obdMac, "010C", m.timestamp, m.timestamp + 90_000).map { it.value }
                    val stfts = dao.getRange(vehicle.obdMac, "0106", m.timestamp, m.timestamp + 90_000).map { it.value }
                    priorLines.add(buildString {
                        append("Start at ${m.value.toInt()}C")
                        if (rpms.isNotEmpty()) append(", avg rpm ${rpms.average().toInt()}")
                        if (stfts.isNotEmpty()) append(", avg stft ${"%.1f".format(stfts.average())}%")
                    })
                }
                append(priorLines.joinToString("\n"))
            }
        }
    }

    /** Live sensor read for the requested items (comma vocabulary in the tool desc). */
    suspend fun liveSnapshot(items: List<String>): String {
        if (!ObdBluetoothManager.isConnected) return "OBD not connected."
        val wanted = if (items.isEmpty()) listOf("rpm", "coolant", "voltage") else items
        val out = mutableListOf<String>()
        for (item in wanted) {
            when (item.lowercase()) {
                "rpm" -> ObdBluetoothManager.getRpm()?.let { out.add("rpm $it") }
                "coolant" -> ObdBluetoothManager.getCoolantTemp()?.let { out.add("coolant ${it * 9 / 5 + 32}F") }
                "voltage" -> ObdBluetoothManager.getBatteryVoltage()?.let { out.add("battery ${"%.1f".format(it)}V") }
                "load" -> ObdBluetoothManager.getEngineLoad()?.let { out.add("load ${"%.0f".format(it)}%") }
                "stft" -> ObdBluetoothManager.getShortFuelTrim()?.let { out.add("stft ${"%+.1f".format(it)}%") }
                "ltft" -> ObdBluetoothManager.getLongFuelTrim()?.let { out.add("ltft ${"%+.1f".format(it)}%") }
                "iat" -> ObdBluetoothManager.getIntakeAirTemp()?.let { out.add("iat ${it * 9 / 5 + 32}F") }
                "maf" -> ObdBluetoothManager.getMaf()?.let { out.add("maf ${"%.1f".format(it)}g/s") }
                "speed" -> ObdBluetoothManager.getSpeedKmh()?.let { out.add("speed ${(it * 0.621371).roundToInt()}mph") }
                "fuel_level" -> ObdBluetoothManager.getFuelLevel()?.let { out.add("fuel ${"%.0f".format(it)}%") }
            }
        }
        return if (out.isEmpty()) "No readings returned (engine off, or those PIDs unsupported)." else out.joinToString(", ")
    }

    /** Emissions readiness in plain language (inspection guidance). */
    suspend fun readinessReport(): String {
        if (!ObdBluetoothManager.isConnected) return "OBD not connected."
        val r = ObdBluetoothManager.getReadiness() ?: return "The car didn't answer the readiness request."
        val incomplete = r.monitors.filter { !it.complete }.map { it.name }
        return buildString {
            append(if (r.milOn) "Check-engine light is ON" else "Check-engine light is off")
            if (r.dtcCount > 0) append(" with ${r.dtcCount} stored code(s)")
            append(". ${r.monitors.count { it.complete }} of ${r.monitors.size} monitors complete.")
            if (incomplete.isNotEmpty()) {
                append(" Not ready yet: ${incomplete.joinToString(", ")} - drive a normal mixed cycle first.")
            } else {
                append(" All ready.")
            }
        }
    }

    /** Decoded factory specs plus any driver-entered build notes. */
    suspend fun specsSummary(context: Context): String {
        val spec = VehicleSpecController.current(context) ?: return "No decoded specs on file (VIN not read yet)."
        val parts = listOfNotNull(
            spec.displacementL?.let { "${it}L" },
            spec.engineConfig.ifBlank { null },
            spec.engineCylinders?.let { "$it-cyl" },
            spec.engineHp?.let { "$it hp" },
            spec.fuelType.ifBlank { null },
            spec.transmissionStyle.ifBlank { null },
            spec.driveType.ifBlank { null },
            spec.paintColor.ifBlank { null }?.let { "paint $it" },
            spec.buildNotes.ifBlank { null },
        )
        return if (parts.isEmpty()) "Specs row exists but is empty." else parts.joinToString(", ")
    }

    /** Known chassis quirks for this car (empty until the quirk index is bundled). */
    suspend fun quirksList(context: Context): String {
        val dao = CarDatabase.getDatabase(context).chassisQuirkDao()
        if (dao.count() == 0) return "No quirk index loaded yet."
        val vehicle = VehicleController.currentVehicle(context)
        val candidates = listOf(vehicle.model, vehicle.trim)
            .flatMap { it.split(" ") }.map { it.uppercase() }.filter { it.isNotBlank() }.distinct()
        val quirks = candidates.flatMap { dao.getForChassis(it) }.distinctBy { it.quirkId }
        if (quirks.isEmpty()) return "No known quirks on file for this chassis yet."
        return quirks.joinToString("\n") { q ->
            buildString {
                append(q.title).append(" (${q.severity})")
                if (q.mileageLow >= 0 || q.mileageHigh >= 0) {
                    val lo = if (q.mileageLow >= 0) "%,d".format(q.mileageLow) else "?"
                    val hi = if (q.mileageHigh >= 0) "%,d".format(q.mileageHigh) else "?"
                    append(" ~$lo-$hi mi")
                }
                append(": ").append(q.symptom)
            }
        }
    }

    /** Recent used-oil analyses with the wear metals that matter. */
    suspend fun oilAnalyses(context: Context, limit: Int = 3): String {
        val vehicle = VehicleController.currentVehicle(context)
        val all = CarDatabase.getDatabase(context).oilAnalysisDao().getAll(vehicle.obdMac)
        if (all.isEmpty()) return "No oil analyses recorded yet."
        return all.sortedByDescending { it.date }.take(limit).joinToString("\n") { a ->
            buildString {
                append(shortDate(a.date))
                a.mileage?.let { append(" at ${"%,d".format(it)} mi") }
                append(":")
                a.oilGrade.ifBlank { null }?.let { append(" $it") }
                val metrics = listOfNotNull(
                    a.iron?.let { "Fe ${it}ppm" },
                    a.copper?.let { "Cu ${it}ppm" },
                    a.lead?.let { "Pb ${it}ppm" },
                    a.aluminum?.let { "Al ${it}ppm" },
                    a.silicon?.let { "Si ${it}ppm" },
                    a.fuelPercent?.let { "fuel $it%" },
                    a.tbn?.let { "TBN $it" },
                    a.viscosityCst?.let { "visc $it cSt" },
                )
                if (metrics.isNotEmpty()) append(" ").append(metrics.joinToString(", "))
                a.labNotes.ifBlank { null }?.let { append(" - $it") }
            }
        }
    }

    // --- Belt builders ------------------------------------------------------

    fun forDiagnostics(context: Context): List<AgentTool> = listOf(
        codeHistoryTool(context), liveDataTool(), trendTool(context),
        readinessTool(), specsTool(context), quirksTool(context), webLookupTool(),
    )

    fun forSymptoms(context: Context): List<AgentTool> = forDiagnostics(context) + serviceHistoryTool(context)

    // MaintenanceAgent pre-seeds the schedule into its context, so the belt omits
    // get_maintenance_schedule - the worker would only burn a round re-pulling it.
    fun forMaintenance(context: Context): List<AgentTool> = listOf(
        serviceHistoryTool(context), trendTool(context),
        codeHistoryTool(context), oilAnalysesTool(context), webLookupTool(),
    )

    // ColdStartAgent and MusicAgent are one-shot (askTyped), not investigate
    // loops, so they no longer need belts - each pre-assembles its data
    // (coldStartReport / musicTasteSummary + savedMusicSummary) into the prompt
    // and answers in a single round. Their old belts + the tools exclusive to
    // them (get_cold_starts, get_music_taste, list_saved_music) were removed.

    // --- Tool factories -----------------------------------------------------

    private fun trendTool(context: Context) = AgentTool(
        name = "get_trend",
        description = "How a recorded metric has trended over recent weeks (coolant, rpm, voltage, " +
            "load, fuel_trim, mpg): count, min/max/average, and recent-vs-earlier comparison. Use to " +
            "judge whether something is drifting.",
        params = props(
            "metric" to prop("string", "Which metric.", listOf("coolant", "rpm", "voltage", "load", "fuel_trim", "mpg")),
            "days" to prop("integer", "How many days back to look. Default 30."),
        ),
        required = listOf("metric"),
        timeoutMs = 5_000,
    ) { args -> trendSummary(context, args.optString("metric"), args.optInt("days", 30)) }

    private fun codeHistoryTool(context: Context) = AgentTool(
        name = "get_code_history",
        description = "Past trouble-code events with the sensor snapshot latched when each code set " +
            "(rpm, coolant, load at trip time). Use to spot patterns like a code that only sets cold.",
        params = props("limit" to prop("integer", "How many recent events. Default 5.")),
        timeoutMs = 5_000,
    ) { args -> codeHistory(context, args.optInt("limit", 5)) }

    private fun serviceHistoryTool(context: Context) = AgentTool(
        name = "get_service_history",
        description = "Logged maintenance history for this car (dates, service, mileage, cost). Use to " +
            "see what has actually been done and when.",
        params = props("limit" to prop("integer", "How many recent records. Default 10.")),
        timeoutMs = 5_000,
    ) { args -> serviceHistory(context, args.optInt("limit", 10)) }

    private fun liveDataTool() = AgentTool(
        name = "read_live_data",
        description = "Read live sensor values off the OBD port right now. items is a comma list from: " +
            "rpm, coolant, voltage, load, stft, ltft, iat, maf, speed, fuel_level. Returns 'OBD not " +
            "connected' if the adapter is unplugged.",
        params = props("items" to prop("string", "Comma-separated sensor names to read.")),
        required = listOf("items"),
        timeoutMs = 12_000,
    ) { args ->
        liveSnapshot(args.optString("items").split(",").map { it.trim() }.filter { it.isNotBlank() })
    }

    private fun readinessTool() = AgentTool(
        name = "get_readiness",
        description = "Live emissions-readiness read: MIL state, stored-code count, and which self-test " +
            "monitors are complete vs. still need drive time.",
        timeoutMs = 8_000,
    ) { readinessReport() }

    private fun specsTool(context: Context) = AgentTool(
        name = "get_specs",
        description = "Decoded factory specs for this car (engine, drivetrain, fuel) plus any driver " +
            "build notes. Use when the answer depends on the exact powertrain.",
        timeoutMs = 5_000,
    ) { specsSummary(context) }

    private fun quirksTool(context: Context) = AgentTool(
        name = "get_quirks",
        description = "Known chassis quirks and common failure points for this platform, with typical " +
            "onset mileage and severity. Use to weight a diagnosis toward model-specific weak spots.",
        timeoutMs = 5_000,
    ) { quirksList(context) }

    private fun oilAnalysesTool(context: Context) = AgentTool(
        name = "get_oil_analyses",
        description = "Recent used-oil analysis results (wear metals in ppm, fuel dilution, TBN, " +
            "viscosity). Use to reason about internal engine wear trends.",
        params = props("limit" to prop("integer", "How many recent analyses. Default 3.")),
        timeoutMs = 5_000,
    ) { args -> oilAnalyses(context, args.optInt("limit", 3)) }

    private fun webLookupTool() = AgentTool(
        name = "web_lookup",
        description = "Look something up on the web (model-specific failure patterns, procedures, " +
            "part numbers, torque specs). Use only when this car's own recorded data can't answer it.",
        params = props("query" to prop("string", "What to look up.")),
        required = listOf("query"),
        timeoutMs = 20_000,
    ) { args ->
        val q = args.optString("query")
        if (q.isBlank()) "No query given."
        else webAgent.ask("", q) ?: "Web lookup came back empty or unreachable."
    }

    private val webAgent by lazy {
        SubAgent(
            systemInstruction = "Answer factually and compactly from search. A few sentences, plain text.",
            useSearch = true,
        )
    }

    // --- schema helpers -----------------------------------------------------

    private fun props(vararg pairs: Pair<String, JSONObject>): JSONObject =
        JSONObject().apply { for ((k, v) in pairs) put(k, v) }

    private fun prop(type: String, description: String, enum: List<String>? = null): JSONObject =
        JSONObject().put("type", type).put("description", description)
            .apply { if (enum != null) put("enum", JSONArray(enum)) }

    private fun codesOf(codesJson: String): String = try {
        val a = JSONArray(codesJson)
        (0 until a.length()).joinToString(", ") { a.getString(it) }
    } catch (e: Exception) {
        codesJson
    }

    private fun freezeHighlights(json: String): String {
        if (json.isBlank()) return ""
        return try {
            val o = JSONObject(json)
            listOfNotNull(
                if (o.has("rpm")) "rpm ${o.getDouble("rpm").toInt()}" else null,
                if (o.has("coolant_c")) "coolant ${(o.getDouble("coolant_c") * 9 / 5 + 32).toInt()}F" else null,
                if (o.has("load_pct")) "load ${o.getDouble("load_pct").toInt()}%" else null,
            ).joinToString(" ")
        } catch (e: Exception) {
            ""
        }
    }
}
