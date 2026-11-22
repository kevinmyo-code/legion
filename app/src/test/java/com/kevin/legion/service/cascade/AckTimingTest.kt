@file:Suppress("MaxLineLength") // test fixtures read best on one line

package com.kevin.legion.service.cascade

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.sin

/**
 * The guard on the instant acknowledgement (2026-11-21). The end-of-turn path the session runs
 * ([CascadeSession] `scheduleBackchannel` then `playBackchannel`: the filler job, [Backchannel.DELAY_MS],
 * [AckClips.pick] from memory) is run whole here and timed on a clock: from the end-of-turn decision to
 * the first sample handed to the player is at most [FillerTiming.ACK_MAX_START_MS], for a voice whose
 * clips are in memory (the cloud voice's kept lines), the laptop voice with its clips saved, and on
 * Bluetooth (the route only adds its settle AFTER the clip, pinned below). Invented audio only.
 */
@RunWith(RobolectricTestRunner::class)
class AckTimingTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lines = Backchannel.LINES.map { SpokenText.forSpeech(it) }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `the acknowledgement's delay is 0 and its latest start 150 ms (raise either and this fails)`() {
        assertEquals(0L, FillerTiming.ACK_DELAY_MS)
        assertEquals(150L, FillerTiming.ACK_MAX_START_MS)
        assertEquals(FillerTiming.ACK_DELAY_MS, Backchannel.DELAY_MS)
    }

    /** 300 ms of an invented tone at 24 kHz: a clip that passes the length gate once trimmed. */
    private fun tone(): ByteArray {
        val n = 7_200
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (sin(2 * PI * 300.0 * i / 24_000) * 6_000).toInt()
            out[2 * i] = (v and 0xff).toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

    /** A voice whose clips are already in memory, as the cloud voice's kept lines are. */
    private inner class MemoryVoice : TextToSpeech {
        override val sampleRate = 24_000
        override val streamsText = false
        override fun inHand(text: String) = text in lines
        override fun speak(text: String): SpeechClip = object : SpeechClip {
            override val id = 2
            override val text = text
            override val frames: ReceiveChannel<ByteArray> = Channel<ByteArray>(1).also {
                it.trySend(tone())
                it.close()
            }
            override fun cancel() = Unit
            override suspend fun outcome(): TtsOutcome = TtsOutcome.Done(300, 0)
        }
        override fun openStream(): StreamingSpeechClip = error("not used")
    }

    /**
     * The session's own end-of-turn code ([AckRun], the same class `playBackchannel` runs), on the test's
     * virtual clock: ms from the decision to the first sample handed to the player. The session's
     * steps are its checks and its clip ([AckClips.pick], real); here the checks pass and the play
     * records the time.
     */
    private suspend fun TestScope.ackStartMs(tts: TextToSpeech): Long {
        val eot = testScheduler.currentTime
        var handedAt = -1L
        val steps = object : AckRun.Steps {
            override suspend fun stillDue(): (() -> Boolean)? = { true }
            override suspend fun pick() = AckClips.pick(tts, RecentLines(Backchannel.ROTATION), 0)
            override fun noClip() = Unit
            override fun started(picked: AckClips.Picked) = Unit
            override suspend fun play(pcm: ByteArray) {
                if (pcm.isNotEmpty()) handedAt = testScheduler.currentTime
            }
            override suspend fun after() = Unit
        }
        AckRun({ testScheduler.currentTime }, Mutex(), steps).run(eot)
        assertTrue("no acknowledgement was handed to the player", handedAt >= 0)
        return handedAt - eot
    }

    @Test
    fun `a voice with its clips in memory starts the acknowledgement within 150 ms of end of turn`() = runTest {
        val ms = ackStartMs(MemoryVoice())
        assertTrue("$ms ms", ms <= FillerTiming.ACK_MAX_START_MS)
    }

    @Test
    fun `the laptop voice with its clips saved starts it within 150 ms too, with no request`() = runTest {
        val kept = ConcurrentHashMap<String, ByteArray>()
        lines.forEach { kept["laptop|m|v|24000|$it"] = tone() }
        val sent = mutableListOf<String>()
        val wire = WireConnector { _, _, listener ->
            object : WireSocket {
                override fun sendText(text: String): Boolean {
                    val m = JSONObject(text)
                    sent += m.getString("type")
                    if (m.getString("type") == "hello") {
                        listener.onText(
                            JSONObject().put("type", "ready").put("protocol", 1)
                                .put("stt", JSONObject().put("sample_rate", 16000))
                                .put("tts", JSONObject().put("sample_rate", 24000).put("model", "m").put("default_voice", "v"))
                                .toString(),
                        )
                    }
                    return true
                }
                override fun sendBinary(bytes: ByteArray) = true
                override fun close(code: Int, reason: String) = Unit
            }
        }
        val c = LaptopVoiceConnection(
            wire, LaptopVoiceConfig("http://127.0.0.1:8765", "invented-token"), "legion-phone/test", scope,
            keepLines = lines.toSet(), kept = kept,
        )
        withContext(Dispatchers.Default) { c.open(1_000) }
        val ms = ackStartMs(c)
        assertTrue("$ms ms", ms <= FillerTiming.ACK_MAX_START_MS)
        assertTrue("no live request for a saved clip", "tts.speak" !in sent)
    }

    @Test
    fun `the session runs this same code, and nothing on its path waits but the named cue wait`() {
        val s = File(sourceRoot(), "service/cascade/CascadeSession.kt").readText()
        fun body(start: String, end: String) = s.substring(s.indexOf(start), s.indexOf(end))
        assertTrue(s.contains("fillerJobs.startBackchannel { playBackchannel(seq, eotAtMs) }"))
        val play = body("private suspend fun playBackchannel(", "private fun ackSteps(")
        assertTrue(play.contains("AckRun(System::currentTimeMillis, playGate, ackSteps(seq, eotAtMs, b, p)).run(eotAtMs)"))
        assertFalse(play.contains("delay("))
        // The steps: the only wait before the clip is the cue wait inside backchannelStillDue; the route's
        // settle is in after(), once the clip has played.
        val steps = body("private fun ackSteps(", "private fun noteAckStarted(")
        val settle = steps.indexOf("delay(MicReopen.settleMs(")
        assertEquals("one wait in the steps", 1, steps.split("delay(").size - 1)
        assertTrue(settle > steps.indexOf("override suspend fun after()"))
        val due = body("private suspend fun backchannelStillDue(", "private fun answerTextIn(")
        assertEquals("only the cue wait", listOf("cueWait)"), due.split("delay(").drop(1).map { it.substringBefore(")") + ")" })
        val clip = body("private suspend fun playAckClip(", "/** The clip whose audio [clipListen] keeps")
        assertFalse(clip.contains("delay("))
        val started = body("private fun noteAckStarted(", "/** This turn's first-sound line")
        assertFalse(started.contains("delay("))
        // AckRun's one wait is the named delay from the end-of-turn decision.
        val run = File(sourceRoot(), "service/cascade/AckRun.kt").readText()
        assertEquals(1, run.split("delay(").size - 1)
        assertTrue(run.contains("delay((eotAtMs + Backchannel.DELAY_MS - now()).coerceAtLeast(0))"))
    }

    private fun sourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/java/com/kevin/legion")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("source root not found")
    }
}
