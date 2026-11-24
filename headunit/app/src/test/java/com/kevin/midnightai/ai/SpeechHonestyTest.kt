package com.kevin.midnightai.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards [CANNOT_CLAUSE]'s presence and the three properties that make it one clause rather than a
 * per-tool list (root ADR 0031). It cannot guard obedience: nothing inspects the spoken audio.
 *
 * The snake-case test is the load-bearing one. Every `LiveToolbox` tool name is snake case, so a
 * clause with no snake-case token names no tool, which is what lets it survive tool 57 unedited.
 */
class SpeechHonestyTest {

    @Test
    fun theClauseIsPresentAndNotEmpty() {
        assertTrue(CANNOT_CLAUSE.length > 200)
        assertTrue("ends with a space so it concatenates", CANNOT_CLAUSE.endsWith(" "))
    }

    @Test
    fun itNamesNoTool_soItSurvivesEveryNewOne() {
        val snakeCase = Regex("[a-z]+_[a-z]+")
        val hit = snakeCase.find(CANNOT_CLAUSE)
        assertFalse(
            "the clause must name no tool, found '${hit?.value}'",
            hit != null,
        )
    }

    @Test
    fun itForbidsTheOutcomeVerbsTheHeadUnitCanActuallyFake() {
        // Each of these is a word Moose can say about a car-local write or a music call that never
        // landed. "logged" and "saved" are the service-ledger ones; "playing" is the App Remote's.
        // Matched on a word boundary: a bare `contains("sent")` is satisfied by "sentence".
        listOf("done", "logged", "saved", "set", "started", "playing", "sent", "booked")
            .forEach { verb ->
                assertTrue(
                    "the clause must forbid '$verb' as a word",
                    Regex("\\b${Regex.escape(verb)}\\b").containsMatchIn(CANNOT_CLAUSE),
                )
            }
    }

    @Test
    fun anUnsuccessfulResultCountsAsNoTool() {
        assertTrue(CANNOT_CLAUSE.contains("unsuccessful"))
        assertTrue(CANNOT_CLAUSE.contains("same as no tool at all"))
    }

    @Test
    fun theOutcomeVerbsAreGatedOnThisTurnsResult_notOnHavingATool() {
        assertTrue(CANNOT_CLAUSE.contains("in this turn"))
        assertTrue(CANNOT_CLAUSE.contains("came back successful"))
    }

    @Test
    fun theNearestThingOffered_mustItselfBeARealTool() {
        assertTrue(CANNOT_CLAUSE.contains("nearest thing"))
        assertTrue(
            "the invention trap must be named, or the clause moves it one sentence later",
            CANNOT_CLAUSE.contains("genuinely have a") && CANNOT_CLAUSE.contains("one sentence later"),
        )
    }
}
