package com.kevin.legion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
