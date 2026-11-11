package com.kevin.legion.service

import androidx.test.core.app.ApplicationProvider
import com.kevin.legion.ai.ALFRED
import com.kevin.legion.ai.SHARED_INSTRUCTIONS
import com.kevin.legion.service.tools.LiveSurface
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Writes the Live `setup` message the phone sends, built by the app's own
 * [GeminiLiveSession.buildSetupJson], to `build/live-setup/live_setup_message.json`, for
 * `tools/live_setup_probe.py` (live cost diet, 2026-11-10): the probe opens a real Live session
 * with exactly this message, so the new tool list is proven on the real service before it ships.
 *
 * Phone defaults: voice Puck, conversation mode, manual activity (speaker route), "Answer lookups
 * while talking" on. The system instruction is the static half (persona plus shared rules, as
 * `LiveSetupPayloadSizeTest` measures); the phone adds the safety block, the date and the live
 * context, which need a device. Build output only, never committed; it holds no key and no
 * personal data.
 */
@RunWith(RobolectricTestRunner::class)
class LiveSetupMessageExportTest {

    @Test
    fun `the setup message the phone sends is exported for the live probe`() {
        val session = GeminiLiveSession(ApplicationProvider.getApplicationContext()) {}
        val message = session.buildSetupJson(
            systemInstruction = ALFRED.clause.trimIndent() + " " + ALFRED.delivery + " " + SHARED_INSTRUCTIONS,
            functionDeclarations = LiveToolbox.declarations(),
            voiceName = "Puck",
            vadMode = true,
            subtitles = false,
            resumeHandle = null,
            manualActivity = true,
            nonBlockingTools = LiveSurface.nonBlockingWireNames(LiveToolbox.NON_BLOCKING_READ_TOOLS),
        )
        val setup = message.getJSONObject("setup")
        assertEquals("models/gemini-3.8-live", setup.getString("model"))
        val fns = setup.getJSONArray("tools").getJSONObject(1).getJSONArray("functionDeclarations")
        assertEquals(LiveToolbox.declarations().length(), fns.length())
        val ask = (0 until fns.length()).map { fns.getJSONObject(it) }.first { it.getString("name") == "ask" }
        assertEquals("NON_BLOCKING", ask.getString("behavior"))
        File("build/live-setup").mkdirs()
        File("build/live-setup/live_setup_message.json").writeText(message.toString(1))
        assertTrue(File("build/live-setup/live_setup_message.json").length() > 0)
    }

    @Test
    fun `the exported message carries no key`() {
        val f = File("build/live-setup/live_setup_message.json")
        if (!f.exists()) return
        assertTrue("a key must never reach the export", !f.readText().contains("AIza"))
        JSONObject(f.readText())
    }
}
