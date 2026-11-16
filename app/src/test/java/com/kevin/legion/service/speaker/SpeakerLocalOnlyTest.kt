package com.kevin.legion.service.speaker

import com.kevin.legion.service.conversationlog.ConversationSpool
import com.kevin.legion.service.phonelog.LogcatLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The per-wake speaker score is derived from Kevin's voice and is never uploaded (2026-11-16). It
 * goes to logcat only, under [SpeakerPolicy.LOG_TAG]; both paths that carry log lines to the engine
 * must refuse it: the conversation spool (by tag) and the phone's logcat capture (drops the tag).
 */
class SpeakerLocalOnlyTest {
    private fun line(tag: String, msg: String) = "11-16 14:00:00.000  4242  4243 I $tag: $msg"

    private val scoreLine = line(
        SpeakerPolicy.LOG_TAG,
        SpeakerPolicy.line(SpeakerPolicy.Mode.SHADOW, SpeakerResult.Scored(0.5f, 40), SpeakerPolicy.Band.OPEN),
    )

    @Test
    fun `the speaker tag is not one the conversation spool sends to the engine`() {
        assertEquals("SPEAKER_LOCAL", SpeakerPolicy.LOG_TAG)
        assertFalse(SpeakerPolicy.LOG_TAG in ConversationSpool.TAGS)
        // Sanity: the spool does send LIVE_LATENCY, which is why the score is not logged there.
        assertTrue("LIVE_LATENCY" in ConversationSpool.TAGS)
    }

    @Test
    fun `the phone's logcat capture drops a speaker_check line and keeps others`() {
        assertTrue(scoreLine.contains("speaker_check_ms=40"))
        assertTrue(SpeakerPolicy.LOG_TAG in LogcatLines.LOCAL_ONLY_TAGS)
        assertTrue(LogcatLines.isLocalOnly(scoreLine))
        assertTrue(LogcatLines.isNoise(scoreLine))
        assertFalse(LogcatLines.isNoise(line("LIVE_LATENCY", "reply_ms=1200")))
    }

    @Test
    fun `no speaker source writes through an uploading log path`() {
        val dir = File("src/main/java/com/kevin/legion/service/speaker")
        val files = dir.listFiles { f -> f.extension == "kt" }.orEmpty()
        assertTrue("speaker sources not found from ${File(".").absolutePath}", files.isNotEmpty())
        val uploading = listOf("LiveLatency", "ConversationLog", "CarProbeLog", "PhoneLog", "MidnightEvents")
        for (f in files) {
            val code = f.readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
                .joinToString("\n")
            for (name in uploading) assertFalse("${f.name} uses $name", code.contains(name))
        }
    }
}
