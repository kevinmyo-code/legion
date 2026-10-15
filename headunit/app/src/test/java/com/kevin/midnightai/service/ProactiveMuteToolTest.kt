package com.kevin.midnightai.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveMuteToolTest {

    private fun decl(name: String) =
        LiveToolbox.declarations().let { all ->
            (0 until all.length()).map { all.getJSONObject(it) }.firstOrNull { it.getString("name") == name }
        }

    @Test fun `the mute tool is declared with a required boolean`() {
        val d = decl("set_proactive_mute")!!
        val params = d.getJSONObject("parameters")
        assertEquals("boolean", params.getJSONObject("properties").getJSONObject("muted").getString("type"))
        assertEquals("muted", params.getJSONArray("required").getString(0))
    }

    @Test fun `its description separates it from speaker mute`() {
        val text = decl("set_proactive_mute")!!.getString("description")
        assertTrue(text.contains("control_volume"))
        assertTrue(text.contains("UNPROMPTED"))
    }

    @Test fun `muting reports the observed state`() {
        val (ok, msg) = ProactivePreferences.voiceResult(requested = true, actual = true)
        assertTrue(ok)
        assertTrue(msg.contains("no more unprompted remarks"))
    }

    @Test fun `unmuting reports the observed state`() {
        val (ok, msg) = ProactivePreferences.voiceResult(requested = false, actual = false)
        assertTrue(ok)
        assertTrue(msg.contains("back on"))
    }

    @Test fun `a write that did not stick is a failure and says what is still true`() {
        val (ok, msg) = ProactivePreferences.voiceResult(requested = true, actual = false)
        assertFalse(ok)
        assertTrue(msg.contains("couldn't change"))
        assertTrue(msg.contains("still on"))
    }
}
