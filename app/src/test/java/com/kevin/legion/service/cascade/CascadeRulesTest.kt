@file:Suppress("MaxLineLength", "ReturnCount", "EmptyFunctionBlock") // test fixtures: invented JSON and wire frames read best on one line

package com.kevin.legion.service.cascade

import com.kevin.legion.service.turn.AudioRoute
import com.kevin.legion.service.turn.EndOfTurnPolicy
import org.json.JSONObject
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
    fun `the early end follows its switch and needs Smart Turn on the phone`() {
        fun plan(serviceEnds: Boolean, smart: Boolean, early: Boolean) = CascadeRules.turnPlan(
            serviceEndsTurns = serviceEnds, smartSetting = smart, smartTurnReady = true,
            bargeSetting = true, route = AudioRoute.PHONE_SPEAKER, inCall = false, earlySetting = early,
        ).config.earlyEnd
        assertTrue(plan(serviceEnds = false, smart = true, early = true))
        assertFalse(plan(serviceEnds = false, smart = true, early = false))
        assertFalse(plan(serviceEnds = false, smart = false, early = true))
        assertFalse(plan(serviceEnds = true, smart = true, early = true))
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

    @Test
    fun `the agenda rides after the preamble, for his turns, and an empty one adds nothing`() {
        val pre = CascadePrompt.preamble("Today's date is an invented day.", "")
        assertEquals(pre, CascadePrompt.withAgenda(pre, ""))
        assertEquals(pre, CascadePrompt.withAgenda(pre, "  "))
        val withIt = CascadePrompt.withAgenda(pre, "TODAY'S AGENDA, invented.\n- one line")
        assertTrue(withIt.startsWith(pre))
        assertTrue(withIt.endsWith("(TODAY'S AGENDA, invented.\n- one line)\n\n"))
        // Never in the cached half: the split's cacheable part is untouched by it.
        val split = CascadePrompt.split("Rules.\n\nToday's date is an invented day.")
        assertFalse(split.cacheable.contains("AGENDA"))
    }

    // --- his speech while a turn is still being answered (voice quick wins D) --------------------

    @Test
    fun `new speech before any reply is audible merges a no-tool or read-only turn`() {
        val noTool = CascadeRules.newSpeech(true, false, true, toolsCalled = false, writeCalled = false, bargeIn = false)
        assertEquals(CascadeRules.NewSpeech.MERGE, noTool)
        val reads = CascadeRules.newSpeech(true, false, true, toolsCalled = true, writeCalled = false, bargeIn = false)
        assertEquals("a lookup no longer holds his words behind its reply", CascadeRules.NewSpeech.MERGE, reads)
        assertEquals(
            CascadeRules.NewSpeech.MERGE,
            CascadeRules.newSpeech(true, false, true, toolsCalled = true, writeCalled = false, bargeIn = true),
        )
    }

    @Test
    fun `a turn that called a write is interrupted like a barge-in, never merged`() {
        assertEquals(
            CascadeRules.NewSpeech.INTERRUPT,
            CascadeRules.newSpeech(true, false, true, toolsCalled = true, writeCalled = true, bargeIn = true),
        )
        // Half-duplex (Bluetooth, a call): no barge-in, so a write's turn keeps today's rule.
        assertEquals(
            CascadeRules.NewSpeech.NONE,
            CascadeRules.newSpeech(true, false, true, toolsCalled = true, writeCalled = true, bargeIn = false),
        )
    }

    @Test
    fun `nothing happens with no turn running, a reply already audible, or an opener's turn`() {
        for (tools in listOf(false, true)) {
            for (write in listOf(false, true)) {
                assertEquals(
                    CascadeRules.NewSpeech.NONE,
                    CascadeRules.newSpeech(false, false, true, tools, write, bargeIn = true),
                )
                assertEquals(
                    "an audible reply is the barge-in gate's to stop",
                    CascadeRules.NewSpeech.NONE,
                    CascadeRules.newSpeech(true, true, true, tools, write, bargeIn = true),
                )
                assertEquals(
                    CascadeRules.NewSpeech.NONE,
                    CascadeRules.newSpeech(true, false, false, tools, write, bargeIn = true),
                )
            }
        }
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
    fun `the engine defaults to Cloud, and a stored Live reads as Cloud while Live is retired`() {
        assertTrue(VoiceEngineSettings.LIVE_RETIRED)
        assertEquals(VoiceEngine.CLOUD, VoiceEngineSettings.DEFAULT_ENGINE)
        assertEquals(VoiceEngine.CLOUD, VoiceEngineSettings.parseEngine(null))
        assertEquals(VoiceEngine.CLOUD, VoiceEngineSettings.parseEngine("CASCADE"))
        // Every install from before 2026-11-15 stored LIVE: migrated on read.
        assertEquals(VoiceEngine.CLOUD, VoiceEngineSettings.parseEngine("LIVE"))
        assertEquals(VoiceEngine.LAPTOP, VoiceEngineSettings.parseEngine("LAPTOP"))
        assertEquals(VoiceEngine.CLOUD, VoiceEngineSettings.parseEngine("CLOUD"))
        assertEquals("Settings offers Cloud and Laptop only", listOf(VoiceEngine.CLOUD, VoiceEngine.LAPTOP), VoiceEngineSettings.OFFERED)
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

    // --- the cloud fallback (Kevin, 2026-11-15): laptop, then cloud voice, then Live ------------

    @Test
    fun `a laptop fault goes to the cloud voice, a cloud or phone fault past it`() {
        assertEquals(CascadeRules.Next.CLOUD, CascadeRules.nextAfter(SpeechBackend.Kind.LAPTOP, cloudCanStart = true))
        assertEquals(CascadeRules.Next.LIVE, CascadeRules.nextAfter(SpeechBackend.Kind.LAPTOP, cloudCanStart = false))
        assertEquals(CascadeRules.Next.LIVE, CascadeRules.nextAfter(SpeechBackend.Kind.CLOUD, cloudCanStart = true))
        assertEquals(CascadeRules.Next.LIVE, CascadeRules.nextAfter(null, cloudCanStart = true))
    }

    @Test
    fun `settings say which path ran`() {
        assertEquals("laptop busy, used cloud voice", VoiceEngineSettings.statusWords("busy_loading"))
        assertEquals(
            "laptop not reachable (is Tailscale on, and the laptop awake?), used cloud voice",
            VoiceEngineSettings.statusWords("unreachable_timeout"),
        )
        assertEquals("laptop dropped (socket_dropped_1006), switched to cloud voice", VoiceEngineSettings.statusWords("socket_dropped_1006"))
        // Live is retired: nothing says "used Live" any more; the failure is said as what it is.
        assertEquals("laptop busy, voice not started", VoiceEngineSettings.statusWords("busy_loading", usedCloud = false))
        assertEquals("phone turn detection unavailable, voice not started", VoiceEngineSettings.statusWords("turn_models_unavailable"))
        assertEquals("cloud voice failed (tts_http_503), conversation ended", VoiceEngineSettings.cloudFailedWords("tts_http_503"))
        assertEquals(
            "laptop busy; cloud voice unavailable (no Gemini key), voice not started",
            VoiceEngineSettings.cloudFailedWords("no_key", laptopToken = "busy_busy"),
        )
        assertEquals("phone voice failed (mic_read_error_-3), conversation ended", VoiceEngineSettings.phoneFailedWords("mic_read_error_-3"))
        // Settings > Test connection: the reason only, no destination.
        assertEquals("laptop busy", VoiceEngineSettings.laptopWords("busy_busy"))
        assertFalse(VoiceEngineSettings.laptopWords("unreachable_timeout").contains("used"))
    }

    @Test
    fun `the fallback and open lines name both ends`() {
        assertEquals(
            "engine_fallback from=laptop to=cloud stage=socket reason=dropped_1006",
            CascadeLines.fallback("laptop", "cloud", "socket", "dropped_1006"),
        )
        val p = CascadeRules.turnPlan(
            serviceEndsTurns = false, smartSetting = true, smartTurnReady = true,
            bargeSetting = false, route = AudioRoute.PHONE_SPEAKER, inCall = false,
        )
        val line = CascadeLines.open(900, -1, p, ttsStreams = false, backend = "cloud")
        assertTrue(line, line.startsWith("cascade_open_ms=900 probe_ms=-1 backend=cloud eot_mode=phone"))
    }

    @Test
    fun `the hand-over to Live no longer says it came from the laptop`() {
        val o = Handover.opener(Handover.Moment.Idle)!!
        assertFalse(o.contains("laptop"))
        assertTrue(o.contains("different voice engine"))
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

    @Test
    fun `a write finished under a stop is said from its facts, short, never a question`() {
        fun fact(name: String, action: String, result: String, ok: Boolean = true) =
            ScriptedLines.writeFact(name, action, JSONObject(result), ok)
        val milk = fact("manage_item", "add", """{"success":true,"message":"Added. It was a list, not a reminder. Anything else?","item":"milk","list":"Groceries"}""")
        assertEquals("Done: added milk to Groceries.", ScriptedLines.doneLine(listOf(milk)))
        // Two writes, two short clauses; the title comes from a nested result field.
        val tick = fact("manage_item", "tick", """{"success":true,"items":[{"title":"stamps","done":true}]}""")
        assertEquals("Done: added milk to Groceries; ticked off stamps.", ScriptedLines.doneLine(listOf(milk, tick)))
        // A failed or refused write is never said by code (review F4, F9): it changed nothing.
        val failed = fact("complete_task", "", """{"success":false,"message":"Which one?","title":"essay"}""", ok = false)
        assertNull(ScriptedLines.doneLine(listOf(failed)))
        // No title in the result: the phrase alone; never the message, never a "?".
        val bare = fact("complete_task", "", """{"success":true,"message":"Marked. Want another?"}""")
        assertEquals("Done: marked it done.", ScriptedLines.doneLine(listOf(bare)))
        // Long titles and many writes stay within 80 characters.
        val many = (1..6).map { fact("manage_item", "add", """{"item":"invented item number $it with a long name","list":"Groceries"}""") }
        val line = ScriptedLines.doneLine(many)!!
        assertTrue(line, line.length <= ScriptedLines.MAX_DONE_CHARS && !line.contains("?"))
        assertNull(ScriptedLines.doneLine(emptyList()))
    }

    @Test
    fun `every completed write is said from its result, never dropped unsaid, never a false done`() {
        fun fact(name: String, action: String, result: String, ok: Boolean = true) =
            ScriptedLines.writeFact(name, action, JSONObject(result), ok)
        // The car switch names the car the result names (fields the adapter branch adds), or says the generic line.
        val switched = fact("manage_vehicle", "switch", """{"success":true,"changed":true,"vehicle":"Invented Fit","message":"x"}""")
        assertEquals("Done: switched to the Invented Fit.", ScriptedLines.doneLine(listOf(switched)))
        // A result that names nothing still reads as English; an untick says which way it went.
        assertEquals("Done: ticked it off.", ScriptedLines.doneLine(listOf(fact("manage_checklist", "tick", """{"success":true}"""))))
        val undone = fact("complete_task", "", """{"success":true,"done":false,"title":"essay"}""")
        assertEquals("Done: marked essay not done.", ScriptedLines.doneLine(listOf(undone)))
        // The adapter branch's shapes (feat/obd-adapter-per-connection, final field names 2026-11-19).
        val moved = fact("manage_vehicle", "adapter", """{"success":true,"changed":true,"readings_moved":true,"vehicle":"Invented Ranger"}""")
        assertEquals("Done: this drive now goes under the Invented Ranger.", ScriptedLines.doneLine(listOf(moved)))
        val onIt = fact("manage_vehicle", "adapter", """{"success":true,"changed":true,"vehicle":"Invented Ranger"}""")
        assertEquals("Done: the adapter is on the Invented Ranger.", ScriptedLines.doneLine(listOf(onIt)))
        assertNull(ScriptedLines.doneLine(listOf(fact("manage_vehicle", "adapter", """{"success":true,"changed":false,"vehicle":"Invented Ranger"}"""))))
        assertEquals(ScriptedLines.GENERIC_DONE, ScriptedLines.doneLine(listOf(fact("manage_vehicle", "adapter", """{"success":true}"""))))
        val oldShape = fact("manage_vehicle", "switch", """{"success":true,"message":"You are on the Invented Fit now."}""")
        assertEquals(ScriptedLines.GENERIC_DONE, ScriptedLines.doneLine(listOf(oldShape)))
        // Already the selected car / already the goal: nothing changed, nothing is said.
        val same = fact("manage_vehicle", "switch", """{"success":true,"changed":false,"vehicle":"Invented Fit"}""")
        assertNull(ScriptedLines.doneLine(listOf(same)))
        val goal = fact("set_goal", "", """{"success":true,"changed":false,"message":"already"}""")
        assertNull(ScriptedLines.doneLine(listOf(goal)))
        // A proposal is not a change he would call done.
        assertNull(ScriptedLines.doneLine(listOf(fact("generate_goal_plan", "", """{"success":true}"""))))
        // A write with no phrase (the engine bridge: success and a message only) gets the generic line.
        val bridged = fact("ask_engine", "", """{"success":true,"changed":true,"message":"Logged it on the engine."}""")
        assertEquals(ScriptedLines.GENERIC_DONE, ScriptedLines.doneLine(listOf(bridged)))
        val milk = fact("manage_item", "add", """{"item":"milk","list":"Groceries"}""")
        assertEquals("Done: added milk to Groceries. That change went through.", ScriptedLines.doneLine(listOf(milk, bridged)))
        // Two identical writes are two facts.
        assertEquals("Done: added milk to Groceries; added milk to Groceries.", ScriptedLines.doneLine(listOf(milk, fact("manage_item", "add", """{"item":"milk","list":"Groceries"}"""))))
        // Past 80 characters the rest is counted, not dropped.
        val many = (1..6).map { fact("manage_item", "add", """{"item":"thing $it"}""") }
        val line = ScriptedLines.doneLine(many)!!
        assertTrue(line, line.length <= ScriptedLines.MAX_DONE_CHARS)
        val clauses = line.removePrefix("Done: ").removeSuffix(".").split("; ")
        val counted = clauses.last().let { Regex("and ([0-9]+) more").find(it)?.groupValues?.get(1)?.toInt() } ?: 0
        assertEquals(6, clauses.count { it.startsWith("added") } + counted)
        // Long titles with several writes still fit.
        val long = (1..3).map { fact("manage_item", "add", """{"item":"invented item number $it with a very long name indeed","list":"Groceries"}""") }
        assertTrue(ScriptedLines.doneLine(long)!!.length <= ScriptedLines.MAX_DONE_CHARS)
    }

    @Test
    fun `a long item is said by its whole name, and a title too long for the line ends on a word`() {
        fun add(item: String) =
            ScriptedLines.writeFact("manage_item", "add", JSONObject().put("success", true).put("item", item), true)
        // Over 40 characters, and the whole clause still fits the spoken line, so nothing may be
        // cut here. A title cap
        // used to fire inside a sentence that had room for all of it, and the item was spoken
        // mid-word (2026-11-23 voice log).
        val whole = "Order a new chain for the invented bicycle"
        assertEquals("Done: added $whole.", ScriptedLines.doneLine(listOf(add(whole))))
        // A title no line could carry is still cut, but never mid-word.
        val words = (1..40).map { "word$it" }
        val line = ScriptedLines.doneLine(listOf(add(words.joinToString(" "))))!!
        assertTrue(line, line.length <= ScriptedLines.MAX_DONE_CHARS)
        val said = line.removePrefix("Done: added ").removeSuffix(".").split(" ")
        assertTrue(line, said.isNotEmpty() && said.all { it in words })
    }

    @Test
    fun `a cut done line never ends on a dangling to`() {
        fun add(item: String, list: String) = ScriptedLines.writeFact(
            "manage_item", "add",
            JSONObject().put("success", true).put("item", item).put("list", list), true,
        )
        // An item just long enough that the line's own cut lands inside the list's name: the
        // clause has to lose the whole "to <list>" tail, not stop on the preposition.
        for (length in 55..70) {
            val item = "ab".repeat(length / 2).chunked(4).joinToString(" ").take(length).trim()
            val line = ScriptedLines.doneLine(listOf(add(item, "Todo")))!!
            assertTrue(line, line.length <= ScriptedLines.MAX_DONE_CHARS)
            assertFalse(line, line.endsWith(" to."))
        }
    }

    @Test
    fun `a hand-over says an owed write only while the voice works, never on a crash`() {
        assertTrue(CascadeRules.handOverSaysOwed("prompt", phoneFault = false))
        assertTrue(CascadeRules.handOverSaysOwed("stt", phoneFault = false))
        assertFalse(CascadeRules.handOverSaysOwed("tts", phoneFault = false))
        assertFalse(CascadeRules.handOverSaysOwed("open", phoneFault = false))
        assertFalse("a crash goes straight to the record and the tone", CascadeRules.handOverSaysOwed("crash", phoneFault = true))
    }

    @Test
    fun `the 500 ms finished end is on only where Smart Turn runs on the phone`() {
        fun plan(serviceEnds: Boolean, smart: Boolean) = CascadeRules.turnPlan(
            serviceEndsTurns = serviceEnds, smartSetting = smart, smartTurnReady = true,
            bargeSetting = false, route = AudioRoute.PHONE_SPEAKER, inCall = false,
        )
        assertEquals(EndOfTurnPolicy.FINISHED_MS, plan(serviceEnds = false, smart = true).endFinishedMs)
        assertEquals(EndOfTurnPolicy.BASE_MS, plan(serviceEnds = false, smart = false).endFinishedMs)
        assertEquals(EndOfTurnPolicy.CAP_MS, plan(serviceEnds = true, smart = true).endFinishedMs)
    }

    @Test
    fun `code's write lines with no title are kept like the fillers`() {
        val fixed = ScriptedLines.FIXED_DONE_LINES
        assertTrue(ScriptedLines.GENERIC_DONE in fixed)
        assertTrue(ScriptedLines.UNCONFIRMED_ONE in fixed)
        assertTrue(fixed.size > 2)
        for (line in fixed) {
            assertFalse(line, line.contains("%s"))
            assertTrue(line, line in ScriptedLines.KEEP)
        }
        assertTrue(fixed.filter { it.startsWith("Done: ") }.all { it.endsWith(".") })
    }
}
