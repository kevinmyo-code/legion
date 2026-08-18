package com.kevin.legion.advisor

import com.kevin.legion.ai.ActiveCompanionProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [Priming] + [PlaybookStore]: the resolver both answer paths call, and the driver-editable store
 * underneath it (2026-08-18).
 *
 * Robolectric only because [PlaybookStore] reads and writes real files under `filesDir` and
 * [PlaybookStore.profileDir] reads real SharedPreferences - the logic itself is plain Kotlin.
 *
 * The load-bearing cases here are the FALLBACK ones. A store that returns the driver's edit when
 * there is one is the easy half; the half that can silently break an advisor is what it returns
 * when the file is blank, missing, or identical to the shipped text, because every one of those
 * reads as working software while stripping the domain knowledge out of a sub-agent that is still
 * expected to answer.
 */
@RunWith(RobolectricTestRunner::class)
class PrimingTest {
    private val context = RuntimeEnvironment.getApplication()

    /**
     * An edit that keeps every required referral boundary, so it passes [PlaybookStore.save]'s
     * boundary guard and exercises the storage path rather than the refusal path. Built from the
     * shipped text plus a marker rather than from a short string, because a short string is
     * exactly what the guard exists to refuse - see the boundary test below.
     */
    private fun edited(topic: PrimingTopic) = topic.defaultText.trim() + "\nMARKER-" + topic.key

    @After
    fun clearOverrides() {
        PrimingTopic.values().forEach { PlaybookStore.revertToDefault(context, it) }
        context.getSharedPreferences("active_companion_profile", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    // --- The store ------------------------------------------------------------------------

    @Test
    fun `with no override the shipped playbook is what rides the prompt`() {
        PrimingTopic.values().forEach { topic ->
            assertEquals(topic.defaultText, PlaybookStore.text(context, topic))
            assertFalse(PlaybookStore.isCustomised(context, topic))
        }
    }

    @Test
    fun `a saved edit replaces the shipped playbook and reports as customised`() {
        assertEquals(
            PlaybookSaveResult.Saved,
            PlaybookStore.save(context, PrimingTopic.BIO, edited(PrimingTopic.BIO)),
        )
        assertEquals(edited(PrimingTopic.BIO), PlaybookStore.text(context, PrimingTopic.BIO))
        assertTrue(PlaybookStore.isCustomised(context, PrimingTopic.BIO))
        // One topic's edit must not leak into another's - they are separate files.
        assertEquals(PrimingTopic.FLEET.defaultText, PlaybookStore.text(context, PrimingTopic.FLEET))
    }

    @Test
    fun `saving blank text reverts rather than blanking the advisor`() {
        PlaybookStore.save(context, PrimingTopic.CRED, edited(PrimingTopic.CRED))
        assertTrue(PlaybookStore.isCustomised(context, PrimingTopic.CRED))

        assertEquals(PlaybookSaveResult.RevertedToDefault, PlaybookStore.save(context, PrimingTopic.CRED, "   \n  "))

        assertEquals(PrimingTopic.CRED.defaultText, PlaybookStore.text(context, PrimingTopic.CRED))
        assertFalse(PlaybookStore.isCustomised(context, PrimingTopic.CRED))
    }

    @Test
    fun `saving text identical to the default is not an edit`() {
        assertEquals(
            PlaybookSaveResult.RevertedToDefault,
            PlaybookStore.save(context, PrimingTopic.LOG, PrimingTopic.LOG.defaultText),
        )
        assertFalse(PlaybookStore.isCustomised(context, PrimingTopic.LOG))
        assertEquals(PrimingTopic.LOG.defaultText, PlaybookStore.text(context, PrimingTopic.LOG))
    }

    @Test
    fun `revert restores the shipped playbook`() {
        PlaybookStore.save(context, PrimingTopic.FLEET, edited(PrimingTopic.FLEET))
        PlaybookStore.revertToDefault(context, PrimingTopic.FLEET)
        assertEquals(PrimingTopic.FLEET.defaultText, PlaybookStore.text(context, PrimingTopic.FLEET))
        assertFalse(PlaybookStore.isCustomised(context, PrimingTopic.FLEET))
    }

    @Test
    fun `two companion profiles do not share doctrine`() {
        ActiveCompanionProfile.setActiveProfileId(context, "profile-kevin")
        PlaybookStore.save(context, PrimingTopic.BIO, edited(PrimingTopic.BIO))

        ActiveCompanionProfile.setActiveProfileId(context, "profile-other")
        assertEquals(PrimingTopic.BIO.defaultText, PlaybookStore.text(context, PrimingTopic.BIO))
        assertFalse(PlaybookStore.isCustomised(context, PrimingTopic.BIO))

        ActiveCompanionProfile.setActiveProfileId(context, "profile-kevin")
        assertEquals(edited(PrimingTopic.BIO), PlaybookStore.text(context, PrimingTopic.BIO))

        // Cleanup for @After, which only clears the profile the prefs currently point at.
        PlaybookStore.revertToDefault(context, PrimingTopic.BIO)
    }

    @Test
    fun `a profile id carrying path separators cannot escape the playbooks directory`() {
        ActiveCompanionProfile.setActiveProfileId(context, "../../etc/passwd")
        PlaybookStore.save(context, PrimingTopic.BIO, edited(PrimingTopic.BIO))

        val file = PlaybookStore.fileFor(context, PrimingTopic.BIO)
        val root = java.io.File(context.filesDir, "playbooks").canonicalPath
        assertTrue(file.canonicalPath.startsWith(root))
        assertEquals(edited(PrimingTopic.BIO), PlaybookStore.text(context, PrimingTopic.BIO))

        PlaybookStore.revertToDefault(context, PrimingTopic.BIO)
    }

    // --- The advisor path -----------------------------------------------------------------

    @Test
    fun `every advisor aspect but HOME resolves doctrine, HOME resolves none`() {
        AdvisorAspect.values().filter { it != AdvisorAspect.HOME }.forEach { aspect ->
            val text = Priming.forAdvisor(context, aspect)
            assertNotNull("$aspect should carry doctrine", text)
            assertTrue("$aspect doctrine should not be blank", text!!.isNotBlank())
        }
        assertNull(Priming.forAdvisor(context, AdvisorAspect.HOME))
    }

    @Test
    fun `an edited playbook reaches the composed advisor prompt`() {
        PlaybookStore.save(context, PrimingTopic.BIO, edited(PrimingTopic.BIO))
        val composed = AdvisorAgent.composeContext(
            brief = AdvisorBriefs.BIO,
            digest = "unused",
            goals = emptyList(),
            adviceLog = emptyList(),
            playbook = Priming.forAdvisor(context, AdvisorAspect.BIO),
        )
        assertTrue(composed.contains("PLAYBOOK:"))
        assertTrue(composed.contains("MARKER-bio"))
    }

    @Test
    fun `composeContext defaults to the brief's own playbook when no override is passed`() {
        val composed = AdvisorAgent.composeContext(
            brief = AdvisorBriefs.HOME,
            digest = "unused",
            goals = emptyList(),
            adviceLog = emptyList(),
        )
        // HOME's brief playbook is null, so no header at all - the pre-existing rule this change
        // must not have broken.
        assertFalse(composed.contains("PLAYBOOK:"))
    }

    // --- The voice-dispatch path ----------------------------------------------------------

    @Test
    fun `the dispatch routing table primes exactly fleet and body`() {
        assertEquals(PrimingTopic.FLEET, Priming.topicForDispatchDomain("fleet"))
        assertEquals(PrimingTopic.BIO, Priming.topicForDispatchDomain("body"))
        listOf("pantry", "goals", "mail", "nonsense", "").forEach {
            assertNull("'$it' must not inherit doctrine by default", Priming.topicForDispatchDomain(it))
        }
    }

    @Test
    fun `an unprimed domain's clause is empty, so its grounding is unchanged`() {
        listOf("pantry", "goals", "mail", "nonsense").forEach {
            assertEquals("", Priming.dispatchClause(context, it))
        }
    }

    @Test
    fun `a primed domain's clause carries the driver's doctrine and the basis rule`() {
        PlaybookStore.save(context, PrimingTopic.FLEET, edited(PrimingTopic.FLEET))
        val clause = Priming.dispatchClause(context, "fleet")
        assertTrue(clause.contains("MARKER-fleet"))
        assertTrue(clause.contains("PLAYBOOK:"))
        // The honesty rule is not optional on a path that has no basis-tagged schema to fall back
        // on - see Priming.BASIS_CLAUSE's own doc comment.
        assertTrue(clause.contains(Priming.BASIS_CLAUSE))
    }

    @Test
    fun `topic keys match the advisor aspect keys they stand for`() {
        assertEquals(AdvisorAspect.BIO.key, PrimingTopic.BIO.key)
        assertEquals(AdvisorAspect.LOG.key, PrimingTopic.LOG.key)
        assertEquals(AdvisorAspect.FLEET.key, PrimingTopic.FLEET.key)
        assertEquals(AdvisorAspect.CRED.key, PrimingTopic.CRED.key)
        PrimingTopic.values().forEach { assertEquals(it, PrimingTopic.fromKey(it.key)) }
        assertNull(PrimingTopic.fromKey("home"))
    }

    // --- The two guards on a driver's edit -------------------------------------------------

    @Test
    fun `an edit that drops the referral boundaries is refused, and says which`() {
        val result = PlaybookStore.save(context, PrimingTopic.BIO, "Just squat heavy and eat big.")

        assertTrue(result is PlaybookSaveResult.MissingBoundaries)
        val missing = (result as PlaybookSaveResult.MissingBoundaries).missing
        // The lines that send someone to a doctor are exactly what a wholesale replacement
        // deletes, and exactly what PlaybookKeywordsTest cannot see because it only reads the
        // shipped constant.
        assertTrue(missing.contains("Pain or injury"))
        assertTrue(missing.contains("Medical conditions"))
        // Nothing was stored - the advisor still reads the shipped doctrine.
        assertEquals(PrimingTopic.BIO.defaultText, PlaybookStore.text(context, PrimingTopic.BIO))
        assertFalse(PlaybookStore.isCustomised(context, PrimingTopic.BIO))
    }

    @Test
    fun `an edit over the token ceiling is refused with both numbers`() {
        val tooLong = edited(PrimingTopic.LOG) + "x".repeat(PrimingTopic.MAX_CHARS)

        val result = PlaybookStore.save(context, PrimingTopic.LOG, tooLong)

        assertTrue(result is PlaybookSaveResult.TooLong)
        val r = result as PlaybookSaveResult.TooLong
        assertEquals(PrimingTopic.MAX_CHARS, r.maxChars)
        assertTrue(r.actualChars > r.maxChars)
        assertEquals(PrimingTopic.LOG.defaultText, PlaybookStore.text(context, PrimingTopic.LOG))
    }

    @Test
    fun `every shipped playbook passes its own guards`() {
        // If a shipped default could not itself be saved, the guard would be wrong rather than the
        // driver - and "revert, edit one word, save" would be impossible.
        PrimingTopic.values().forEach { topic ->
            assertTrue(
                "${topic.key} default is over the ceiling",
                topic.defaultText.trim().length <= PrimingTopic.MAX_CHARS,
            )
            assertEquals(
                "${topic.key} default is missing its own required phrases",
                emptyList<String>(),
                topic.missingPhrases(topic.defaultText),
            )
        }
    }
}
