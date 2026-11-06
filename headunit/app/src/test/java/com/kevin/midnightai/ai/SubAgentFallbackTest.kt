package com.kevin.midnightai.ai

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The head unit's SubAgent model choice (2026-11-06), mirroring the phone's ticket-03 fallback:
 * Flash-Lite 3.5 first, `gemini-3.8-flash` once on a 429 or a 500/503, never on a bad key, never
 * twice, and a body that carries none of the knobs 3.8 Flash rejects. Robolectric only for
 * `android.util.Log` on the fallback line.
 */
@RunWith(RobolectricTestRunner::class)
class SubAgentFallbackTest {

    private val calls = mutableListOf<String>()

    private fun agent(vararg answers: SubAgent.HttpOutcome, model: String = SubAgent.DEFAULT_MODEL): SubAgent {
        val queue = ArrayDeque(answers.toList())
        return SubAgent("sys", false, model) { m, _ ->
            calls += m
            queue.removeFirst()
        }
    }

    private val ok = SubAgent.HttpOutcome.Ok("""{"candidates":[{"content":{"parts":[{"text":"hi"}]}}]}""")
    private fun err(code: Int, body: String = "") = SubAgent.HttpOutcome.HttpError(code, body)

    @Test
    fun `the models are the phone's`() {
        assertEquals("gemini-3.5-flash-lite", SubAgent.DEFAULT_MODEL)
        assertEquals("gemini-3.8-flash", SubAgent.FALLBACK_MODEL)
    }

    @Test
    fun `a 429 or a 503 retries once on 3_8 Flash`() = runBlocking {
        assertTrue(agent(err(429), ok).postRaw(JSONObject()) is SubAgent.HttpOutcome.Ok)
        assertEquals(listOf(SubAgent.DEFAULT_MODEL, SubAgent.FALLBACK_MODEL), calls)
        calls.clear()
        assertTrue(agent(err(503), ok).postRaw(JSONObject()) is SubAgent.HttpOutcome.Ok)
        assertEquals(listOf(SubAgent.DEFAULT_MODEL, SubAgent.FALLBACK_MODEL), calls)
    }

    @Test
    fun `never a second fallback, and the fallback's error comes back as it came`() = runBlocking {
        val out = agent(err(429), err(503)).postRaw(JSONObject())
        assertEquals(503, (out as SubAgent.HttpOutcome.HttpError).code)
        assertEquals(2, calls.size)
    }

    @Test
    fun `a bad key or an ordinary failure does not fall back`() = runBlocking {
        agent(err(400, "API_KEY_INVALID")).postRaw(JSONObject())
        agent(err(403)).postRaw(JSONObject())
        agent(err(404)).postRaw(JSONObject())
        assertEquals(List(3) { SubAgent.DEFAULT_MODEL }, calls)
    }

    @Test
    fun `an agent already on 3_8 Flash does not retry itself`() = runBlocking {
        agent(err(429), model = SubAgent.FALLBACK_MODEL).postRaw(JSONObject())
        assertEquals(listOf(SubAgent.FALLBACK_MODEL), calls)
    }

    @Test
    fun `the bodies sent carry no temperature and no thinkingConfig`() = runBlocking {
        val bodies = mutableListOf<JSONObject>()
        val agent = SubAgent("sys", true, SubAgent.DEFAULT_MODEL) { _, body -> bodies += body; ok }
        agent.ask("context", "question")
        agent.askTyped("context", "question")
        assertEquals(2, bodies.size)
        bodies.forEach { body ->
            val text = body.toString()
            assertFalse(text, text.contains("\"temperature\""))
            assertFalse(text, text.contains("\"thinkingConfig\""))
        }
    }
}
