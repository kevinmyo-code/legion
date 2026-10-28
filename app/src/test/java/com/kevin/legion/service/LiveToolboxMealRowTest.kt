package com.kevin.legion.service

import com.kevin.legion.data.local.MealLog
import com.kevin.legion.plan.TrustTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * `list_recent_meals` rows carry the day they were logged (async-lookups ticket 07; ticket 04 run 3,
 * F7: "what did I eat yesterday" was answered "the records don't specify dates").
 */
class LiveToolboxMealRowTest {
    private val chicago = ZoneId.of("America/Chicago")

    private fun meal(at: ZonedDateTime) = MealLog(
        description = "Chicken and rice",
        caloriesKcal = 650,
        proteinG = 40.0,
        loggedAt = at.toInstant().toEpochMilli(),
        trustTier = TrustTier.REPORTED,
    )

    @Test
    fun `a row carries the ISO date and weekday in the phone's zone`() {
        val row = LiveToolbox.mealRow(meal(ZonedDateTime.of(2026, 10, 27, 19, 30, 0, 0, chicago)), chicago)
        assertEquals("2026-10-27", row.getString("date"))
        assertEquals("Tuesday", row.getString("weekday"))
        assertEquals("Chicken and rice", row.getString("description"))
        assertEquals(650, row.getInt("estimatedCaloriesKcal"))
        assertFalse(row.has("estimatedFatG"))
    }

    @Test
    fun `a late-evening meal stays on its local day, not the UTC one`() {
        // 23:30 in Chicago is already the next day in UTC.
        val row = LiveToolbox.mealRow(meal(ZonedDateTime.of(2026, 10, 27, 23, 30, 0, 0, chicago)), chicago)
        assertEquals("2026-10-27", row.getString("date"))
    }

    @Test
    fun `the declaration says each meal carries its date`() {
        // list_recent_meals sits behind ask_body, so it is in the full set, not the live one.
        val all = LiveToolbox.allDeclarations()
        val description = (0 until all.length()).map { all.getJSONObject(it) }
            .single { it.optString("name") == "list_recent_meals" }
            .optString("description")
        assertTrue(description.contains("the date it was logged"))
    }
}
