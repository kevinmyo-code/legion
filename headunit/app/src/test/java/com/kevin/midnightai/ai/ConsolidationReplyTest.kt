package com.kevin.midnightai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Issue #452: the distill reply now carries the chat's summary; the old shape still parses. */
class ConsolidationReplyTest {

    @Test
    fun `the object form gives the summary and the valid memories`() {
        val raw = """Sure: {"summary": " They talked about the brakes. ", "memories": [
            {"text": "Wants new pads", "importance": 14, "category": "CAR_ANCHORED"},
            {"text": "Bad category", "importance": 3, "category": "gossip"},
            {"text": " ", "importance": 3, "category": "driver"},
            {"text": "Likes jazz", "category": "driver"}]}"""
        val parsed = ConsolidationReply.parse(raw)!!
        assertEquals("They talked about the brakes.", parsed.summary)
        assertEquals(
            listOf(
                ConsolidationReply.Memory("Wants new pads", 10, "car_anchored"),
                ConsolidationReply.Memory("Likes jazz", 5, "driver"),
            ),
            parsed.memories,
        )
    }

    @Test
    fun `a null or empty summary is none`() {
        assertNull(ConsolidationReply.parse("""{"summary": null, "memories": []}""")!!.summary)
        assertNull(ConsolidationReply.parse("""{"summary": "", "memories": []}""")!!.summary)
    }

    @Test
    fun `the legacy bare array still parses, with no summary`() {
        val parsed = ConsolidationReply.parse("""[{"text": "Hates tolls", "importance": 6, "category": "driver"}]""")!!
        assertNull(parsed.summary)
        assertEquals(listOf(ConsolidationReply.Memory("Hates tolls", 6, "driver")), parsed.memories)
    }

    @Test
    fun `garbage is null, so the turns wait for the next pass`() {
        assertNull(ConsolidationReply.parse("I could not do that."))
        assertNull(ConsolidationReply.parse("{not json}"))
    }
}
