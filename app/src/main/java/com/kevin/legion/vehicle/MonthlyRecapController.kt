package com.kevin.legion.vehicle

import android.content.Context
import android.util.Log
import com.kevin.legion.ai.AssistantIdentity
import com.kevin.legion.ai.SubAgent
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.MonthlyRecap
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Generates the monthly recap cassette (E5): aggregates a calendar month's
 * driving into stats, asks Gemini for a short first-person narrative (Side
 * B, in the same car-self voice as C1), generates a themed cover scene
 * (Side A - stats render as real UI text over it in the viewer, never baked
 * into the art), and saves the result. [generateIfDue] is checked on a
 * periodic loop from AriaForegroundService shortly after a month rolls over;
 * idempotent per vehicle+month via the getForMonth existence check below.
 *
 * The shelf/archive UI to browse past recaps (Kevin's "bookshelf, spine-out,
 * special color for unusual months" design vision) is a separate follow-up -
 * this is the generation pipeline + a single view-latest screen only.
 */
object MonthlyRecapController {
    private const val TAG = "MonthlyRecapController"

    // Only auto-generate within the first few days after a month rolls over -
    // if the app wasn't running near a rollover, this deliberately doesn't
    // try to backfill every missed month, just picks up the most recent one.
    private const val GRACE_DAYS = 5

    // "Notable month" heuristics feeding the future shelf UI's special spine
    // color - not used for anything else yet in this pass.
    private const val NOTABLE_LONG_DRIVE_MILES = 200.0
    private const val NOTABLE_CODE_COUNT = 3

    // Car profiles (2026-07-16): the driver's picked car, falling back to the
    // connected dongle. NOT the dongle MAC directly any more - one dongle moved
    // between two cars used to make them the same vehicle. See ActiveVehicle.
    private fun vehicleId(context: Context): String = ActiveVehicle.current(context)

    /**
     * Generates last month's recap if it doesn't already exist and we're
     * still in the grace window. [vehicleId] is the fleet-wide-voice override
     * (ticket 01 §2's literal controller-threading instruction) - null means
     * the active car, unchanged. Not currently exercised: called from the
     * service's own periodic loop, never from a Live tool.
     */
    suspend fun generateIfDue(context: Context, vehicleIdOverride: String? = null) {
        val now = Calendar.getInstance()
        if (now.get(Calendar.DAY_OF_MONTH) > GRACE_DAYS) return

        val cal = Calendar.getInstance().apply { add(Calendar.MONTH, -1) }
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1 // Calendar months are 0-based

        val id = vehicleIdOverride ?: vehicleId(context)
        val dao = CarDatabase.getDatabase(context).monthlyRecapDao()
        if (dao.getForMonth(id, year, month) != null) return

        runCatching { generate(context, id, year, month) }
            .onFailure { Log.w(TAG, "Monthly recap generation failed: ${it.message}") }
    }

    /**
     * Debug-only: fabricates a plausible recap for a random month in the past
     * year and saves it, bypassing the real DB aggregation entirely - lets
     * the shelf/detail UI be previewed without waiting a month for real data
     * (2026-07-08, Kevin's request after E5's shelf UI shipped with nothing
     * to show it). Reuses the exact same narrative/cover generation as
     * [generate] - only the *stats* are fabricated, not the Gemini calls -
     * so what's on screen looks like a real recap, not placeholder art.
     * Doesn't check for an existing recap that month (unlike [generateIfDue]);
     * repeated taps just add more spines to the shelf, which is fine for
     * previewing.
     */
    suspend fun generateFake(context: Context, vehicleId: String): MonthlyRecap {
        val cal = Calendar.getInstance().apply { add(Calendar.MONTH, -(0..11).random()) }
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1

        val notable = listOf(true, false, false).random() // skew toward plain months, like real data would
        val milesDriven = (200..1200).random().toDouble()
        val driveCount = (10..40).random()
        val avgMpg = (16..30).random().toDouble()
        val longestDrive = if (notable) (200..450).random().toDouble() else (20..150).random().toDouble()
        val codeCount = if (notable && listOf(true, false).random()) (3..6).random() else (0..1).random()
        val serviceCount = (0..3).random()
        val notableReason = when {
            longestDrive >= NOTABLE_LONG_DRIVE_MILES -> "longest drive: ${longestDrive.toInt()} mi"
            codeCount >= NOTABLE_CODE_COUNT -> "$codeCount trouble codes this month"
            else -> null
        }

        val vehicle = VehicleController.currentVehicle(context)
        val (fromMs, _) = monthRange(year, month)
        val monthName = SimpleDateFormat("MMMM", Locale.getDefault()).format(Date(fromMs))

        val narrative = generateNarrative(
            context = context, monthName = monthName, milesDriven = milesDriven, avgMpg = avgMpg,
            driveCount = driveCount, longestDrive = longestDrive,
            codeCount = codeCount, serviceCount = serviceCount,
        )
        val coverPath = runCatching {
            generateCover(context, vehicleId, vehicle, monthName, year, month)
        }.getOrNull()

        val recap = MonthlyRecap(
            vehicleId = vehicleId, year = year, month = month,
            generatedAt = System.currentTimeMillis(),
            milesDriven = milesDriven, avgMpg = avgMpg, driveCount = driveCount,
            longestDriveMiles = longestDrive, codeEventCount = codeCount,
            serviceCount = serviceCount, narrative = narrative, coverImagePath = coverPath,
            notable = notableReason != null, notableReason = notableReason,
        )
        CarDatabase.getDatabase(context).monthlyRecapDao().insert(recap)
        return recap
    }

    /** Generates and saves the recap for [vehicleId]/[year]/[month] unconditionally. */
    suspend fun generate(context: Context, vehicleId: String, year: Int, month: Int): MonthlyRecap {
        val (fromMs, toMs) = monthRange(year, month)
        val db = CarDatabase.getDatabase(context)

        val tripMiles = db.odbSampleDao().getRange(vehicleId, "TRIP_MILES", fromMs, toMs)
        val mpgSamples = db.odbSampleDao().getRange(vehicleId, "MPG_TRIP", fromMs, toMs)
        val milesDriven = tripMiles.sumOf { it.value }
        val driveCount = tripMiles.size
        val longestDrive = tripMiles.maxOfOrNull { it.value }
        val avgMpg = mpgSamples.takeIf { it.isNotEmpty() }?.let { s -> s.sumOf { it.value } / s.size }
        val codeCount = db.codeEventDao().countInRange(vehicleId, fromMs, toMs)
        // GAP CLOSED (engine retirement step 3, ticket 16, 2026-08-27): cutover 4 left this reading
        // the legacy service_records table while FleetEngineStore.insertObserved wrote only to the
        // engine, so a service logged after that cutover under-counted here - a named, accepted gap
        // at the time (see git history for the original comment). FleetEngineStore.insertObserved
        // now writes service_records directly again (kind = 'OBSERVED'), and countInRange filters
        // to that kind - see [ServiceRecordDao.countInRange]'s own doc for why an ASSERTED anchor
        // must not count as a service performed. Regression: MonthlyRecapServiceCountTest.
        val serviceCount = db.serviceRecordDao().countInRange(vehicleId, fromMs, toMs)

        val notableReason = when {
            (longestDrive ?: 0.0) >= NOTABLE_LONG_DRIVE_MILES -> "longest drive: ${longestDrive!!.toInt()} mi"
            codeCount >= NOTABLE_CODE_COUNT -> "$codeCount trouble codes this month"
            else -> null
        }

        val vehicle = VehicleController.currentVehicle(context)
        val monthName = SimpleDateFormat("MMMM", Locale.getDefault()).format(Date(fromMs))

        val narrative = generateNarrative(
            context = context, monthName = monthName, milesDriven = milesDriven, avgMpg = avgMpg,
            driveCount = driveCount, longestDrive = longestDrive,
            codeCount = codeCount, serviceCount = serviceCount,
        )

        val coverPath = runCatching {
            generateCover(context, vehicleId, vehicle, monthName, year, month)
        }.getOrNull()

        val recap = MonthlyRecap(
            vehicleId = vehicleId, year = year, month = month,
            generatedAt = System.currentTimeMillis(),
            milesDriven = milesDriven, avgMpg = avgMpg, driveCount = driveCount,
            longestDriveMiles = longestDrive, codeEventCount = codeCount,
            serviceCount = serviceCount, narrative = narrative, coverImagePath = coverPath,
            notable = notableReason != null, notableReason = notableReason,
        )
        db.monthlyRecapDao().insert(recap)
        return recap
    }

    private suspend fun generateNarrative(
        context: Context,
        monthName: String,
        milesDriven: Double,
        avgMpg: Double?,
        driveCount: Int,
        longestDrive: Double?,
        codeCount: Int,
        serviceCount: Int,
    ): String {
        val fallback = "Another month on the road together - $driveCount drives, ${milesDriven.toInt()} miles."
        val stats = buildString {
            append("Miles driven: ${milesDriven.toInt()}. ")
            append("Drives: $driveCount. ")
            // Withheld from the PROMPT, not just the UI - see DailyDriveLogController.generateNarrative's
            // doc (ticket 09, `.scratch/drive-ui/issues/09-mpg-scale-bug.md`): a stat handed to Gemini
            // can resurface paraphrased into the narrative it writes, which every UI-layer suppression
            // elsewhere in this app would miss once it's baked into stored, freeform narrative text.
            if (MpgTrust.SHOW_MPG) avgMpg?.let { append("Average MPG: ${"%.1f".format(it)}. ") }
            longestDrive?.let { append("Longest single drive: ${it.toInt()} miles. ") }
            append("Trouble codes seen: $codeCount. ")
            // GAP CLOSED (engine retirement step 3, ticket 16, 2026-08-27): service_records has a
            // real writer again (FleetEngineStore.insertObserved) - see [generate]'s own comment on
            // [serviceCount]. The stale caveat below is removed rather than left inert, per this
            // function's own doc on why a withheld/mis-stated stat can resurface paraphrased in the
            // narrative Gemini writes from it - a caveat that is no longer true is exactly as
            // dangerous there as a stat that was never withheld.
            append("Services logged: $serviceCount.")
        }
        // Identity from AssistantIdentity, deliberately self-contained rather than
        // injecting the full CompanionProfile persona, matching the other
        // sub-agents - only the speaker's stance is shared, not the character.
        val system = AssistantIdentity.shortClause(context) + " " +
            "You are looking back at $monthName with the user. Given the month's stats, write " +
            "2-4 short, warm, spoken-natural sentences reflecting on the month - notice something " +
            "real in the numbers (a long drive, a quiet month, a rough patch with codes), don't " +
            "just restate them as a list. Plain text only, no markdown, no lists."
        val agent = SubAgent(systemInstruction = system, useSearch = false)
        return agent.ask(stats, "Write the recap.") ?: fallback
    }

    /**
     * Cover art generation is retired for now: it depended on `AvatarStudio`
     * (city-pop art direction, retired in the 2026-07-31 pivot) and no
     * replacement image-gen path has been decided. Always returns null;
     * `coverImagePath` stays a valid nullable column, just unpopulated.
     */
    private suspend fun generateCover(
        context: Context,
        vehicleId: String,
        vehicle: com.kevin.legion.data.local.Vehicle,
        monthName: String,
        year: Int,
        month: Int,
    ): String? = null

    private fun monthRange(year: Int, month: Int): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(year, month - 1, 1, 0, 0, 0)
        val from = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        val to = cal.timeInMillis - 1
        return from to to
    }
}
