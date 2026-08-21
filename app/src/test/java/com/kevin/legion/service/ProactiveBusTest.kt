package com.kevin.legion.service

import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.ai.GeminiKeyProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [ProactiveBus.speakIfAllowed] and [ProactiveBus.speakSolicited] - the split introduced by
 * `.scratch/proactive-mode/issues/01-one-gate-not-three.md` (2026-08-18) to make the mute choke
 * point structural (raw emit private) instead of a convention three separate authors had to
 * remember. Robolectric only because [CompanionProfile]/[ProactivePreferences] read/write real
 * [android.content.SharedPreferences].
 *
 * **Not covered here: [TelephonyController.isInCall].** It is `var ... private set` with no test
 * seam - `handleState` (the only writer) is private and reached only through a real
 * `PhoneStateListener` callback from the `TelephonyManager`. There is no way to flip it from a
 * unit test without reflection, so the "blocked while in a call" branch of [ProactiveBus.
 * speakIfAllowed] is exercised only by inspection of the source (traced, not tested) - see the
 * coding report for this ticket rather than silently omitting the case.
 *
 * **Scope narrowed 2026-08-21, and the reason matters.** When the five category switches landed,
 * `speakIfAllowed` began reading Room - for the switches, the suppression window and the daily cap -
 * and this class started failing with `IllegalStateException: Illegal connection pointer`.
 * `CarDatabase` hands out a process-wide singleton while Robolectric resets its SQLite between
 * tests, so any Robolectric test reaching Room across class boundaries dies that way. It is the
 * same gap CLAUDE.md §10 already records for `LedgerController` and `PantryController`.
 *
 * **That was a real regression, not flakiness** - a gate that used to be pure acquired a database
 * read. The response was to move the arithmetic somewhere a plain JUnit test can reach it
 * ([ProactiveBus.decideOnHistory], covered in [ProactiveGateRulesTest]) and to keep this class on
 * the checks that run BEFORE the first query. Those checks are deliberately ordered first in
 * `speakIfAllowed` for exactly this reason.
 *
 * **So this class no longer covers the emit-on-success path**, which needs the insert. What that
 * loses is stated rather than quietly dropped: nothing here proves a fully-clear raise reaches the
 * flow. [`speakSolicited emits and is never gated`] still proves the flow itself works.
 */
@RunWith(RobolectricTestRunner::class)
class ProactiveBusTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        // Onboarding complete by default (a real Gemini key on file) so each test only has to
        // flip the ONE gate it's exercising. CompanionProfile.saveGeminiKey degrades to plaintext
        // storage when the Keystore is unavailable (its own doc comment) - exactly the Robolectric
        // case - so this works without a real AndroidKeyStore.
        CompanionProfile.saveGeminiKey(context, "test-key")
        GeminiKeyProvider.init(context)

        ConversationState.setBusy(false)
        // The switches, set directly so no Room call happens - see the class doc. TIMING is on
        // because that is the category every raise in this file uses.
        ProactiveSettings.overrideForTest(master = true, on = setOf(ProactiveCategory.TIMING))
    }

    @After
    fun tearDown() {
        // Leave onboarding incomplete for whichever test runs next in this class or another -
        // GeminiKeyProvider is a process-wide object, so an unreset cached key would leak across
        // test classes in the same JVM fork.
        context.getSharedPreferences("companion_profile", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
        GeminiKeyProvider.init(context)
        ConversationState.setBusy(false)
        ProactiveSettings.resetForTest()
    }

    /** Sets onboarding to INCOMPLETE by clearing the on-file Gemini key and re-caching it. */
    private fun makeOnboardingIncomplete() {
        context.getSharedPreferences("companion_profile", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
        GeminiKeyProvider.init(context)
    }

    /** A minimal but fact-stating raise, so every test here exercises the gate under test rather
     * than tripping the [ProactiveBus.RaiseOutcome.StatedNoFacts] check first. */
    private fun testRaise(prompt: String) = ProactiveRaise(
        ruleId = "test_rule",
        category = ProactiveCategory.TIMING,
        reason = "a test raise fired",
        facts = "test facts",
        prompt = prompt,
    )

    @Test
    fun `blocked while onboarding is incomplete`() = runBlocking {
        makeOnboardingIncomplete()
        assertEquals(ProactiveBus.RaiseOutcome.NotNow, ProactiveBus.speakIfAllowed(context, testRaise("prompt")))
    }

    @Test
    fun `blocked while a conversation turn is busy`() = runBlocking {
        ConversationState.setBusy(true)
        assertEquals(ProactiveBus.RaiseOutcome.NotNow, ProactiveBus.speakIfAllowed(context, testRaise("prompt")))
    }

    @Test
    fun `blocked while the master switch is off`() = runBlocking {
        // Was `blocked while muted`, driving ProactivePreferences. That preference is a MIGRATION
        // SOURCE now and no longer gates anything, so the old test passed or failed for reasons
        // unrelated to what it claimed to check.
        ProactiveSettings.overrideForTest(master = false, on = setOf(ProactiveCategory.TIMING))
        assertEquals(
            ProactiveBus.RaiseOutcome.MutedByUser,
            ProactiveBus.speakIfAllowed(context, testRaise("prompt")),
        )
    }

    @Test
    fun `blocked when the raise's own category is off, even with the master on`() = runBlocking {
        ProactiveSettings.overrideForTest(master = true, on = setOf(ProactiveCategory.FLEET))
        assertEquals(
            ProactiveBus.RaiseOutcome.MutedByUser,
            ProactiveBus.speakIfAllowed(context, testRaise("prompt")),
        )
    }

    @Test
    fun `a raise stating no facts is refused before it can reach the model`() = runBlocking {
        val factless = ProactiveRaise(
            ruleId = "test_rule",
            category = ProactiveCategory.TIMING,
            reason = "a test raise fired",
            facts = "",
            prompt = "(System: mention anything notable coming up.)",
        )
        assertEquals(
            ProactiveBus.RaiseOutcome.StatedNoFacts,
            ProactiveBus.speakIfAllowed(context, factless),
        )
    }

    @Test
    fun `Raised means SPOKEN, and Notified is a different answer`() {
        // An earlier cut returned Raised for both, which made "did it actually get said out loud?"
        // unanswerable - and ReminderAlarmReceiver has to answer exactly that to avoid posting a
        // second notification for one reminder. A single outcome meaning "delivered somehow" is the
        // kind that turns into a wrong claim about what the user heard.
        val spoken: ProactiveBus.RaiseOutcome = ProactiveBus.RaiseOutcome.Raised(1L)
        val posted: ProactiveBus.RaiseOutcome = ProactiveBus.RaiseOutcome.Notified(1L, postedByCaller = false)
        assertFalse(spoken == posted)
        assertTrue(posted is ProactiveBus.RaiseOutcome.Notified)
        assertFalse(posted is ProactiveBus.RaiseOutcome.Raised)
    }

    @Test
    fun `speakSolicited emits and is never gated`() = runBlocking {
        // The user asked, directly. A kill switch that silences an answer to a button someone just
        // pressed is not a kill switch anyone would trust either way.
        ProactiveSettings.overrideForTest(master = false, on = emptySet())
        val received = mutableListOf<ProactiveBus.SpeakRequest>()
        val job = launch { ProactiveBus.requestSpeak.collect { received.add(it) } }
        yield()

        ProactiveBus.speakSolicited("solicited prompt")

        withTimeout(1_000) { while (received.isEmpty()) yield() }
        job.cancel()
        assertEquals(listOf("solicited prompt"), received.map { it.prompt })
        // Solicited speech never opens the mic on its own - the user is already talking.
        assertTrue(received.none { it.listensForReply })
    }
}
