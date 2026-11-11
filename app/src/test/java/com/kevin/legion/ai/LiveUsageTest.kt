package com.kevin.legion.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Voice cost audit 2026-11-10, F7: the per-turn usage line and the per-generation fold. */
@RunWith(RobolectricTestRunner::class)
class LiveUsageTest {

    private fun report(json: String) = LiveUsage.parse(JSONObject(json))

    @Test
    fun `the usage line carries counts by modality and thoughts, nothing else`() {
        val u = report(
            """{"promptTokenCount":24310,"responseTokenCount":95,"thoughtsTokenCount":40,
               "totalTokenCount":24445,
               "promptTokensDetails":[{"modality":"TEXT","tokenCount":24100},{"modality":"AUDIO","tokenCount":210}],
               "responseTokensDetails":[{"modality":"AUDIO","tokenCount":95}]}""",
        )
        assertEquals(
            "prompt=24310 prompt_text=24100 prompt_audio=210 reply=95 reply_audio=95 thoughts=40 total=24445",
            u.auditFields(),
        )
    }

    @Test
    fun `an absent count is left out, never written as zero`() {
        val u = report("""{"totalTokenCount":12}""")
        assertNull(u.prompt)
        assertEquals("total=12", u.auditFields())
        assertEquals("empty=true", report("{}").auditFields())
    }

    @Test
    fun `candidatesTokenCount is read when responseTokenCount is absent`() {
        assertEquals(7, report("""{"candidatesTokenCount":7}""").response)
    }

    @Test
    fun `repeat reports inside one generation are maxed, then committed once at the boundary`() {
        val fold = LiveUsageFold()
        assertNull(fold.report(report("""{"promptTokenCount":100,"responseTokenCount":5,"totalTokenCount":105}""")))
        assertNull(fold.report(report("""{"promptTokenCount":100,"responseTokenCount":9,"totalTokenCount":109}""")))
        val done = fold.boundary()!!
        assertEquals(100, done.prompt)
        assertEquals(9, done.response)
        assertNull("nothing left after the boundary", fold.boundary())
    }

    @Test
    fun `a report with a new prompt count starts the next generation and returns the last`() {
        // A report that arrives in its own message after its turnComplete passed must not be maxed
        // into the next generation's report.
        val fold = LiveUsageFold()
        fold.report(report("""{"promptTokenCount":100,"totalTokenCount":110}"""))
        val first = fold.report(report("""{"promptTokenCount":180,"totalTokenCount":190}"""))
        assertEquals(110, first!!.total)
        assertEquals(190, fold.boundary()!!.total)
    }
}
