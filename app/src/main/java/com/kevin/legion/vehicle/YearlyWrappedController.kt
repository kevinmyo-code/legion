package com.kevin.legion.vehicle

import android.content.Context
import com.kevin.legion.ai.AssistantIdentity
import com.kevin.legion.ai.SubAgent
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.YearlyWrapped

/**
 * Yearly Wrapped (CLAUDE.md Sprint 6) - aggregated from a year's
 * [com.kevin.legion.data.local.MonthlyRecap] rows. Originally shipped as
 * an ephemeral, non-persisted data class (2026-07-08) - that meant a
 * generated Wrapped never showed up on the shelf, since the shelf only
 * lists rows actually saved to the database. Fixed the same day: promoted
 * to a real Room entity and both [aggregate] and [generateFake] now save
 * their result.
 */
object YearlyWrappedController {
    // Car profiles (2026-07-16): the driver's picked car, falling back to the
    // connected dongle. NOT the dongle MAC directly any more - one dongle moved
    // between two cars used to make them the same vehicle. See ActiveVehicle.
    private fun vehicleId(context: Context): String = ActiveVehicle.current(context)

    /** Aggregates [year]'s monthly recaps into a Wrapped summary and saves it, or null if no monthly recaps exist yet. */
    suspend fun aggregate(context: Context, vehicleId: String, year: Int): YearlyWrapped? {
        val months = CarDatabase.getDatabase(context).monthlyRecapDao().getAll(vehicleId)
            .filter { it.year == year }
        if (months.isEmpty()) return null

        val milesDriven = months.sumOf { it.milesDriven }
        val driveCount = months.sumOf { it.driveCount }
        val avgMpg = months.mapNotNull { it.avgMpg }.takeIf { it.isNotEmpty() }?.average()
        val longestDrive = months.mapNotNull { it.longestDriveMiles }.maxOrNull()
        val notableMonths = months.count { it.notable }
        val codeCount = months.sumOf { it.codeEventCount }
        val serviceCount = months.sumOf { it.serviceCount }

        val vehicle = VehicleController.currentVehicle(context)
        val narrative = generateNarrative(context, year, milesDriven, driveCount, avgMpg, longestDrive, notableMonths, codeCount, serviceCount)
        val coverPath = runCatching { generateCover(context, vehicleId, vehicle, year) }.getOrNull()

        val wrapped = YearlyWrapped(
            vehicleId = vehicleId, year = year, generatedAt = System.currentTimeMillis(),
            milesDriven = milesDriven, driveCount = driveCount, avgMpg = avgMpg,
            longestDriveMiles = longestDrive, notableMonths = notableMonths,
            codeEventCount = codeCount, serviceCount = serviceCount,
            narrative = narrative, coverImagePath = coverPath,
        )
        CarDatabase.getDatabase(context).yearlyWrappedDao().insert(wrapped)
        return wrapped
    }

    /**
     * Debug-only: fabricates a plausible year of stats directly, without
     * depending on 12 real (or fake) MonthlyRecap rows existing first -
     * lets the Wrapped view be previewed immediately (2026-07-08, Kevin's
     * request alongside the monthly fake-recap generator). Saves the result
     * like [aggregate] does, so it shows up on the shelf.
     */
    /**
     * [vehicleIdOverride] is the fleet-wide-voice override (ticket 01 §2's
     * literal controller-threading instruction) - null means the active car,
     * unchanged. Not currently exercised: this is a debug-only fake-data
     * generator with no Live tool call site.
     */
    suspend fun generateFake(context: Context, year: Int, vehicleIdOverride: String? = null): YearlyWrapped {
        val notableMonths = (0..4).random()
        val milesDriven = (4000..14000).random().toDouble()
        val driveCount = (80..300).random()
        val avgMpg = (16..30).random().toDouble()
        val longestDrive = (150..500).random().toDouble()
        val codeCount = (0..10).random()
        val serviceCount = (2..8).random()

        val vehicleId = vehicleIdOverride ?: vehicleId(context)
        val vehicle = VehicleController.currentVehicle(context)
        val narrative = generateNarrative(context, year, milesDriven, driveCount, avgMpg, longestDrive, notableMonths, codeCount, serviceCount)
        val coverPath = runCatching { generateCover(context, vehicleId, vehicle, year) }.getOrNull()

        val wrapped = YearlyWrapped(
            vehicleId = vehicleId, year = year, generatedAt = System.currentTimeMillis(),
            milesDriven = milesDriven, driveCount = driveCount, avgMpg = avgMpg,
            longestDriveMiles = longestDrive, notableMonths = notableMonths,
            codeEventCount = codeCount, serviceCount = serviceCount,
            narrative = narrative, coverImagePath = coverPath,
        )
        CarDatabase.getDatabase(context).yearlyWrappedDao().insert(wrapped)
        return wrapped
    }

    private suspend fun generateNarrative(
        context: Context,
        year: Int,
        milesDriven: Double,
        driveCount: Int,
        avgMpg: Double?,
        longestDrive: Double?,
        notableMonths: Int,
        codeCount: Int,
        serviceCount: Int,
    ): String {
        val fallback = "A whole year on the road together - $driveCount drives, ${milesDriven.toInt()} miles."
        val stats = buildString {
            append("Year: $year. ")
            append("Total miles driven: ${milesDriven.toInt()}. ")
            append("Total drives: $driveCount. ")
            avgMpg?.let { append("Average MPG across the year: ${"%.1f".format(it)}. ") }
            longestDrive?.let { append("Longest single drive: ${it.toInt()} miles. ") }
            append("Notable/unusual months: $notableMonths. ")
            append("Trouble codes seen across the year: $codeCount. ")
            append("Services logged: $serviceCount.")
        }
        // Identity from AssistantIdentity - see its doc.
        val system = AssistantIdentity.shortClause(context) + " " +
            "You are looking back at the whole of $year with the driver - a year-end wrapped, not " +
            "just one month. Given the year's stats, write 3-5 short, warm, spoken-natural sentences that " +
            "feel like a genuine look-back over a full year together - notice the big picture " +
            "(a long haul, a steady year, a rough patch), don't just restate the numbers as a " +
            "list. Plain text only, no markdown, no lists."
        val agent = SubAgent(systemInstruction = system, useSearch = false)
        return agent.ask(stats, "Write the year-end wrapped.") ?: fallback
    }

    /**
     * Cover art generation is retired for now: it depended on `AvatarStudio`
     * (city-pop art direction, retired in the 2026-07-31 pivot) and no
     * replacement image-gen path has been decided. Always returns null.
     */
    private suspend fun generateCover(
        context: Context,
        vehicleId: String,
        vehicle: com.kevin.legion.data.local.Vehicle,
        year: Int,
    ): String? = null
}
