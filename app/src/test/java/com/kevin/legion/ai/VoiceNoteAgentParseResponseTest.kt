package com.kevin.legion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Network-free coverage of [VoiceNoteAgent.parseResponse] - the raw model text (plus the
 * [AgentResult.Success.truncated] flag) in, a [VoiceNoteAgent.Result] out. Same pattern as
 * [com.kevin.legion.pantry.PantryReceiptAgentTest] exercising `parseAndReconcile` directly.
 *
 * Covers the ticket's own verification list: a failed/unreadable response never produces a
 * [VoiceNoteAgent.Result.Success] (so nothing downstream can ever write a summary against a null
 * transcript - [VoiceNoteAgent.Result.Success] simply has no constructor that allows one), and the
 * output-cap salvage path says in words that a transcript is partial rather than returning a
 * truncated one that reads as complete.
 */
class VoiceNoteAgentParseResponseTest {

    @Test
    fun `a clean full response parses as a complete, non-partial success`() {
        val raw = """{"title": "Standup", "summary": "Quick sync, nothing decided.", "transcript": "Alright let's get started."}"""
        val result = VoiceNoteAgent.parseResponse(raw, truncated = false)

        assertTrue(result is VoiceNoteAgent.Result.Success)
        val success = result as VoiceNoteAgent.Result.Success
        assertEquals("Standup", success.title)
        assertEquals("Quick sync, nothing decided.", success.summary)
        assertEquals("Alright let's get started.", success.transcript)
        assertFalse(success.transcriptPartial)
    }

    @Test
    fun `tolerates a markdown code fence around the object`() {
        val raw = "```json\n{\"title\": \"Note\", \"summary\": \"A thought.\", \"transcript\": \"Just thinking out loud.\"}\n```"
        val result = VoiceNoteAgent.parseResponse(raw, truncated = false)
        assertTrue(result is VoiceNoteAgent.Result.Success)
    }

    @Test
    fun `unreadable response with no truncation reports failure, not a guess`() {
        val result = VoiceNoteAgent.parseResponse("not json at all", truncated = false)
        assertTrue(result is VoiceNoteAgent.Result.Failed)
    }

    @Test
    fun `a response missing transcript is a failure even if it parses as JSON`() {
        // A summary with no transcript behind it is exactly the anchor-chain violation ADR 0041
        // forbids (summary outliving its transcript) - Result.Success has no way to represent
        // this, so the parser must refuse rather than backfilling a blank transcript.
        val raw = """{"title": "Note", "summary": "Something was said."}"""
        val result = VoiceNoteAgent.parseResponse(raw, truncated = false)
        assertTrue(result is VoiceNoteAgent.Result.Failed)
    }

    @Test
    fun `truncated mid-transcript salvages title and summary and marks the transcript partial`() {
        // Simulates finishReason=MAX_TOKENS landing partway through a long transcript string -
        // title and summary (earlier in propertyOrdering) finished; the transcript's closing
        // quote and the object's closing brace never arrived.
        val raw = """{"title": "Quarterly planning", "summary": "Budget and headcount discussed.", "transcript": "Okay so first item is the budget review and"""
        val result = VoiceNoteAgent.parseResponse(raw, truncated = true)

        assertTrue(result is VoiceNoteAgent.Result.Success)
        val success = result as VoiceNoteAgent.Result.Success
        assertEquals("Quarterly planning", success.title)
        assertEquals("Budget and headcount discussed.", success.summary)
        assertTrue(success.transcriptPartial)
        assertTrue(
            "a salvaged transcript must say in words that it is partial, not read as complete",
            success.transcript.contains("truncated"),
        )
        assertTrue(success.transcript.contains("Okay so first item is the budget review and"))
    }

    @Test
    fun `truncated with nothing usable at all is a failure, not an empty success`() {
        val raw = """{"title": "Quarterly pla"""
        val result = VoiceNoteAgent.parseResponse(raw, truncated = true)
        assertTrue(result is VoiceNoteAgent.Result.Failed)
    }

    @Test
    fun `a non-truncated unreadable response never salvages, even if it looks similar`() {
        // Guards against the salvage path accidentally firing on an ordinary parse failure that
        // has nothing to do with the output cap - truncated must be the explicit, measured signal
        // (AgentResult.Success.truncated, sourced from the real API's own finishReason), never
        // inferred from the shape of the text itself.
        val raw = """{"title": "Note", "summary": "Something.", "transcript": "unterminated"""
        val result = VoiceNoteAgent.parseResponse(raw, truncated = false)
        assertTrue(result is VoiceNoteAgent.Result.Failed)
    }

    @Test
    fun `unicode escapes in a salvaged transcript are decoded, not left as literal backslash-u`() {
        // “/” are literal characters IN THIS JVM STRING (Kotlin has no raw-string escape
        // suppression), simulating the JSON text `"She said “Hello” and then` that a
        // real truncated response would carry.
        val raw = "{\"title\": \"Note\", \"summary\": \"Summary.\", \"transcript\": \"She said \\u201cHello\\u201d and then"
        val result = VoiceNoteAgent.parseResponse(raw, truncated = true)
        assertTrue(result is VoiceNoteAgent.Result.Success)
        val success = result as VoiceNoteAgent.Result.Success
        assertTrue(success.transcript.contains("“Hello”"))
        assertFalse(success.transcript.contains("\\u201c"))
    }
}
