package com.kevin.legion.ui

import com.kevin.legion.ai.GeminiUsageMeter
import com.kevin.legion.ai.KeyHealth
import com.kevin.legion.data.local.BackgroundPassState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sentences the Setup screen says about Gemini spend.
 *
 * **The property under test is honesty, not phrasing.** Gemini omits `usageMetadata` on some 200
 * responses, so a window can contain calls whose cost is genuinely unknown, and printing "0 tokens
 * today" over such a window would tell Kevin he is fine precisely when the app cannot see - the
 * same failure as reading a refused calendar permission as a clear day (CLAUDE.md section 1).
 * Every test below pins one of the three states apart from the other two.
 *
 * Pure functions, so no Robolectric: this is the whole reason they were pulled out of the
 * Composable, the same reasoning [auditTrailBacklogSentence]'s own doc gives.
 */
class GeminiSpendSentenceTest {

    private fun spend(
        tokensToday: Long? = null,
        callsToday: Int = 0,
        unreportedToday: Int = 0,
        tokensThisMonth: Long? = null,
        callsThisMonth: Int = 0,
        unreportedThisMonth: Int = 0,
        connectsThisMonth: Int = 0,
        connectsWithTurnThisMonth: Int = 0,
        setAside: List<BackgroundPassState> = emptyList(),
    ) = GeminiUsageMeter.Spend(
        tokensToday = tokensToday,
        callsToday = callsToday,
        unreportedToday = unreportedToday,
        tokensThisMonth = tokensThisMonth,
        callsThisMonth = callsThisMonth,
        unreportedThisMonth = unreportedThisMonth,
        connectsThisMonth = connectsThisMonth,
        connectsWithTurnThisMonth = connectsWithTurnThisMonth,
        setAside = setAside,
    )

    @Test
    fun `an unreadable database says so, and never reports zero`() {
        val sentence = geminiSpendSentence(null)
        assertTrue(sentence.contains("Couldn't read"))
        assertFalse("an unread state must never render as a measured 0", sentence.contains("0 tokens"))
    }

    @Test
    fun `a window whose calls all went unreported says the cost is unknown`() {
        val sentence = geminiSpendSentence(
            spend(
                tokensToday = null, callsToday = 4, unreportedToday = 4,
                tokensThisMonth = null, callsThisMonth = 4, unreportedThisMonth = 4,
            ),
        )
        assertTrue("it must say the API reported nothing", sentence.contains("reported no token count"))
        assertTrue("and still say the calls happened", sentence.contains("4 calls"))
        assertFalse(sentence.contains("0 tokens"))
    }

    @Test
    fun `a partly-unreported total is labelled a floor, with the missing count named`() {
        val sentence = geminiSpendSentence(
            spend(
                tokensToday = 5000, callsToday = 10, unreportedToday = 3,
                tokensThisMonth = 90000, callsThisMonth = 120, unreportedThisMonth = 11,
            ),
        )
        assertTrue("'at least' is what stops the number reading as complete", sentence.contains("At least 5000 tokens"))
        assertTrue("and the number of unmeasured calls says how much is missing", sentence.contains("3 of them"))
        assertTrue(sentence.contains("At least 90000 tokens"))
    }

    @Test
    fun `a fully-measured window states the figure plainly`() {
        val sentence = geminiSpendSentence(
            spend(
                tokensToday = 1200, callsToday = 3, unreportedToday = 0,
                tokensThisMonth = 45000, callsThisMonth = 90, unreportedThisMonth = 0,
            ),
        )
        assertTrue(sentence.contains("1200 tokens today across 3 calls"))
        assertTrue(sentence.contains("45000 tokens this month across 90 calls"))
        assertFalse("nothing is missing, so nothing may be hedged", sentence.contains("At least"))
    }

    @Test
    fun `a quiet today inside a busy month is said as a quiet today, not as unknown`() {
        val sentence = geminiSpendSentence(
            spend(
                tokensToday = null, callsToday = 0, unreportedToday = 0,
                tokensThisMonth = 45000, callsThisMonth = 90, unreportedThisMonth = 0,
            ),
        )
        assertTrue(
            "no calls is a different sentence from no reported count",
            sentence.contains("No Gemini calls today"),
        )
        assertFalse(sentence.contains("reported no token count"))
    }

    @Test
    fun `an empty month says metering only started when it started`() {
        // Otherwise "0 calls this month" reads as "the app made no calls", when the truth is that
        // nothing before 2026-09-06 was ever counted.
        val sentence = geminiSpendSentence(spend(callsThisMonth = 0))
        assertTrue(sentence.contains("Metering started"))
    }

    @Test
    fun `one call is not called one calls`() {
        val sentence = geminiSpendSentence(
            spend(tokensToday = 50, callsToday = 1, tokensThisMonth = 50, callsThisMonth = 1),
        )
        assertTrue(sentence.contains("across 1 call."))
    }

    @Test
    fun `connects nobody spoke into are named and their cost explained`() {
        val sentence = liveConnectSentence(spend(connectsThisMonth = 40, connectsWithTurnThisMonth = 6))
        assertTrue(sentence.contains("40 voice connections"))
        assertTrue(sentence.contains("34 of which nobody spoke into"))
        assertTrue("a bare count is not actionable without why it costs", sentence.contains("setup prompt"))
    }

    @Test
    fun `a month where every connect was used says so without alarming`() {
        val sentence = liveConnectSentence(spend(connectsThisMonth = 8, connectsWithTurnThisMonth = 8))
        assertTrue(sentence.contains("every one of them spoken into"))
        assertFalse(sentence.contains("nobody spoke"))
    }

    @Test
    fun `an unreadable connect count says so rather than claiming none`() {
        assertTrue(liveConnectSentence(null).contains("Couldn't read"))
    }

    @Test
    fun `nothing set aside says nothing at all`() {
        assertNull(backgroundPassSetAsideSentence(spend()))
        assertNull(backgroundPassSetAsideSentence(null))
    }

    @Test
    fun `a set-aside pass is surfaced with the reason it recorded`() {
        // A pass that quietly stopped and a pass that never ran look identical from the outside,
        // and that is exactly how reflection spent a week re-synthesizing the same memories.
        val sentence = backgroundPassSetAsideSentence(
            spend(
                setAside = listOf(
                    BackgroundPassState(
                        passKey = "reflection:car",
                        setAsideAt = 1L,
                        setAsideReason = "Reflection failed 5 times in a row.",
                    ),
                ),
            ),
        )
        assertEquals(
            "One background task has stopped retrying. Reflection failed 5 times in a row.",
            sentence,
        )
    }

    @Test
    fun `several set-aside passes are counted`() {
        val sentence = backgroundPassSetAsideSentence(
            spend(
                setAside = listOf(
                    BackgroundPassState(passKey = "a", setAsideAt = 1L, setAsideReason = "First reason."),
                    BackgroundPassState(passKey = "b", setAsideAt = 2L, setAsideReason = "Second reason."),
                ),
            ),
        )
        assertTrue(checkNotNull(sentence).startsWith("2 background tasks have stopped retrying."))
    }

    // --- Assistant availability (the quota sentence, 2026-09-06) ---

    @Test
    fun `a healthy key says nothing at all`() {
        // Not "everything is fine" - a settings screen full of reassurances is noise, and the row
        // is meant to be absent so its presence means something.
        assertNull(assistantAvailabilitySentence(null, ""))
        assertNull(assistantAvailabilitySentence("", ""))
    }

    @Test
    fun `a 429 names both possible causes and never promises a recovery`() {
        // The chip used to read "KEY RATE-LIMITED - TRY AGAIN SOON". Gemini returns 429
        // RESOURCE_EXHAUSTED for a per-minute rate limit AND for an exhausted quota, and the status
        // does not tell them apart - so "soon" was an assertion the app had no basis for, and it
        // was false for Kevin, whose credits had run out.
        val sentence = checkNotNull(
            assistantAvailabilitySentence(KeyHealth.PROBLEM_RATE_LIMITED, "HTTP 429 from Gemini"),
        )
        assertTrue(
            "both causes must be named, because only one of them was observed",
            sentence.contains("out of quota"),
        )
        assertTrue(sentence.contains("rate-limited"))
        assertFalse("nothing may promise a recovery that was never observed", sentence.contains("soon"))
        assertFalse(sentence.contains("try again"))
    }

    @Test
    fun `the sentence says what still works, so it reads as a fact and not a wall`() {
        val sentence = checkNotNull(
            assistantAvailabilitySentence(KeyHealth.PROBLEM_RATE_LIMITED, ""),
        )
        assertTrue(sentence.contains("still works by hand"))
        // Not a paywall and not a nag - CLAUDE.md section 7's compulsion ban. One factual line.
        assertFalse(sentence.contains("upgrade"))
        assertFalse(sentence.contains("top up"))
        assertFalse(sentence.contains("$"))
    }

    @Test
    fun `the observed status is quoted, so the claim is checkable`() {
        val sentence = checkNotNull(
            assistantAvailabilitySentence(KeyHealth.PROBLEM_RATE_LIMITED, "HTTP 429 opening the Gemini Live socket"),
        )
        assertTrue(sentence.contains("HTTP 429 opening the Gemini Live socket"))
    }

    @Test
    fun `with no detail recorded, nothing is quoted and nothing is invented`() {
        val sentence = checkNotNull(assistantAvailabilitySentence(KeyHealth.PROBLEM_RATE_LIMITED, ""))
        assertFalse("an absent detail must not become a fabricated one", sentence.contains("Gemini said"))
    }

    @Test
    fun `a rejected key is stated definitely, because 401 and 403 are not ambiguous`() {
        val sentence = checkNotNull(
            assistantAvailabilitySentence(KeyHealth.PROBLEM_INVALID, "HTTP 403 opening the Gemini Live socket"),
        )
        assertTrue(sentence.contains("rejected the key"))
        assertFalse("this one is not a quota question, so it must not muddy it", sentence.contains("quota"))
        assertTrue(sentence.contains("still works by hand"))
    }
}
