package com.kevin.legion.service.cascade

import com.kevin.legion.service.Farewells
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The first turn after a fresh install on the cloud voice (2026-11-21, voice-local-clips): nothing has
 * ever been rendered, the cache is empty, and still an acknowledgement is in hand, from the clips
 * shipped in the APK, with no request to the voice. Before the bundle this turn logged
 * `backchannel_skip reason=no_clip_fits`.
 */
@RunWith(RobolectricTestRunner::class)
class FreshInstallAckTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requests = AtomicInteger(0)
    private val voice = GeminiStreamTransport { _, _, _ ->
        requests.incrementAndGet()
        StreamOutcome.Network("offline")
    }

    @Before fun freshInstall() {
        val app = RuntimeEnvironment.getApplication()
        File(app.cacheDir, "scripted-tts").deleteRecursively()
        File(app.filesDir, "scripted-tts").deleteRecursively()
        KeptLineAudio.install(app)
        KeptLineAudio.SHARED.clear()
    }

    @Test fun `the first turn on the cloud voice has an acknowledgement in hand`() = runBlocking {
        val tts = GeminiTts(voice, scope, keepLines = ScriptedLines.KEEP)
        val picked = AckClips.pick(tts, RecentLines(Backchannel.ROTATION), 0)
        assertNotNull("no acknowledgement in hand on a fresh install (no_clip_fits)", picked)
        assertTrue(Backchannel.clipFits(picked!!.chosen.ms))
        assertEquals("played from the APK, not asked of the voice", 0, requests.get())
    }

    @Test fun `every wait line, goodbye, the missed and the voice-lost line are in hand too`() {
        val tts = GeminiTts(voice, scope, keepLines = ScriptedLines.KEEP)
        val lines = WaitFillers.LINES + Farewells.SPOKEN + ScriptedLines.MISSED + ScriptedLines.VOICE_LOST
        for (line in lines) assertTrue(line, tts.inHand(SpokenText.forSpeech(line)))
        for (line in Backchannel.LINES + WaitFillers.LINES) {
            assertTrue("$line take 1", tts.inHandTake(SpokenText.forSpeech(line), 1))
        }
        assertEquals(0, requests.get())
    }
}
