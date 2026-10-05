package com.kevin.legion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Marcus register: present on the roster, shaped by the rules CLAUDE.md section 7 puts above any
 * character, and carrying the quotation rule that `consult_meditations` exists to make possible.
 *
 * Like `AriaBrainHonestyClauseTest`, these guard a clause's PRESENCE, never the model's obedience:
 * nothing inspects spoken audio, so a prompt rule is the only lever and listening is the only proof.
 */
class MarcusPersonaTest {

    /** The clause is hard-wrapped in source; sentences are matched with whitespace collapsed. */
    private val clause = MARCUS.clause.replace(Regex("""\s+"""), " ")

    @Test
    fun `Marcus is on the roster with a voice the picker offers`() {
        assertTrue(MARCUS in BUILT_IN_PERSONAS)
        assertEquals(MARCUS, personaFor("marcus"))
        assertTrue(
            "suggestedVoice ${MARCUS.suggestedVoice} is not a CURATED_VOICES preset",
            CURATED_VOICES.any { it.name == MARCUS.suggestedVoice },
        )
        // Distinct from the other built-ins, so switching is audible.
        assertTrue(BUILT_IN_PERSONAS.filter { it != MARCUS }.none { it.suggestedVoice == MARCUS.suggestedVoice })
    }

    /** The clause is injected on every turn's setup; its size is a decision, so it is bounded. */
    @Test
    fun `the clause stays within its size budget`() {
        // 3,379 chars measured 2026-10-04 (~845 estimated tokens); Kratos, the longest precedent, is
        // 2,759. Marcus carries four jobs the others do not (see MarcusPayloadTest), so the budget is
        // 3,500, not Kratos's. Raise it only with the new measured figure written here.
        assertTrue("clause is ${clause.length} chars", clause.length <= 3_500)
        assertTrue("shortClause is ${MARCUS.shortClause.length} chars", MARCUS.shortClause.length <= 400)
    }

    @Test
    fun `the quotation rule names the tool and forbids invention`() {
        assertTrue(clause.contains("consult_meditations"))
        assertTrue(clause.contains("Quote ONLY words it returned in that same turn"))
        assertTrue(clause.contains("Never invent a quotation"))
        assertTrue(clause.contains("If it finds nothing, say you do not find it written"))
    }

    /** CLAUDE.md section 7: distress breaks character, and Stoic talk of death must not become encouragement. */
    @Test
    fun `distress stops the character and leaving life is never offered as comfort`() {
        assertTrue(clause.contains("stop being Marcus"))
        assertTrue(clause.contains("not equipped for this"))
        assertTrue(clause.contains("Never quote or paraphrase what you wrote about leaving life"))
        assertTrue(clause.contains("never call death a relief, a door or a release"))
        // The shared safety clause (assembled after the persona, and overriding it) still applies:
        // it is appended to every companion's instruction by AriaBrain, not chosen by persona.
        assertTrue(SHARED_INSTRUCTIONS.isNotBlank())
    }

    /**
     * The crisis detector reads the user's transcript and never asks who is speaking
     * (`GeminiLiveSession.checkForCrisis`), so with Marcus active the same phrases trip it. This pins
     * the phrases a Stoic conversation could plausibly drift to.
     */
    @Test
    fun `the crisis detector does not depend on the persona and still fires`() {
        val distressed = listOf("I want to die", "I want to kill myself", "I have nothing to live for", "I am suicidal")
        for (phrase in distressed) {
            assertTrue(phrase, CrisisDetector.detect(phrase))
        }
        // Ordinary Stoic talk of death is not a crisis; the persona is allowed to discuss it.
        for (phrase in listOf("what did you think about death", "are you afraid to die someday", "memento mori")) {
            assertFalse(phrase, CrisisDetector.detect(phrase))
        }
    }

    @Test
    fun `no compulsion mechanic and no counting`() {
        assertTrue(clause.contains("You never count how long"))
        assertTrue(clause.contains("never mention their absence"))
        assertTrue(clause.contains("never bargain, never plead, never guilt"))
        for (g in MARCUS.greetings) {
            val lower = g.lowercase()
            for (banned in listOf("again", "back", "missed", "while", "days", "streak", "long time")) {
                val mentions = Regex("\\b$banned\\b").containsMatchIn(lower)
                assertFalse("greeting \"$g\" references absence (\"$banned\")", mentions)
            }
        }
    }

    @Test
    fun `the register is plain - no exclamation marks and no therapy words in speech or greetings`() {
        assertFalse(MARCUS.greetings.any { it.contains('!') })
        // The clause LISTS the banned words once, to forbid them; strip that sentence before checking.
        val withoutList = clause.replace(Regex("""\(self-care[^)]*\)"""), "")
        for (word in listOf("self-care", "boundaries", "healing", "mindset", "journey", "toxic")) {
            assertFalse("clause uses \"$word\"", withoutList.contains(word))
        }
        for (g in MARCUS.greetings) for (word in listOf("self-care", "boundaries", "healing", "mindset", "journey")) {
            assertFalse(g.lowercase().contains(word))
        }
    }

    @Test
    fun `he does not claim to be real and is not framed around driving`() {
        assertTrue(clause.contains("an assistant who speaks in his manner"))
        assertFalse(clause.lowercase().contains("driver"))
        assertFalse(MARCUS.delivery.lowercase().contains("driver"))
    }

    @Test
    fun `a rename swaps the name and leaves the rest`() {
        // AssistantIdentity.withName replaces the default name as a whole word; the clause must
        // use "Marcus" only where it means the assistant's own name.
        val renamed = clause.replace(Regex("\\bMarcus\\b"), "Emperor")
        assertTrue(renamed.startsWith("You are Emperor, Emperor of Rome"))
        assertFalse(renamed.contains("Marcus"))
    }

    @Test
    fun `aliases cover the titles people use`() {
        assertTrue("Marcus Aurelius" in MARCUS.aliases)
        assertTrue("the Emperor" in MARCUS.aliases)
    }
}
