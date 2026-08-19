package com.kevin.legion.ai

import com.kevin.legion.vehicle.ActiveVehicle
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [CompanionProfile]'s global identity + one-time per-car promotion
 * (fleet-wide voice, ticket 01). Robolectric only because these read/write
 * real [android.content.SharedPreferences].
 */
@RunWith(RobolectricTestRunner::class)
class CompanionProfileTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        context.getSharedPreferences("companion_profile", android.content.Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("active_vehicle", android.content.Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Test 13: a value written under a per-car key and absent from the flat key is promoted on first read. */
    @Test
    fun `a per-car legacy value promotes to the flat key on first read`() {
        ActiveVehicle.select(context, "AA:BB")
        // Simulate a pre-ticket-01 install: persona saved under the OLD
        // per-car key, nothing under the flat one. Written directly (not via
        // CompanionProfile.savePersona, which now always writes the flat key)
        // to reproduce exactly what an existing install's SharedPreferences
        // file looks like.
        context.getSharedPreferences("companion_profile", android.content.Context.MODE_PRIVATE)
            .edit().putString("persona:AA:BB", "You are Alfred.").apply()

        val read = CompanionProfile.persona(context)

        assertEquals("You are Alfred.", read)
        // Promoted: the flat key now holds it directly, not just via fallback.
        val flat = context.getSharedPreferences("companion_profile", android.content.Context.MODE_PRIVATE)
            .getString("persona", null)
        assertEquals("You are Alfred.", flat)
    }

    /** Test 14: after promotion, switching the active car returns the SAME persona, name and voice. */
    @Test
    fun `after promotion the persona survives switching the active car`() {
        ActiveVehicle.select(context, "AA:BB")
        val prefs = context.getSharedPreferences("companion_profile", android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .putString("persona:AA:BB", "You are Dorothy.")
            .putString("name:AA:BB", "Dorothy")
            .putString("voice:AA:BB", "Kore")
            .apply()

        // First read while AA:BB is active triggers the promotion.
        assertEquals("You are Dorothy.", CompanionProfile.persona(context))
        assertEquals("Dorothy", CompanionProfile.name(context))
        assertEquals("Kore", CompanionProfile.voice(context))

        // Switch the active car - identity must not follow the car anymore.
        ActiveVehicle.select(context, "CC:DD")

        assertEquals("You are Dorothy.", CompanionProfile.persona(context))
        assertEquals("Dorothy", CompanionProfile.name(context))
        assertEquals("Kore", CompanionProfile.voice(context))
    }

    /** Test 15: a fresh install with nothing stored returns blank, not a crash. */
    @Test
    fun `a fresh install with nothing stored returns blank`() {
        ActiveVehicle.select(context, "AA:BB")

        assertEquals("", CompanionProfile.name(context))
        assertEquals("", CompanionProfile.persona(context))
        assertEquals("", CompanionProfile.voice(context))
        assertEquals("", CompanionProfile.voiceStyle(context))
    }

    /** A promoted value never re-promotes over a driver's later edit (the "once" in "one-time promotion"). */
    @Test
    fun `promotion never overwrites a value the driver has since changed`() {
        ActiveVehicle.select(context, "AA:BB")
        val prefs = context.getSharedPreferences("companion_profile", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("persona:AA:BB", "Old legacy persona.").apply()

        // Promotes on first read.
        assertEquals("Old legacy persona.", CompanionProfile.persona(context))

        // Driver edits the persona through the normal global save path.
        CompanionProfile.saveProfile(context, "New Name", "A brand new persona.")

        assertEquals("A brand new persona.", CompanionProfile.persona(context))
    }

    /** [CompanionProfile.clear] must not leave a per-car key promotion can revive. */
    @Test
    fun `clear also removes the legacy per-car key so a clear cannot be undone by promotion`() {
        ActiveVehicle.select(context, "AA:BB")
        val prefs = context.getSharedPreferences("companion_profile", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("persona:AA:BB", "Old legacy persona.").apply()
        // Promote, then clear.
        CompanionProfile.persona(context)
        CompanionProfile.clear(context)

        assertEquals("", CompanionProfile.persona(context))
    }
}
