package com.kevin.legion.service.cascade

import com.kevin.legion.service.turn.AudioRoute
import com.kevin.legion.service.turn.EndOfTurnPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cascade's pure decisions: open, turn plan, prompt split, hand-over wording, settings words. */
class CascadeRulesTest {

    // --- open ---------------------------------------------------------------------------------

    @Test
    fun `a conversation opens on the laptop only when everything is there`() {
        val ready = LaptopProbe.Result.Ready(40)
        assertNull(CascadeRules.openRefusal(configComplete = true, turnModelsReady = true, probe = ready))
        assertEquals("not_configured", CascadeRules.openRefusal(false, true, ready))
        assertEquals("turn_models_unavailable", CascadeRules.openRefusal(true, false, ready))
        assertEquals("busy_busy", CascadeRules.openRefusal(true, true, LaptopProbe.Result.Busy("busy", 30)))
        assertEquals("busy_loading", CascadeRules.openRefusal(true, true, LaptopProbe.Result.Busy("loading", 30)))
        assertEquals("unauthorized", CascadeRules.openRefusal(true, true, LaptopProbe.Result.Unauthorized))
        assertEquals(
            "unreachable_timeout",
            CascadeRules.openRefusal(true, true, LaptopProbe.Result.Unreachable("timeout", 1200)),
        )
    }

    // --- turn plan (Kevin: the service's end of turn first, Smart Turn ditched for it) ----------

    @Test
    fun `when the service ends turns the phone runs no Smart Turn and only a silence cap`() {
        val p = CascadeRules.turnPlan(
            serviceEndsTurns = true, smartSetting = true, smartTurnReady = true,
            bargeSetting = true, route = AudioRoute.PHONE_SPEAKER, inCall = false,
        )
        assertTrue(p.config.manualActivity)
        assertFalse("Smart Turn must not run when the service ends turns", p.config.smartTurn)
        assertEquals(EndOfTurnPolicy.CAP_MS, p.endBaseMs)
        assertEquals(CascadeRules.EotSource.PHONE_CAP, p.phoneEnd)
    }

    @Test
    fun `when the service has no end of turn the phone detector runs unchanged`() {
        val p = CascadeRules.turnPlan(
            serviceEndsTurns = false, smartSetting = true, smartTurnReady = true,
            bargeSetting = true, route = AudioRoute.PHONE_SPEAKER, inCall = false,
        )
        assertTrue(p.config.smartTurn)
        assertEquals(EndOfTurnPolicy.BASE_MS, p.endBaseMs)
        assertEquals(CascadeRules.EotSource.PHONE_DETECTOR, p.phoneEnd)
    }

    @Test
    fun `Bluetooth and a phone call keep the half-duplex rule, and local turn detection still runs`() {
        for ((route, inCall) in listOf(AudioRoute.BLUETOOTH to false, AudioRoute.PHONE_SPEAKER to true)) {
            val p = CascadeRules.turnPlan(false, true, true, bargeSetting = true, route = route, inCall = inCall)
            assertFalse("$route inCall=$inCall must be half-duplex", p.config.bargeIn)
            assertTrue("no server VAD in the cascade: the phone always decides", p.config.manualActivity)
        }
        val wired = CascadeRules.turnPlan(false, true, true, bargeSetting = true, route = AudioRoute.WIRED_HEADSET, inCall = false)
        assertTrue(wired.config.bargeIn)
        val off = CascadeRules.turnPlan(false, true, true, bargeSetting = false, route = AudioRoute.PHONE_SPEAKER, inCall = false)
        assertFalse(off.config.bargeIn)
    }

    @Test
    fun `the end-of-turn source tokens are the ones the log reader expects`() {
        assertEquals(
            listOf("service", "phone_cap", "phone_detector"),
            CascadeRules.EotSource.entries.map { it.wire },
        )
        assertEquals(
            "eot_source=service eot_silence_ms=420 eot_prob=0.91",
            CascadeLines.eot(CascadeRules.EotSource.SERVICE, 420, 0.91f),
        )
        assertEquals("eot_source=phone_cap eot_silence_ms=-1", CascadeLines.eot(CascadeRules.EotSource.PHONE_CAP, null))
    }

    // --- prompt -------------------------------------------------------------------------------

    @Test
    fun `the date paragraph leaves the cached instruction and rides in the conversation`() {
        val base = "You are an invented assistant.\n\nToday's date is Tuesday, March 3, 2099. It is evening " +
            "in Testville. You do NOT have a clock.\n\nThe user likes short answers."
        val split = CascadePrompt.split(base)
        assertFalse(split.cacheable.contains("2099"))
        assertFalse(split.cacheable.contains("Testville"))
        assertTrue(split.cacheable.startsWith("You are an invented assistant."))
        assertTrue(split.cacheable.contains("The user likes short answers."))
        assertTrue(split.cacheable.endsWith(CascadePrompt.VOICE_CLAUSE))
        assertTrue(split.volatile.startsWith("Today's date is Tuesday, March 3, 2099."))
        // Two days, one cache: the cached half is identical.
        val tomorrow = CascadePrompt.split(base.replace("Tuesday, March 3", "Wednesday, March 4"))
        assertEquals(split.cacheable, tomorrow.cacheable)
        assertNotEquals(split.volatile, tomorrow.volatile)
        val pre = CascadePrompt.preamble(split.volatile, "Clock: 21:00 (invented).")
        assertTrue(pre.contains("2099") && pre.contains("21:00"))
    }

    @Test
    fun `an instruction with no date paragraph is used whole`() {
        val split = CascadePrompt.split("Only rules.")
        assertEquals("Only rules.\n\n" + CascadePrompt.VOICE_CLAUSE, split.cacheable)
        assertEquals("", split.volatile)
        assertEquals("", CascadePrompt.preamble("", " "))
    }

    // --- hand-over to Live: never lose his words, never run a tool twice ------------------------

    @Test
    fun `at open Live starts as it always does`() {
        assertNull(Handover.opener(Handover.Moment.AtOpen))
    }

    @Test
    fun `lost words are said out loud and asked for again`() {
        val o = Handover.opener(Handover.Moment.LostWords)!!
        assertTrue(o.contains("say it again"))
    }

    @Test
    fun `heard words with no tool are answered by Live`() {
        val o = Handover.opener(Handover.Moment.Heard("what is the capital of Testland", "", emptyList(), emptyList(), false))!!
        assertTrue(o.contains("what is the capital of Testland"))
        assertTrue(o.contains("Answer that now"))
    }

    @Test
    fun `after a tool ran Live relays the answer and never calls it again`() {
        val o = Handover.opener(
            Handover.Moment.Heard("add oat milk", "Added oat milk.", listOf("manage_checklist"), listOf("manage_checklist"), false),
        )!!
        assertTrue(o.contains("Do NOT call them again"))
        assertTrue(o.contains("Completed and confirmed: manage_checklist"))
        assertTrue(o.contains("Added oat milk."))
        assertFalse("must not ask Live to answer the request afresh", o.contains("Answer that now"))
    }

    @Test
    fun `a read-through turn carries no third-party text`() {
        val o = Handover.opener(
            Handover.Moment.Heard("read my latest mail", "The invented sender says hello", listOf("ask_mail"), emptyList(), true),
        )!!
        assertFalse(o.contains("invented sender"))
        assertFalse(o.contains("read my latest mail"))
        assertTrue(o.contains("Do not repeat any action"))
    }

    // --- ack lines, settings ------------------------------------------------------------------

    @Test
    fun `the ack line never repeats twice in a row`() {
        var i = 0
        val picks = listOf(0, 0, 0, 1, 1, 2)
        val a = AckLines(listOf("A.", "B.", "C."), pick = { picks[i++ % picks.size] })
        val seen = List(4) { a.next() }
        for (k in 1 until seen.size) assertNotEquals(seen[k - 1], seen[k])
    }

    @Test
    fun `the engine defaults to Live and unknown values read as Live`() {
        assertEquals(VoiceEngine.LIVE, VoiceEngineSettings.DEFAULT_ENGINE)
        assertEquals(VoiceEngine.LIVE, VoiceEngineSettings.parseEngine(null))
        assertEquals(VoiceEngine.LIVE, VoiceEngineSettings.parseEngine("CASCADE"))
        assertEquals(VoiceEngine.LAPTOP, VoiceEngineSettings.parseEngine("LAPTOP"))
    }

    @Test
    fun `settings say why the laptop was not used`() {
        assertTrue(VoiceEngineSettings.statusWords("unreachable_timeout").contains("not reachable"))
        assertTrue(VoiceEngineSettings.statusWords("unreachable_recent").contains("Tailscale"))
        assertTrue(VoiceEngineSettings.statusWords("connect_ready_timeout").contains("not reachable"))
        assertTrue(VoiceEngineSettings.statusWords("busy_loading").contains("busy"))
        assertTrue(VoiceEngineSettings.statusWords("unauthorized").contains("token"))
        assertEquals("connected", VoiceEngineSettings.statusWords("ok"))
    }

    @Test
    fun `the laptop config builds its urls and never prints its token`() {
        val c = LaptopVoiceConfig("http://laptop.example.ts.net:8765/", "s3cret-invented", listOf("X-Extra" to "v"))
        assertTrue(c.isComplete)
        assertEquals("http://laptop.example.ts.net:8765/health", c.healthUrl)
        assertEquals("ws://laptop.example.ts.net:8765/v1/conversation", c.conversationUrl)
        assertEquals("wss://h.example/v1/conversation", LaptopVoiceConfig("https://h.example", "t").conversationUrl)
        assertEquals("Authorization" to "Bearer s3cret-invented", c.requestHeaders().first())
        assertEquals(2, c.requestHeaders().size)
        assertFalse(c.toString().contains("s3cret"))
        assertFalse(LaptopVoiceConfig("laptop:8765", "t").isComplete)
        assertFalse(LaptopVoiceConfig("http://laptop:8765", "").isComplete)
    }
}
