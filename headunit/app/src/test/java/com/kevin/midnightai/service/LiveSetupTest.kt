package com.kevin.midnightai.service

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The head unit's Live setup on gemini-3.8-live (2026-11-06), mirroring the phone's
 * `LiveBlockingBehaviorTest` and `LiveSetupFlagsTest`: the model id, every tool BLOCKING, none of
 * the fields 3.8 rejects, and the root voiceActivity onset signal.
 */
class LiveSetupTest {

    private fun decls() = JSONArray()
        .put(JSONObject().put("name", "get_health").put("description", "read"))
        .put(JSONObject().put("name", "activate_garage").put("description", "write"))
        .put(JSONObject().put("name", "custom").put("behavior", "NON_BLOCKING"))

    private fun setup(vadMode: Boolean = true, subtitles: Boolean = false, decls: JSONArray = decls()) =
        LiveSetup.buildSetupJson(
            systemInstruction = "be Moose",
            functionDeclarations = decls,
            voiceName = "Sulafat",
            vadMode = vadMode,
            subtitles = subtitles,
            silenceDurationMs = 900,
            prefixPaddingMs = 300,
        ).getJSONObject("setup")

    @Test
    fun `the head unit runs the phone's Live model`() {
        assertEquals("models/gemini-3.8-live", LiveSetup.MODEL)
        assertEquals(LiveSetup.MODEL, setup().getString("model"))
    }

    @Test
    fun `every declaration is stamped BLOCKING, an explicit behavior is kept, the input is untouched`() {
        val input = decls()
        val tools = setup(decls = input).getJSONArray("tools")
        assertTrue(tools.getJSONObject(0).has("googleSearch"))
        val stamped = tools.getJSONObject(1).getJSONArray("functionDeclarations")
        assertEquals(3, stamped.length())
        assertEquals("BLOCKING", stamped.getJSONObject(0).getString("behavior"))
        assertEquals("BLOCKING", stamped.getJSONObject(1).getString("behavior"))
        assertEquals("NON_BLOCKING", stamped.getJSONObject(2).getString("behavior"))
        assertFalse("the caller's array is copied, not mutated", input.getJSONObject(0).has("behavior"))
    }

    @Test
    fun `no declarations means no functionDeclarations entry`() {
        val tools = setup(decls = JSONArray()).getJSONArray("tools")
        assertEquals(1, tools.length())
    }

    @Test
    fun `no field 3_8-live rejects is sent, anywhere in the setup`() {
        for (s in listOf(setup(), setup(vadMode = false, subtitles = true))) {
            val text = s.toString()
            LiveSetup.FORBIDDEN_SETUP_KEYS.forEach { key ->
                assertFalse("$key must not be sent", text.contains("\"$key\""))
            }
        }
    }

    @Test
    fun `the rest of the setup is what the head unit sent before`() {
        val s = setup()
        val gen = s.getJSONObject("generationConfig")
        assertEquals("AUDIO", gen.getJSONArray("responseModalities").getString(0))
        assertEquals(
            "Sulafat",
            gen.getJSONObject("speechConfig").getJSONObject("voiceConfig")
                .getJSONObject("prebuiltVoiceConfig").getString("voiceName"),
        )
        assertTrue(s.has("inputAudioTranscription"))
        assertFalse(s.has("outputAudioTranscription"))
        val vad = s.getJSONObject("realtimeInputConfig").getJSONObject("automaticActivityDetection")
        assertFalse(vad.getBoolean("disabled"))
        assertEquals(900, vad.getInt("silenceDurationMs"))
        assertEquals(300, vad.getInt("prefixPaddingMs"))

        val speakOnly = setup(vadMode = false, subtitles = true)
        assertTrue(speakOnly.has("outputAudioTranscription"))
        val off = speakOnly.getJSONObject("realtimeInputConfig").getJSONObject("automaticActivityDetection")
        assertTrue(off.getBoolean("disabled"))
        assertFalse(off.has("silenceDurationMs"))
    }

    @Test
    fun `voiceActivity ACTIVITY_START is the onset signal, ACTIVITY_END is not`() {
        fun root(type: String) = JSONObject().put("voiceActivity", JSONObject().put("type", type))
        assertTrue(LiveSetup.isActivityStart(root("ACTIVITY_START")))
        assertFalse(LiveSetup.isActivityStart(root("ACTIVITY_END")))
        assertFalse(LiveSetup.isActivityStart(JSONObject().put("serverContent", JSONObject())))
    }
}
