package com.kevin.legion.service.cascade

import com.kevin.legion.service.turn.AudioRoute
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A tap opens the mic at once with a chime and no spoken greeting; the wake word and a proactive line
 * keep theirs (Kevin, 2026-11-19, speed plan item 1). The rules are pure ([OpeningRule], [MicBacklog]);
 * the session's wiring is pinned in [VoiceSpeedWiringTest].
 */
class TapOpeningTest {

    @Test
    fun `a tap opens on the chime, the wake word on the greeting, a proactive line on itself`() {
        assertEquals(OpeningRule.Opening.CHIME, OpeningRule.of(fromWakeWord = false, hasOpener = false))
        assertEquals(OpeningRule.Opening.GREETING, OpeningRule.of(fromWakeWord = true, hasOpener = false))
        assertEquals(OpeningRule.Opening.OPENER, OpeningRule.of(fromWakeWord = false, hasOpener = true))
        assertEquals(OpeningRule.Opening.OPENER, OpeningRule.of(fromWakeWord = true, hasOpener = true))
    }

    @Test
    fun `the early barge-in decision is the turn plan's, for every route`() {
        for (route in AudioRoute.entries) for (setting in listOf(true, false)) for (inCall in listOf(true, false)) {
            val plan = CascadeRules.turnPlan(
                serviceEndsTurns = false, smartSetting = true, smartTurnReady = true,
                bargeSetting = setting, route = route, inCall = inCall,
            )
            assertEquals("$route $setting $inCall", plan.config.bargeIn, OpeningRule.bargeIn(setting, route, inCall))
        }
    }

    @Test
    fun `the backlog hands on every buffer in order with its playing mark, copied`() {
        val b = MicBacklog()
        val buf = ByteArray(4) { 1 }
        b.add(buf, 4, playing = true)
        buf.fill(2) // the mic reuses its buffer
        b.add(buf, 2, playing = false)
        assertEquals(6, b.bytes)
        val got = mutableListOf<Triple<List<Byte>, Int, Boolean>>()
        b.drainTo { pcm, n, playing -> got += Triple(pcm.take(n), n, playing) }
        assertEquals(2, got.size)
        assertEquals(Triple(List<Byte>(4) { 1 }, 4, true), got[0])
        assertEquals(Triple(List<Byte>(2) { 2 }, 2, false), got[1])
        assertEquals(0, b.bytes)
        // Drained: nothing twice.
        b.drainTo { _, _, _ -> fail("drained twice") }
    }

    @Test
    fun `the backlog keeps the newest audio past its cap and says how long it was`() {
        val b = MicBacklog(capBytes = 6)
        b.add(byteArrayOf(1, 1, 1, 1), 4, false)
        b.add(byteArrayOf(2, 2, 2, 2), 4, false)
        assertEquals(4, b.bytes)
        assertEquals(4, b.droppedBytes)
        var first: ByteArray? = null
        b.drainTo { pcm, _, _ -> if (first == null) first = pcm }
        assertArrayEquals(byteArrayOf(2, 2, 2, 2), first)
        val ms = MicBacklog().apply { add(ByteArray(3_200), 3_200, false) }.drainTo { _, _, _ -> }
        assertEquals(100L, ms)
        b.add(ByteArray(0), 0, false)
        assertEquals(0, b.bytes)
    }
}
