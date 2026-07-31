package com.kevin.legion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies [SubAgent.userParts]' JSON shape: text-only when no image is
 * supplied, text + inlineData when one is. No network involved - this is the
 * pure part of the vision extension pantry's [com.kevin.legion.pantry.PantryReceiptAgent]
 * depends on. Runs under Robolectric because `android.util.Base64` is a stub
 * that throws on a plain JVM unit test runner (no `returnDefaultValues`
 * configured); Robolectric shadows it with a real implementation instead.
 */
@RunWith(RobolectricTestRunner::class)
class SubAgentPartsTest {

    private val agent = SubAgent()

    @Test
    fun `text only when no image`() {
        val parts = agent.userParts("hello", null, "image/jpeg")
        assertEquals(1, parts.length())
        assertEquals("hello", parts.getJSONObject(0).getString("text"))
    }

    @Test
    fun `adds inlineData part when image supplied`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val parts = agent.userParts("describe this", bytes, "image/png")

        assertEquals(2, parts.length())
        assertEquals("describe this", parts.getJSONObject(0).getString("text"))

        val inline = parts.getJSONObject(1).getJSONObject("inlineData")
        assertEquals("image/png", inline.getString("mimeType"))
        val decoded = android.util.Base64.decode(inline.getString("data"), android.util.Base64.NO_WRAP)
        assertTrue(decoded.contentEquals(bytes))
    }

    @Test
    fun `no inlineData key present without an image`() {
        val parts = agent.userParts("just text", null, "image/jpeg")
        assertFalse(parts.getJSONObject(0).has("inlineData"))
    }
}
