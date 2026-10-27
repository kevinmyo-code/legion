package com.kevin.legion.ai

import com.kevin.legion.data.local.CompanionProfileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision half of the `switch_companion` voice tool. Pure - no Room, no SharedPreferences, no
 * session - which is the whole reason [CompanionSwitch] was split out of
 * `LiveSessionController.switchCompanionTool` rather than written inline there.
 */
class CompanionSwitchTest {

    private fun profile(id: String, name: String, persona: String = "alfred") =
        CompanionProfileEntity(
            profileId = id,
            assistantName = name,
            persona = persona,
            traits = "",
            voice = "Charon",
            voiceStyle = "",
            voiceStyleTraits = "",
            updatedAt = 0L,
        )

    private val alfred = profile("p-alfred", "Alfred", "alfred")
    private val dorothy = profile("p-dorothy", "Dorothy", "dorothy")
    private val roster = listOf(alfred, dorothy)

    // --- the ordinary case ------------------------------------------------

    @Test
    fun `a rostered name that is not active resolves to a switch`() {
        val outcome = CompanionSwitch.resolve(roster, "Dorothy", activeProfileId = "p-alfred")
        assertEquals(CompanionSwitch.Outcome.Switch("p-dorothy", "Dorothy"), outcome)
    }

    @Test
    fun `matching ignores case and punctuation`() {
        assertEquals(
            CompanionSwitch.Outcome.Switch("p-dorothy", "Dorothy"),
            CompanionSwitch.resolve(roster, "  dorothy!  ", activeProfileId = "p-alfred"),
        )
    }

    @Test
    fun `asking for the companion already speaking changes nothing`() {
        val outcome = CompanionSwitch.resolve(roster, "Alfred", activeProfileId = "p-alfred")
        assertEquals(CompanionSwitch.Outcome.AlreadyActive("Alfred"), outcome)
    }

    @Test
    fun `with no active selection any rostered name is a real switch`() {
        assertEquals(
            CompanionSwitch.Outcome.Switch("p-alfred", "Alfred"),
            CompanionSwitch.resolve(roster, "Alfred", activeProfileId = null),
        )
    }

    // --- built-ins with no profile row yet --------------------------------

    /**
     * The case Kratos shipped into: a built-in persona the user has never created a profile for.
     * Without this branch, asking for him by name would be a NotFound that lists him as
     * unavailable while the Companions picker plainly offers him.
     */
    @Test
    fun `a built-in with no row resolves to create-and-switch`() {
        val outcome = CompanionSwitch.resolve(roster, "Kratos", activeProfileId = "p-alfred")
        assertEquals(CompanionSwitch.Outcome.CreateAndSwitch(KRATOS), outcome)
    }

    @Test
    fun `a built-in also resolves by its persona key`() {
        assertEquals(
            CompanionSwitch.Outcome.CreateAndSwitch(KRATOS),
            CompanionSwitch.resolve(roster, "kratos", activeProfileId = "p-alfred"),
        )
    }

    /**
     * A roster row always wins over the template it came from, so asking for "Alfred" when an
     * Alfred row exists can never build a second one. This is the duplicate-profile-per-request
     * bug that ordering guards against.
     */
    @Test
    fun `a rostered name never falls through to create-and-switch`() {
        val outcome = CompanionSwitch.resolve(roster, "Alfred", activeProfileId = "p-dorothy")
        assertEquals(CompanionSwitch.Outcome.Switch("p-alfred", "Alfred"), outcome)
    }

    /** A renamed profile answers to its own name, not to the persona it was built from. */
    @Test
    fun `a renamed profile resolves by the name the user gave it`() {
        val renamed = listOf(profile("p-1", "Jeeves", "alfred"))
        assertEquals(
            CompanionSwitch.Outcome.Switch("p-1", "Jeeves"),
            CompanionSwitch.resolve(renamed, "Jeeves", activeProfileId = null),
        )
        // "Alfred" is now a built-in with no row wearing that name, so it offers to create one.
        assertEquals(
            CompanionSwitch.Outcome.CreateAndSwitch(ALFRED),
            CompanionSwitch.resolve(renamed, "Alfred", activeProfileId = null),
        )
    }

    // --- misses -----------------------------------------------------------

    @Test
    fun `an unknown name is not found and lists what does exist`() {
        val outcome = CompanionSwitch.resolve(roster, "Jarvis", activeProfileId = "p-alfred")
        assertTrue(outcome is CompanionSwitch.Outcome.NotFound)
        val notFound = outcome as CompanionSwitch.Outcome.NotFound
        assertEquals("Jarvis", notFound.spoken)
        assertEquals(listOf("Alfred", "Dorothy", "Kratos"), notFound.available)
    }

    /**
     * Matching is exact after normalising, never fuzzy: a near-miss must ask rather than guess.
     * Handing the conversation to the wrong person silently is the failure this protects against,
     * and it costs one clarifying turn to avoid.
     */
    @Test
    fun `a near-miss is not found rather than guessed`() {
        assertTrue(CompanionSwitch.resolve(roster, "Dorothea", null) is CompanionSwitch.Outcome.NotFound)
        assertTrue(CompanionSwitch.resolve(roster, "Al", null) is CompanionSwitch.Outcome.NotFound)
    }

    @Test
    fun `a blank name is not found`() {
        assertTrue(CompanionSwitch.resolve(roster, "", null) is CompanionSwitch.Outcome.NotFound)
        assertTrue(CompanionSwitch.resolve(roster, "   ", null) is CompanionSwitch.Outcome.NotFound)
    }

    @Test
    fun `an empty roster still offers every built-in`() {
        val outcome = CompanionSwitch.resolve(emptyList(), "Nobody", activeProfileId = null)
        val notFound = outcome as CompanionSwitch.Outcome.NotFound
        assertEquals(BUILT_IN_PERSONAS.map { it.defaultName }, notFound.available)
    }

    // --- availableNames ---------------------------------------------------

    @Test
    fun `available names are roster first then unbuilt built-ins`() {
        val renamed = listOf(profile("p-1", "Jeeves", "alfred"), dorothy)
        assertEquals(
            listOf("Jeeves", "Dorothy", "Alfred", "Kratos"),
            CompanionSwitch.availableNames(renamed),
        )
    }

    @Test
    fun `a built-in already in the roster is not listed twice`() {
        assertEquals(listOf("Alfred", "Dorothy", "Kratos"), CompanionSwitch.availableNames(roster))
    }
}
