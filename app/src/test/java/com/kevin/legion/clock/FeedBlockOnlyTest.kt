package com.kevin.legion.clock

import com.kevin.legion.service.ClockToolbox
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The feed block alone, by voice (phone audit 2026-11-19: the only voice door was bedtime_on,
 * which turned Quiet on too). Over fakes; no Android.
 */
class FeedBlockOnlyTest {

    private fun run(rig: ClockRig, action: String) =
        ClockToolbox.run(rig.clock, JSONObject().put("action", action))

    @Test
    fun `feed_block_on turns the feed block on and leaves Quiet and bedtime alone`() {
        val rig = ClockRig()
        val r = run(rig, "feed_block_on")
        assertTrue(r.message, r.ok)
        assertTrue(rig.effects.feed)
        assertFalse(rig.effects.quiet)
        assertFalse(rig.store.load().bedtime.active)
        assertTrue(r.message, r.message.contains("Quiet was not changed"))
    }

    @Test
    fun `feed_block_on when it is already on says so`() {
        val rig = ClockRig(effects = FakeEffects(feed = true))
        val r = run(rig, "feed_block_on")
        assertTrue(r.ok)
        assertTrue(r.message, r.message.contains("already on"))
    }

    @Test
    fun `feed_block_on before the first unlock changes nothing and says why`() {
        val rig = ClockRig(effects = FakeEffects(isUnlocked = false))
        val r = run(rig, "feed_block_on")
        assertFalse(r.ok)
        assertFalse(rig.effects.feed)
        assertTrue(r.message, r.message.contains("Nothing was changed"))
    }

    @Test
    fun `the clock declaration offers feed_block_on`() {
        val decl = ClockToolbox.declarations().getJSONObject(0)
        val enum = decl.getJSONObject("parameters").getJSONObject("properties").getJSONObject("action")
            .getJSONArray("enum")
        assertEquals(1, (0 until enum.length()).count { enum.getString(it) == "feed_block_on" })
    }
}
