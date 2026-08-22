package com.kevin.legion.service

import com.kevin.legion.ai.PROACTIVE_CLAUSE
import com.kevin.legion.data.local.ProactiveRaiseRow
import com.kevin.legion.data.local.ProactiveSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * The pure, `Context`-free half of the proactive gate: the quiet-hours window, the day boundary the
 * cap counts from, the raise contract's own invariants, and the compulsion test applied to the
 * shipped rule set.
 *
 * **The suppression window, quiet hours and the cap ARE covered here**, through
 * [ProactiveBus.decideOnHistory] - the pure decision split out of `speakIfAllowed` on 2026-08-21
 * precisely so they could be. They were briefly untestable: once the gate began reading Room, the
 * Robolectric `ProactiveBusTest` died on `Illegal connection pointer`, because `CarDatabase` is a
 * process-wide singleton while Robolectric resets its SQLite per test (the same gap CLAUDE.md §10
 * records for `LedgerController` and `PantryController`).
 *
 * **What is still NOT covered, said plainly rather than implied by a green suite:** the WIRING -
 * that `speakIfAllowed` fetches the right rows and passes them to [ProactiveBus.decideOnHistory]
 * with the right arguments. That is three single-statement DAO calls sitting directly above the
 * call, inspectable by reading them, and nothing here would catch it if one were swapped.
 */
class ProactiveGateRulesTest {

    // ------------------------------------------------------------------- quiet hours

    @Test
    fun `the night window wraps midnight instead of being empty`() {
        // The bug this exists for: `hour in 22 until 7` is an EMPTY range, so a naive
        // implementation never fires and nothing looks broken.
        assertTrue(ProactiveBus.isQuietHour(LocalTime.of(23, 30)))
        assertTrue(ProactiveBus.isQuietHour(LocalTime.of(2, 0)))
        assertTrue(ProactiveBus.isQuietHour(LocalTime.of(6, 59)))
    }

    @Test
    fun `daytime is not quiet`() {
        assertFalse(ProactiveBus.isQuietHour(LocalTime.of(7, 0)))
        assertFalse(ProactiveBus.isQuietHour(LocalTime.of(13, 0)))
        assertFalse(ProactiveBus.isQuietHour(LocalTime.of(21, 59)))
    }

    @Test
    fun `wellbeing may speak at night, because the founding nudge lives there`() {
        // Kevin, 2026-08-16: "it's past 10pm, perhaps rest is in order." A window that muted the
        // night would kill the line this whole map was chartered to build.
        assertTrue(ProactiveCategory.WELLBEING in ProactiveBus.QUIET_HOURS_EXEMPT)
        assertTrue(ProactiveCategory.SAFETY in ProactiveBus.QUIET_HOURS_EXEMPT)
        assertFalse(ProactiveCategory.DIGEST in ProactiveBus.QUIET_HOURS_EXEMPT)
        assertFalse(ProactiveCategory.FLEET in ProactiveBus.QUIET_HOURS_EXEMPT)
        assertFalse(ProactiveCategory.TIMING in ProactiveBus.QUIET_HOURS_EXEMPT)
    }

    // ------------------------------------------------------------------- the cap

    @Test
    fun `the cap counts a calendar day, not a rolling window`() {
        // A rolling 24h would let last night's three suppress this morning's first.
        val nineAm = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 9)
            set(java.util.Calendar.MINUTE, 0)
        }.timeInMillis
        val start = ProactiveBus.startOfToday(nineAm)
        assertTrue(start <= nineAm)
        assertTrue(nineAm - start < 24L * 60 * 60 * 1000)
    }

    @Test
    fun `safety is the uncapped category and there is exactly one`() {
        assertEquals(ProactiveCategory.SAFETY, ProactiveBus.UNCAPPED_CATEGORY)
        assertEquals(3, ProactiveBus.DAILY_SPOKEN_CAP)
    }

    // ------------------------------------------------------------------- the raise contract

    @Test
    fun `a raise with no facts is refusable`() {
        val raise = ProactiveRaise(
            ruleId = "test_rule",
            category = ProactiveCategory.TIMING,
            reason = "something happened",
            facts = "",
            prompt = "(System: say something.)",
        )
        assertFalse(raise.statesItsFacts)
    }

    @Test
    fun `a raise carrying facts states them`() {
        val raise = ProactiveRaise(
            ruleId = "test_rule",
            category = ProactiveCategory.TIMING,
            reason = "something happened",
            facts = "the calendar was read and is clear for 12 hours",
            prompt = "(System: say something.)",
        )
        assertTrue(raise.statesItsFacts)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a raise without a reason cannot be built - it is what why-did-you-say-that reads`() {
        ProactiveRaise(
            ruleId = "test_rule",
            category = ProactiveCategory.TIMING,
            reason = "  ",
            facts = "a fact",
            prompt = "(System: say something.)",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a raise without a ruleId cannot be built - it is the suppression key`() {
        ProactiveRaise(
            ruleId = "",
            category = ProactiveCategory.TIMING,
            reason = "something happened",
            facts = "a fact",
            prompt = "(System: say something.)",
        )
    }

    // ------------------------------------------------------------------- the compulsion test

    /**
     * CLAUDE.md §7's compulsion test, clause (c), applied to the shipped register clause:
     * **never reference his absence, his streak, or his engagement with the app.**
     *
     * Honest about its own reach, exactly as ticket 03's resolution is: this checks that the clause
     * INSTRUCTS against the failure. It cannot check that the model obeys, and clauses (b) and (c)
     * stay human-reviewed. `AriaBrainHonestyClauseTest` guards `CANNOT_CLAUSE` the same way and its
     * doc says the same thing.
     */
    @Test
    fun `the proactive clause forbids referencing a previous attempt and time away`() {
        val clause = PROACTIVE_CLAUSE.lowercase()
        assertTrue("must forbid mentioning a previous raise", clause.contains("never mention that you have raised something before"))
        assertTrue("must forbid escalating tone", clause.contains("never escalate your tone"))
        assertTrue("must forbid remarking on time away", clause.contains("how long they have been away"))
        assertTrue("must forbid characterising an untouched goal", clause.contains("how long it has gone untouched"))
        assertTrue("must require one line, not a paragraph", clause.contains("one short line"))
        assertTrue("must offer rather than instruct", clause.contains("offer, never instruct"))
    }

    @Test
    fun `the proactive clause is composed into the shared instructions`() {
        // The whole point of file scope: a persona cannot omit it.
        assertTrue(com.kevin.legion.ai.SHARED_INSTRUCTIONS.contains(PROACTIVE_CLAUSE))
    }

    // ------------------------------------------------------------------- decideOnHistory

    private fun raise(category: ProactiveCategory, ruleId: String = "test_rule") = ProactiveRaise(
        ruleId = ruleId,
        category = category,
        reason = "a test raise fired",
        facts = "test facts",
        prompt = "(System: say the thing.)",
    )

    private fun row(ruleId: String, at: Long, declined: Boolean) = ProactiveRaiseRow(
        ruleId = ruleId,
        category = ProactiveCategory.WELLBEING.key,
        reason = "a test raise fired",
        spokenAt = at,
        declined = declined,
    )

    private val noon: LocalTime = LocalTime.of(12, 0)
    private val night: LocalTime = LocalTime.of(23, 0)

    @Test
    fun `a brushed-off rule stays quiet for the suppression window`() {
        val now = 1_000_000_000L
        val justDeclined = row("test_rule", now - 1_000, declined = true)
        assertEquals(
            ProactiveBus.RaiseOutcome.Suppressed,
            ProactiveBus.decideOnHistory(raise(ProactiveCategory.WELLBEING), now, justDeclined, 0, noon),
        )
    }

    @Test
    fun `the same rule returns once the window has passed`() {
        val now = 1_000_000_000L
        val old = row("test_rule", now - ProactiveBus.DECLINE_SUPPRESSION_MS - 1, declined = true)
        assertNull(
            ProactiveBus.decideOnHistory(raise(ProactiveCategory.WELLBEING), now, old, 0, noon),
        )
    }

    @Test
    fun `a previous raise that was NOT declined suppresses nothing`() {
        // Speaking is not a reason to stay quiet. Only a brush-off is.
        val now = 1_000_000_000L
        val spokenNotDeclined = row("test_rule", now - 1_000, declined = false)
        assertNull(
            ProactiveBus.decideOnHistory(raise(ProactiveCategory.WELLBEING), now, spokenNotDeclined, 0, noon),
        )
    }

    @Test
    fun `a capped category is refused once the day's budget is spent`() {
        assertEquals(
            ProactiveBus.RaiseOutcome.OverDailyCap,
            ProactiveBus.decideOnHistory(
                raise(ProactiveCategory.TIMING), 1_000L, null, ProactiveBus.DAILY_SPOKEN_CAP, noon,
            ),
        )
    }

    @Test
    fun `one under the cap still speaks`() {
        assertNull(
            ProactiveBus.decideOnHistory(
                raise(ProactiveCategory.TIMING), 1_000L, null, ProactiveBus.DAILY_SPOKEN_CAP - 1, noon,
            ),
        )
    }

    @Test
    fun `safety speaks past the cap - a warning must never lose its slot to a nudge`() {
        assertNull(
            ProactiveBus.decideOnHistory(
                raise(ProactiveCategory.SAFETY), 1_000L, null, ProactiveBus.DAILY_SPOKEN_CAP * 10, noon,
            ),
        )
    }

    @Test
    fun `safety speaks at 3am`() {
        assertNull(
            ProactiveBus.decideOnHistory(
                raise(ProactiveCategory.SAFETY), 1_000L, null, 0, LocalTime.of(3, 0),
            ),
        )
    }

    @Test
    fun `wellbeing speaks at night, because that is where the rest nudge lives`() {
        assertNull(
            ProactiveBus.decideOnHistory(raise(ProactiveCategory.WELLBEING), 1_000L, null, 0, night),
        )
    }

    @Test
    fun `fleet is refused at night`() {
        assertEquals(
            ProactiveBus.RaiseOutcome.QuietHours,
            ProactiveBus.decideOnHistory(raise(ProactiveCategory.FLEET), 1_000L, null, 0, night),
        )
    }

    @Test
    fun `suppression is checked before quiet hours and the cap`() {
        // Ordering matters for what the DELIVERY layer does next: a suppressed raise was answered
        // already, while a quiet-houred one still deserves a notification. If these swapped, a
        // brushed-off nudge would come back as a notification every night.
        val now = 1_000_000_000L
        val justDeclined = row("test_rule", now - 1_000, declined = true)
        assertEquals(
            ProactiveBus.RaiseOutcome.Suppressed,
            ProactiveBus.decideOnHistory(
                raise(ProactiveCategory.FLEET), now, justDeclined, ProactiveBus.DAILY_SPOKEN_CAP, night,
            ),
        )
    }

    // ------------------------------------------------------------------- the mic exception

    /**
     * Opening the microphone from a proactive line is a deliberate, narrow exception - every other
     * raise is speak-only, and a nudge that silently opened the mic each time it fired would be a
     * listening device with a reason attached.
     *
     * This asserts the DEFAULT, which is the half a future raise could get wrong by accident: a new
     * `ProactiveRaise` does not listen unless someone deliberately says so. It cannot assert that
     * `incoming_call` is the only opted-in raise, because the raises are built inline at their call
     * sites rather than registered - that is what the raise registry would give us, and it does not
     * exist yet.
     */
    @Test
    fun `a raise does not open the microphone unless it deliberately opts in`() {
        val ordinary = ProactiveRaise(
            ruleId = "test_rule",
            category = ProactiveCategory.TIMING,
            reason = "a test raise fired",
            facts = "test facts",
            prompt = "(System: say the thing.)",
        )
        assertFalse(ordinary.listensForReply)

        val asking = ordinary.copy(listensForReply = true)
        assertTrue(asking.listensForReply)
    }

    // ------------------------------------------------------------------- read-through

    /**
     * The sitrep's news section is an LLM summary of Kevin's Gmail, and ticket 08 call 4 rejected
     * "store the summary, drop the bodies" **explicitly**. Two paths could have written it into
     * `episodic_turns` and from there into `CompanionMemory`:
     *
     *  - asking for a sitrep by voice - closed by adding `get_sitrep` to
     *    `LiveToolbox.EPISODIC_EXCLUDED_TOOLS`;
     *  - **a SCHEDULED sitrep** - which calls no tool, so the tool-keyed gate cannot see it at all.
     *    Closed by [ProactiveRaise.carriesReadThroughContent].
     *
     * The second was live and was not caught by a green suite: an unanswered raise happens to be
     * safe because `captureEpisodicTurn` returns early on a blank driver turn, so the leak only
     * opened the moment Kevin replied. **A narrow accident is not a guarantee**, which is what these
     * two assertions are for.
     */
    @Test
    fun `get_sitrep is excluded from episodic capture, like the mail tools`() {
        assertTrue(LiveToolbox.EPISODIC_EXCLUDED_TOOLS.contains("get_sitrep"))
        // The originals must not have been displaced by the addition.
        assertTrue(LiveToolbox.EPISODIC_EXCLUDED_TOOLS.contains("search_mail"))
        assertTrue(LiveToolbox.EPISODIC_EXCLUDED_TOOLS.contains("read_mail"))
    }

    @Test
    fun `a raise can declare it carries read-through content, and does not by default`() {
        val ordinary = ProactiveRaise(
            ruleId = "test_rule",
            category = ProactiveCategory.TIMING,
            reason = "a test raise fired",
            facts = "test facts",
            prompt = "(System: say the thing.)",
        )
        assertFalse("the default must be safe-to-remember", ordinary.carriesReadThroughContent)
        assertTrue(ordinary.copy(carriesReadThroughContent = true).carriesReadThroughContent)
    }

    @Test
    fun `the bus carries the read-through flag rather than dropping it`() {
        val req = ProactiveBus.SpeakRequest("prompt", listensForReply = false, carriesReadThroughContent = true)
        assertTrue(req.carriesReadThroughContent)
        assertFalse(ProactiveBus.SpeakRequest("prompt").carriesReadThroughContent)
    }

    // ------------------------------------------------------------------- categories

    @Test
    fun `an upgrade carries exactly the categories the existing raises use`() {
        // Ticket 04 call 3: Kevin's effective behaviour must not change on upgrade. Wellbeing and
        // Digest stay off because nothing raises into them - turning on a switch that governs
        // nothing would be a silent promise.
        assertEquals(
            setOf(ProactiveCategory.SAFETY, ProactiveCategory.TIMING, ProactiveCategory.FLEET),
            ProactiveCategory.CARRIED_OVER_ON_UPGRADE,
        )
    }

    @Test
    fun `every category now has content, and each flag flipped in the commit that earned it`() {
        // Ticket 22: DIGEST got its first content (the scheduled sitrep) and its own hasContent
        // flipped to true in that same commit. goal-plans ticket 05: WELLBEING got its first
        // content (the scheduled wellbeing digest,
        // com.kevin.legion.wellbeing.WellbeingDigestAlarmReceiver) and its own hasContent flipped
        // to true in that same commit - the last category with nothing raising into it is now
        // gone, and a future sixth category must repeat the same discipline: flag flips WITH the
        // first raise, never before.
        val empty = ProactiveCategory.entries.filter { !it.hasContent }.toSet()
        assertEquals(emptySet<ProactiveCategory>(), empty)
    }

    @Test
    fun `category keys are stable and unique - they are storage keys`() {
        val keys = ProactiveCategory.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertFalse("master is the switch key, never a category", keys.contains(ProactiveSetting.MASTER_KEY))
        ProactiveCategory.entries.forEach {
            assertEquals(it, ProactiveCategory.fromKey(it.key))
        }
    }
}
