package com.kevin.midnightai.service

import com.kevin.midnightai.diag.ConvoAudit
import com.kevin.midnightai.diag.ConvoLatency
import com.kevin.midnightai.diag.ConvoSessionGate
import com.kevin.midnightai.diag.DriveLog
import com.kevin.midnightai.diag.DriveLogStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The 2026-11-09 audit question: does a spoken turn on the head unit reach the conversation record?
 * Real `serverContent` message shapes go through the calls `GeminiLiveSession.handleServerContent`
 * makes ([LiveTranscript] -> [ConvoAudit] -> [ConvoSessionGate]) into a real [DriveLogStore], and the
 * sealed batch (what `DriveLogUploader` posts as `reason=conversation`) must hold the turn.
 */
class LiveTranscriptSpoolTest {
    @get:Rule val tmp = TemporaryFolder()

    private val store by lazy { DriveLogStore(File(tmp.root, "convolog")) }
    private val gate by lazy {
        ConvoSessionGate(sink = { t, l, _ ->
            store.append(listOf(DriveLog.threadtime("11-09 08:07:22.929", 1, 2, t, l)))
        })
    }
    private val audit by lazy { ConvoAudit(sink = { gate.write(ConvoAudit.TAG, it) }) }
    private val latency by lazy { ConvoLatency(sink = { gate.write(ConvoLatency.TAG, it) }) }

    /** Mirrors the transcript branches and the turnComplete audit line of `handleServerContent`. */
    private fun serverContent(json: String) {
        val content = JSONObject(json)
        LiveTranscript.input(content)?.let { (chunk, finished) -> audit.user(chunk, final = finished) }
        LiveTranscript.output(content)?.let { audit.assistant(it) }
        if (content.optBoolean("turnComplete")) audit.turnComplete()
    }

    @Test
    fun `the wire fields are read like the phone reads them`() {
        assertEquals(
            "hi" to true,
            LiveTranscript.input(JSONObject("""{"inputTranscription":{"text":"hi","finished":true}}""")),
        )
        assertEquals("hi" to false, LiveTranscript.input(JSONObject("""{"inputTranscription":{"text":"hi"}}""")))
        assertNull(LiveTranscript.input(JSONObject("""{"inputTranscription":{"text":""}}""")))
        assertNull(LiveTranscript.input(JSONObject("""{"outputTranscription":{"text":"x"}}""")))
        assertEquals("Morning.", LiveTranscript.output(JSONObject("""{"outputTranscription":{"text":"Morning."}}""")))
        assertNull(LiveTranscript.output(JSONObject("""{"modelTurn":{"parts":[]}}""")))
    }

    @Test
    fun `a spoken turn lands in the uploaded conversation batch`() {
        audit.sessionStart("gemini-3.8-live"); latency.start(1_000)
        latency.setupComplete(1_800)
        audit.micOpen("first_open")
        serverContent("""{"inputTranscription":{"text":"Any trouble codes?","finished":true}}""")
        serverContent("""{"outputTranscription":{"text":"None stored."}}""")
        serverContent("""{"outputTranscription":{"text":" Running fine."}}""")
        serverContent("""{"turnComplete":true}""")
        audit.sessionEnd("idle")

        val batch = store.seal()
        assertNotNull(batch)
        val lines = batch!!.file.readLines()
        assertEquals(
            listOf(
                "session_start model=gemini-3.8-live async_set=none",
                "cold_start_ms=800",
                "mic_open reason=first_open",
                "user final=true text=\"Any trouble codes?\"",
                "assistant text=\"None stored.\"",
                "assistant text=\" Running fine.\"",
                "turn_complete",
                "session_end reason=\"idle\"",
            ),
            lines.map { it.substringAfter(": ") },
        )
        assertTrue(lines[3], lines[3].contains(" I LIVE_AUDIT: user "))
        assertTrue(lines[4], lines[4].contains(" I LIVE_AUDIT: assistant "))
    }

    @Test
    fun `a session with no transcript at all seals nothing`() {
        audit.sessionStart("gemini-3.8-live"); latency.start(1_000)
        latency.setupComplete(1_800)
        serverContent("""{"modelTurn":{"parts":[]}}""")
        audit.sessionEnd("Session closed due to client inactivity.")
        assertNull(store.seal())
    }
}
