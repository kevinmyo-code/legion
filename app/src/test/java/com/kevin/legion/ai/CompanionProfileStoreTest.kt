package com.kevin.legion.ai

import com.kevin.legion.data.local.CompanionProfileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [CompanionProfileStore.shouldSeedFromLegacy] and
 * [CompanionProfileStore.buildSeedProfile] - the pure profile<->legacy-field
 * logic behind the migration-from-a-single-identity path. Deliberately a
 * plain JUnit test, no Room/Context/SharedPreferences: [CompanionProfileStore
 * .ensureSeeded]/[CompanionProfileStore.materializeActive] themselves touch
 * Android (Room, `CompanionProfile`'s SharedPreferences) and are NOT covered
 * here - see `gradlew testDebugUnitTest`'s plain-JVM constraint in
 * playbook-coding.md's "Testing, singletons, and composables" section.
 */
class CompanionProfileStoreTest {

    // --- shouldSeedFromLegacy -------------------------------------------------

    @Test
    fun shouldSeedFromLegacy_emptyRosterWithNamedIdentity_seeds() {
        assertTrue(CompanionProfileStore.shouldSeedFromLegacy(existingProfileCount = 0, legacyName = "Alfred", legacyPersona = ""))
    }

    @Test
    fun shouldSeedFromLegacy_emptyRosterWithOnlyPersonaSet_seeds() {
        // A driver who freeform-edited the persona without (re)typing a name
        // still has something worth preserving.
        assertTrue(CompanionProfileStore.shouldSeedFromLegacy(existingProfileCount = 0, legacyName = "", legacyPersona = "You are dry and dependable."))
    }

    @Test
    fun shouldSeedFromLegacy_emptyRosterAndBlankIdentity_freshInstallDoesNotSeed() {
        // A genuinely fresh install: nothing onboarded yet, onboarding (Part
        // 2/3) creates the first real profile, not this migration path.
        assertFalse(CompanionProfileStore.shouldSeedFromLegacy(existingProfileCount = 0, legacyName = "", legacyPersona = ""))
    }

    @Test
    fun shouldSeedFromLegacy_rosterAlreadyPopulated_neverReSeedsEvenWithLegacyIdentityPresent() {
        // A roster already exists (built locally by a prior run of this seed,
        // or pulled fresh from Drive) - must never seed a second, redundant
        // profile just because the legacy flat keys still hold values.
        assertFalse(CompanionProfileStore.shouldSeedFromLegacy(existingProfileCount = 1, legacyName = "Alfred", legacyPersona = "You are Alfred."))
    }

    // --- buildSeedProfile -------------------------------------------------

    @Test
    fun buildSeedProfile_copiesEveryLegacyFieldVerbatimOntoTheNewRow() {
        val row = CompanionProfileStore.buildSeedProfile(
            profileId = "profile-1",
            legacyName = "Alfred",
            legacyPersona = "You are Alfred, dry and dependable.",
            legacyTraits = """{"temperament":{"choice":"cool","custom":""}}""",
            legacyVoice = "Charon",
            legacyVoiceStyle = "Delivery notes, separate from your personality: Speak at a measured, even pace.",
            legacyVoiceStyleTraits = """{"pace":{"choice":"measured","custom":""}}""",
            updatedAt = 1733356800000L,
        )

        assertEquals("profile-1", row.profileId)
        assertEquals("Alfred", row.assistantName)
        assertEquals("You are Alfred, dry and dependable.", row.persona)
        assertEquals("""{"temperament":{"choice":"cool","custom":""}}""", row.traits)
        assertEquals("Charon", row.voice)
        assertEquals("Delivery notes, separate from your personality: Speak at a measured, even pace.", row.voiceStyle)
        assertEquals("""{"pace":{"choice":"measured","custom":""}}""", row.voiceStyleTraits)
        assertEquals(1733356800000L, row.updatedAt)
    }

    @Test
    fun buildSeedProfile_traitsRoundTripThroughEncodeAndDecodeSelections() {
        // The store encodes CompanionProfile.selections()/voiceStyleSelections()
        // (PersonaSelection maps) into the opaque strings this function stores
        // verbatim. Confirm that round-trip actually recovers the original
        // selections, since a broken encode/decode pairing would silently lose
        // a driver's picker choices on the very first migration.
        val selections = mapOf(
            "temperament" to PersonaSelection(choiceKey = "cool"),
            "humor" to PersonaSelection(choiceKey = CUSTOM_KEY, customText = "Deadpan, mostly."),
        )
        val row = CompanionProfileStore.buildSeedProfile(
            profileId = "profile-2",
            legacyName = "Zero",
            legacyPersona = "You are Zero.",
            legacyTraits = encodeSelections(selections),
            legacyVoice = "Sulafat",
            legacyVoiceStyle = "",
            legacyVoiceStyleTraits = encodeSelections(emptyMap()),
            updatedAt = 0L,
        )

        assertEquals(selections, decodeSelections(row.traits))
        assertTrue(decodeSelections(row.voiceStyleTraits).isEmpty())
    }

    // --- canDelete / nextActiveAfterDeleting (Part 2 roster screen) ----------

    private fun profile(id: String, updatedAt: Long) = CompanionProfileEntity(
        profileId = id,
        assistantName = id,
        persona = "alfred",
        traits = "",
        voice = "Charon",
        voiceStyle = "",
        voiceStyleTraits = "",
        updatedAt = updatedAt,
    )

    @Test
    fun canDelete_lastRemainingProfile_refused() {
        // The assistant must never end up with no identity at all.
        assertFalse(CompanionProfileStore.canDelete(rosterSize = 1))
    }

    @Test
    fun canDelete_twoOrMoreProfiles_allowed() {
        assertTrue(CompanionProfileStore.canDelete(rosterSize = 2))
        assertTrue(CompanionProfileStore.canDelete(rosterSize = 5))
    }

    @Test
    fun canDelete_emptyRoster_refused() {
        // Shouldn't happen in practice (ensureSeeded/onboarding always leaves
        // at least one row), but a count of 0 must not read as "deletion is fine".
        assertFalse(CompanionProfileStore.canDelete(rosterSize = 0))
    }

    @Test
    fun nextActiveAfterDeleting_picksFirstRemainingRowInRosterOrder() {
        // Roster is newest-edit-first (the DAO's own ORDER BY), so the pick
        // lands on the next-most-recently-touched profile.
        val roster = listOf(profile("a", 300L), profile("b", 200L), profile("c", 100L))
        assertEquals("b", CompanionProfileStore.nextActiveAfterDeleting(roster, deletedId = "a"))
    }

    @Test
    fun nextActiveAfterDeleting_deletedRowNotFirst_stillSkipsOnlyThatOne() {
        val roster = listOf(profile("a", 300L), profile("b", 200L), profile("c", 100L))
        assertEquals("a", CompanionProfileStore.nextActiveAfterDeleting(roster, deletedId = "b"))
    }

    @Test
    fun nextActiveAfterDeleting_onlyRowWasTheDeletedOne_nullRatherThanCrashing() {
        // canDelete gates this case in practice, but the function itself must
        // degrade to null (not throw, not silently pick the deleted row).
        val roster = listOf(profile("a", 300L))
        assertNull(CompanionProfileStore.nextActiveAfterDeleting(roster, deletedId = "a"))
    }

    // --- activeIdResolves: the creation-adoption branch ---------------------
    //
    // Regression guard for the bug found on device 2026-08-02: the "never end
    // up with no identity" invariant was implemented for deletion but not
    // creation, so a fresh install could hold two profiles with NO active
    // selection and answer as Alfred by fallback luck with a blank name.

    @Test
    fun `a null active id never resolves`() {
        assertFalse(CompanionProfileStore.activeIdResolves(null, null))
        assertFalse(CompanionProfileStore.activeIdResolves(null, profile("any")))
    }

    @Test
    fun `an active id pointing at a missing row does not resolve`() {
        // What a delete on the OTHER device produces once the roster syncs.
        assertFalse(CompanionProfileStore.activeIdResolves("deleted-elsewhere", null))
    }

    @Test
    fun `an active id with a live row resolves`() {
        assertTrue(CompanionProfileStore.activeIdResolves("p1", profile("p1")))
    }

    private fun profile(id: String) = CompanionProfileEntity(
        profileId = id,
        assistantName = "Alfred",
        persona = "alfred",
        traits = "{}",
        voice = "Charon",
        voiceStyle = "",
        voiceStyleTraits = "{}",
        updatedAt = 1L,
    )
}
