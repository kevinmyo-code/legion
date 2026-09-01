package com.kevin.legion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies [SubAgent.userParts]'s `fileData` part - the audio equivalent of
 * [SubAgentPartsTest]'s `inlineData` coverage, added for voice-notes ticket 03. Robolectric only
 * because [SubAgentPartsTest] already needs it for `android.util.Base64`; this class's own
 * assertions never touch that API, but sharing the runner keeps this file consistent with its
 * sibling rather than mixing runners in the same package for no reason.
 */
@RunWith(RobolectricTestRunner::class)
class SubAgentFileDataPartTest {

    private val agent = SubAgent()

    @Test
    fun `adds a fileData part, not inlineData, when a fileUri is supplied`() {
        val parts = agent.userParts(
            "transcribe this", imageBytes = null, imageMimeType = "image/jpeg",
            fileUri = "https://generativelanguage.googleapis.com/v1beta/files/abc123",
            fileMimeType = "audio/m4a",
        )

        assertEquals(2, parts.length())
        assertFalse(
            "audio must never ride as inlineData - the research file found the 20MB request cap " +
                "a modest recording already clears",
            parts.getJSONObject(1).has("inlineData"),
        )
        val fileData = parts.getJSONObject(1).getJSONObject("fileData")
        assertEquals("audio/m4a", fileData.getString("mimeType"))
        assertEquals("https://generativelanguage.googleapis.com/v1beta/files/abc123", fileData.getString("fileUri"))
    }

    @Test
    fun `mime type is audio-m4a, never audio-mp4`() {
        // Pins the research file's own flagged mistake: "audio/mp4 is NOT on Google's accepted
        // list" - a caller passing the wrong constant would otherwise fail only at the real API,
        // far from this file.
        val parts = agent.userParts(
            "x", imageBytes = null, imageMimeType = "image/jpeg",
            fileUri = "https://example.com/files/x", fileMimeType = "audio/m4a",
        )
        assertEquals("audio/m4a", parts.getJSONObject(1).getJSONObject("fileData").getString("mimeType"))
    }

    @Test
    fun `no fileData key present without a fileUri`() {
        val parts = agent.userParts("just text", imageBytes = null, imageMimeType = "image/jpeg")
        assertEquals(1, parts.length())
        assertFalse(parts.getJSONObject(0).has("fileData"))
    }
}
