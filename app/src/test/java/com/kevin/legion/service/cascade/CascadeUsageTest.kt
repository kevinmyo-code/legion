@file:Suppress("MaxLineLength") // test fixtures: invented usage JSON reads best on one line

package com.kevin.legion.service.cascade

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections

/**
 * The cost audit's phone half: one `gemini_usage` line per billed call and one
 * `cascade_usage_summary` per conversation, counts only. `tools/voice_cost.py` prices them (its own
 * tests pin the parsing of exactly these shapes). Invented counts only.
 */
@RunWith(RobolectricTestRunner::class)
class CascadeUsageTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun `a usage line carries counts per modality from usageMetadata, and nothing else`() {
        val usage = JSONObject("""{"promptTokenCount":420,"candidatesTokenCount":12,"thoughtsTokenCount":3,"promptTokensDetails":[{"modality":"AUDIO","tokenCount":320},{"modality":"TEXT","tokenCount":100}]}""")
        val line = CallUsage.of(CallUsage.STT, "model-x", usage, audioMs = 10_000).line()
        assertEquals(
            "gemini_usage kind=stt model=model-x usage=present prompt=420 prompt_text=100 prompt_audio=320 cached=0 " +
                "out=12 out_text=12 out_audio=0 thoughts=3 audio_s=10.00 chars=0 discarded=false",
            line,
        )
    }

    @Test
    fun `a TTS call with no breakdown counts its output as audio`() {
        val usage = JSONObject("""{"promptTokenCount":9,"candidatesTokenCount":250}""")
        val u = CallUsage.of(CallUsage.TTS, "tts-x", usage, audioMs = 10_000, chars = 40, defaultOut = "AUDIO")
        assertEquals(250, u.outAudio)
        assertEquals(0, u.outText)
        assertEquals(9, u.promptText)
    }

    @Test
    fun `no usageMetadata says so and keeps the measurable quantity`() {
        val u = CallUsage.of(CallUsage.TTS, "tts-x", null, audioMs = 1_500, chars = 31, discarded = true)
        assertTrue(u.line().contains("usage=absent"))
        assertTrue(u.line().contains("audio_s=1.50 chars=31 discarded=true"))
        assertTrue(u.copy(failed = true).line().endsWith(" failed=true"))
    }

    @Test
    fun `the session summary sums per kind and model, discarded calls on their own, with the cache`() {
        val ledger = UsageLedger()
        ledger.add(CallUsage.of(CallUsage.LLM, "m", JSONObject("""{"promptTokenCount":1000,"cachedContentTokenCount":900,"candidatesTokenCount":20}""")))
        ledger.add(CallUsage.of(CallUsage.LLM, "m", JSONObject("""{"promptTokenCount":1100,"cachedContentTokenCount":900,"candidatesTokenCount":30}""")))
        ledger.add(CallUsage.of(CallUsage.LLM, "m", null, chars = 12, discarded = true))
        ledger.cacheCreated("m", 900, 3_600)
        val line = ledger.summary("laptop>cloud", turns = 2)
        assertTrue(line, line.startsWith("cascade_usage_summary engine=laptop>cloud turns=2 cache_create=m cache_tokens=900 cache_ttl_s=3600 | "))
        assertTrue(line, line.contains("llm/m calls=2 prompt=2100 prompt_text=2100 prompt_audio=0 cached=1800 out=50"))
        assertTrue(line, line.contains("discarded:llm/m calls=1 prompt=0"))
        assertTrue(line, line.contains("absent=1"))
        // Counts and ids only: nothing that could be his words.
        assertFalse(line.contains("\""))
    }

    @Test
    fun `every cloud STT and TTS request reports its usage, a cancelled one as discarded`() = runBlocking<Unit> {
        val seen = Collections.synchronizedList(mutableListOf<CallUsage>())
        fun parts(part: JSONObject) = JSONObject().put(
            "candidates",
            JSONArray().put(JSONObject().put("content", JSONObject().put("parts", JSONArray().put(part)))),
        )
        fun usageEv(prompt: Int, out: Int) =
            JSONObject().put("usageMetadata", JSONObject().put("promptTokenCount", prompt).put("candidatesTokenCount", out))
        val transport = GeminiStreamTransport { model, _, on ->
            if (model == CloudSpeechWire.TTS_MODEL) {
                on(parts(JSONObject().put("inlineData", JSONObject().put("data", ByteArray(480).toByteString().base64()))))
                on(usageEv(7, 25))
            } else {
                on(parts(JSONObject().put("text", "Invented.")))
                on(usageEv(50, 3))
            }
            StreamOutcome.Ok
        }
        val cloud = CloudSpeech.create(scope, transport, usage = { seen.add(it) })
        val u = cloud.stt.begin()
        u.audio(ByteArray(32 * 500), 0, 32 * 500)
        u.end()
        u.result(2_000)
        val clip = cloud.tts.speak("Invented line.")
        for (f in clip.frames) Unit
        clip.outcome()
        val stt = seen.single { it.kind == CallUsage.STT }
        assertEquals(50, stt.prompt)
        assertEquals(500L, stt.audioMs)
        val tts = seen.single { it.kind == CallUsage.TTS }
        assertEquals(25, tts.outAudio)
        assertEquals(10L, tts.audioMs) // 480 bytes of 24 kHz PCM16
        assertEquals("Invented line.".length, tts.chars)
        assertFalse(tts.discarded)
    }
}
