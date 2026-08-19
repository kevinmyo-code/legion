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
        ProactivePreferences.setMuted(context, false)
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
        ProactivePreferences.setMuted(context, false)
    }

    /** Sets onboarding to INCOMPLETE by clearing the on-file Gemini key and re-caching it. */
    private fun makeOnboardingIncomplete() {
        context.getSharedPreferences("companion_profile", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
        GeminiKeyProvider.init(context)
    }

    @Test
    fun `blocked while onboarding is incomplete`() {
        makeOnboardingIncomplete()
        assertFalse(ProactiveBus.speakIfAllowed(context, "prompt"))
    }

    @Test
    fun `blocked while a conversation turn is busy`() {
        ConversationState.setBusy(true)
        assertFalse(ProactiveBus.speakIfAllowed(context, "prompt"))
    }

    @Test
    fun `blocked while muted`() {
        ProactivePreferences.setMuted(context, true)
        assertFalse(ProactiveBus.speakIfAllowed(context, "prompt"))
    }

    @Test
    fun `speaks and returns true when every gate is clear`() = runBlocking {
        val received = mutableListOf<String>()
        val job = launch { ProactiveBus.requestSpeak.collect { received.add(it) } }
        yield() // let the collector start before we emit

        val spoke = ProactiveBus.speakIfAllowed(context, "all clear prompt")

        assertTrue(spoke)
        withTimeout(1_000) { while (received.isEmpty()) yield() }
        job.cancel()
        assertEquals(listOf("all clear prompt"), received)
    }

    @Test
    fun `speakSolicited emits regardless of mute`() = runBlocking {
        ProactivePreferences.setMuted(context, true)
        val received = mutableListOf<String>()
        val job = launch { ProactiveBus.requestSpeak.collect { received.add(it) } }
        yield()

        ProactiveBus.speakSolicited("solicited prompt")

        withTimeout(1_000) { while (received.isEmpty()) yield() }
        job.cancel()
        assertEquals(listOf("solicited prompt"), received)
    }
}
