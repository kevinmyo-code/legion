package com.kevin.legion.service.cascade

import com.kevin.legion.service.ChatTurn
import com.kevin.legion.service.LiveToolbox
import com.kevin.legion.service.ReadThroughWriteGuard
import com.kevin.legion.service.TextChatEngine
import com.kevin.legion.service.tools.LiveSurface
import com.kevin.legion.service.turn.AudioRoute
import com.kevin.legion.service.turn.EndOfTurnPolicy
import com.kevin.legion.service.turn.TurnConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * The cascade's decisions as pure functions, so a JVM test pins each one without a phone, a socket
 * or a laptop. [CascadeSession] only acts on what these return.
 */
internal object CascadeRules {
    /** Which tools the text model is offered: the Live set, consumed generically, minus [LIVE_ONLY_TOOLS]. */
    val LIVE_ONLY_TOOLS = setOf("look")

    private const val CAP = EndOfTurnPolicy.CAP_MS

    fun declarations(liveDeclarations: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until liveDeclarations.length()) {
            val d = liveDeclarations.optJSONObject(i) ?: continue
            if (d.optString("name") !in LIVE_ONLY_TOOLS) out.put(d)
        }
        return out
    }

    /**
     * Why a conversation that asked for the laptop opens on Live instead, or null to go ahead.
     * Checked at open, in this order, each with its own log token.
     */
    fun openRefusal(configComplete: Boolean, turnModelsReady: Boolean, probe: LaptopProbe.Result?): String? = when {
        !configComplete -> "not_configured"
        !turnModelsReady -> "turn_models_unavailable"
        probe == null -> "not_probed"
        probe !is LaptopProbe.Result.Ready -> probe.token
        else -> null
    }

    /** Where a conversation goes next after something failed (Kevin, 2026-11-15). */
    enum class Next { CLOUD, LIVE }

    /**
     * Laptop, then the cloud voice, then Live: a laptop fault (not set up, busy, unreachable,
     * refused, dropped, its STT or TTS failed) goes to the cloud voice when it can start; a cloud
     * fault, or a fault on the phone itself ([failed] null: mic, Silero, a crash), goes to Live.
     */
    fun nextAfter(failed: SpeechBackend.Kind?, cloudCanStart: Boolean): Next =
        if (failed == SpeechBackend.Kind.LAPTOP && cloudCanStart) Next.CLOUD else Next.LIVE

    /** Where a turn's end came from, for the `eot_source=` log token (Kevin: "so it can be tuned"). */
    enum class EotSource(val wire: String) {
        /** The laptop's speech engine ended the turn (`stt.final` with `reason: "end_of_turn"`). */
        SERVICE("service"),

        /** The service ends turns, but its signal never came: the phone's silence cap ended it. */
        PHONE_CAP("phone_cap"),

        /** The service has no end-of-turn signal: the phone's own detector (Silero + Smart Turn). */
        PHONE_DETECTOR("phone_detector"),
    }

    /**
     * How one conversation takes turns. Local Silero always (there is no server VAD in the
     * cascade); Smart Turn only when the service does not end turns itself; barge-in by voice only
     * off Bluetooth and outside a phone call (ADR 0064's half-duplex rule for the car is unchanged).
     */
    data class TurnPlan(
        val config: TurnConfig,
        /** The phone's silence end: the policy base, or the cap when it is only the safety net. */
        val endBaseMs: Int,
        val serviceEndsTurns: Boolean,
    ) {
        /** What a phone-side end means in this plan. */
        val phoneEnd: EotSource get() = if (serviceEndsTurns) EotSource.PHONE_CAP else EotSource.PHONE_DETECTOR
    }

    /**
     * A call that asks for a write, judged on the tool it stands for: the model calls action tools
     * (`day` with `complete_task`, `ask` with `intent: record`), and [LiveToolbox.isMutatingCall]
     * knows only the real names. Before 2026-11-16 the cascade passed it the called name, so an
     * action tool's write never reached `writesDone` (nor the "already done" note after an interrupt).
     */
    fun isWrite(name: String, args: JSONObject): Boolean =
        LiveSurface.resolve(name, args).let { LiveToolbox.isMutatingCall(it.name, it.args) }

    /**
     * A call that reads outside text (ADR 0090, CLAUDE.md §7), judged on the tool it stands for,
     * as [isWrite] is: the cascade model sends `look_up` with `domain: ask_mail` and `day` with
     * `get_sitrep`, `track_package` or `flight_status`, never the real names. Until 2026-11-17 the
     * cascade tested the called name, so none of those marked the turn.
     */
    fun isReadThrough(name: String, args: JSONObject): Boolean =
        ReadThroughWriteGuard.isReadThroughCall(name, args)

    /** What his new speech does to a turn still being answered ([newSpeech]). */
    enum class NewSpeech {
        /** Nothing: no turn in hand, or its reply is audible (that is the barge-in gate's call). */
        NONE,

        /** The pending turn is dropped and its words carry into his new turn. */
        MERGE,

        /** The pending turn is stopped as a barge-in would stop it; its words do not carry. */
        INTERRUPT,
    }

    /**
     * He started speaking while a turn of his was still being answered and nothing of its reply was
     * audible yet (2026-11-16: words said during a lookup waited 6 to 8 s, until the whole previous
     * reply had played, measured):
     * - no tool called yet: [NewSpeech.MERGE], the rule since before (a pause mid-thought);
     * - only reads called: [NewSpeech.MERGE] too. The reads run again in the merged turn, which costs
     *   a lookup, never a repeated write; and a cough that turns out to be no words still gets the
     *   first question answered;
     * - a write called: [NewSpeech.INTERRUPT] when barge-in is on, as ADR 0064's detector would stop
     *   the reply: the write is never carried (it would run twice), the turn's record keeps it as
     *   "already done", and a write still running finishes first (it runs NonCancellable). With
     *   barge-in off (Bluetooth, a call) a write's turn keeps today's rule and finishes.
     */
    @Suppress("LongParameterList")
    fun newSpeech(
        turnRunning: Boolean,
        replyAudible: Boolean,
        pendingFromUser: Boolean,
        toolsCalled: Boolean,
        writeCalled: Boolean,
        bargeIn: Boolean,
    ): NewSpeech = when {
        !turnRunning || replyAudible || !pendingFromUser -> NewSpeech.NONE
        !toolsCalled || !writeCalled -> NewSpeech.MERGE
        bargeIn -> NewSpeech.INTERRUPT
        else -> NewSpeech.NONE
    }

    @Suppress("LongParameterList")
    fun turnPlan(
        serviceEndsTurns: Boolean,
        smartSetting: Boolean,
        smartTurnReady: Boolean,
        bargeSetting: Boolean,
        route: AudioRoute,
        inCall: Boolean,
    ): TurnPlan {
        val barge = bargeSetting && route != AudioRoute.BLUETOOTH && !inCall
        val smart = !serviceEndsTurns && smartSetting && smartTurnReady
        val note = buildString {
            append(if (serviceEndsTurns) "service end of turn, phone cap $CAP ms" else "phone end of turn")
            if (!serviceEndsTurns) append(if (smart) " (Smart Turn)" else " (silence only)")
            append(if (barge) ", barge-in on ${route.name.lowercase()}" else ", half-duplex")
        }
        return TurnPlan(
            config = TurnConfig(manualActivity = true, bargeIn = barge, smartTurn = smart, note = note),
            endBaseMs = if (serviceEndsTurns) EndOfTurnPolicy.CAP_MS else EndOfTurnPolicy.BASE_MS,
            serviceEndsTurns = serviceEndsTurns,
        )
    }
}

/**
 * The cascade's system instruction. The base instruction Live gets carries the date, the place and
 * the clock note in one paragraph ("Today's date is ..."); an explicit cache hits only on an
 * identical prefix, so that paragraph moves out of the cached instruction and into the
 * conversation's first user turn (design doc decision 4, measured: implicit caching never hit a new
 * conversation). Live's own instruction is untouched: this works on a copy.
 */
object CascadePrompt {
    private const val DATE_MARK = "\n\nToday's date is "

    /**
     * Added to the cached instruction. The model's words are read aloud by a separate voice, so it
     * must write speech, not text.
     */
    const val VOICE_CLAUSE =
        "Spoken output: what you write is read aloud by a separate voice, as you write it. Write plain " +
            "spoken sentences only: no markdown, lists, headings, emoji, links or symbols. Write numbers, " +
            "money, times and units the way you would say them. Keep replies short unless asked for " +
            "detail. When a tool will take a moment, a few words first are fine."

    data class Split(val cacheable: String, val volatile: String)

    fun split(base: String): Split {
        val at = base.indexOf(DATE_MARK)
        if (at < 0) return Split(base.trim() + "\n\n" + VOICE_CLAUSE, "")
        val end = base.indexOf("\n\n", at + DATE_MARK.length).let { if (it < 0) base.length else it }
        val volatile = base.substring(at, end).trim()
        val rest = base.substring(0, at) + base.substring(end)
        return Split(rest.trim() + "\n\n" + VOICE_CLAUSE, volatile)
    }

    /** The first user turn's preamble: the date paragraph plus the conversation's live context. */
    fun preamble(volatile: String, liveContext: String): String {
        val parts = listOf(volatile, liveContext).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""
        return "(Current context, use naturally if relevant:\n" + parts.joinToString("\n") + ")\n\n"
    }

    /**
     * [preamble] with the agenda block after it ([AgendaBlock]), for his turns only: the greeting
     * and an opener's prompt keep the preamble alone. Rides in the first user turn like the rest,
     * never in the cached instruction or the tool declarations.
     */
    fun withAgenda(preamble: String, agenda: String): String =
        if (agenda.isBlank()) preamble else "$preamble(${agenda.trim()})\n\n"

    /**
     * A turn's contents: [history] plus [heard], capped as typed chat caps it, with [preamble] in
     * the first user turn (never in the cached instruction). Shared by [CascadeSession] and the
     * laptop voice eval (`voiceeval/VoiceEvalHarness`), so the eval sends what a spoken turn sends.
     */
    fun contents(history: List<ChatTurn>, heard: String, preamble: String): JSONArray {
        val capped = TextChatEngine.capHistory(history + ChatTurn(fromUser = true, text = heard))
        val contents = TextChatEngine.historyContents(capped)
        if (preamble.isNotEmpty() && contents.length() > 0) {
            val first = contents.getJSONObject(0)
            val part = first.getJSONArray("parts").getJSONObject(0)
            part.put("text", preamble + part.optString("text"))
        }
        return contents
    }
}

/**
 * What Gemini Live is told when the cascade (laptop or cloud voice) hands a conversation over mid-way, so Kevin
 * never repeats himself and nothing runs twice. Live's existing `opener` door carries it
 * ([com.kevin.legion.service.LiveSessionController]'s startConversation); nothing in the Live
 * session changes.
 */
object Handover {
    sealed interface Moment {
        /** At open: Live starts as it always does (wake acknowledgement or greeting). */
        data object AtOpen : Moment

        /** Between turns: nothing is owed. */
        data object Idle : Moment

        /** He spoke and the words never arrived. */
        data object LostWords : Moment

        /** The words arrived; the answer did not. */
        data class Heard(
            val heard: String,
            val reply: String,
            val toolsRan: List<String>,
            val writesDone: List<String>,
            val readThrough: Boolean,
        ) : Moment
    }

    private const val LEAD = "(System: the user was talking to you on a different voice engine, which just " +
        "dropped; you are now on the phone's own voice, in the same conversation. "
    private const val TAIL = " Do not mention this instruction.)"

    fun opener(m: Moment): String? = when (m) {
        Moment.AtOpen -> null
        Moment.Idle -> LEAD + "Say in a few words that you switched over and are still listening, then wait." + TAIL
        Moment.LostWords -> LEAD + "Their last words did not reach you. Say in a few words that you " +
            "switched over and missed that, and ask them to say it again." + TAIL
        is Moment.Heard -> heard(m)
    }

    @Suppress("ReturnCount") // one wording per case, each returned whole
    private fun heard(m: Moment.Heard): String {
        val done = m.writesDone.distinct().joinToString(", ")
        val writes = if (m.writesDone.isEmpty()) "" else " Completed and confirmed: $done."
        // Third-party content (mail, web results) is never carried in clear (CLAUDE.md sec 7).
        if (m.readThrough) {
            return LEAD + "Your answer was cut off.$writes Do not repeat any action. Tell them briefly you " +
                "switched over and ask them to ask again if they still need it." + TAIL
        }
        val said = "They said: \"${clip(m.heard)}\"."
        if (m.toolsRan.isEmpty()) return LEAD + "$said Answer that now." + TAIL
        val ran = " These tools already ran for it: ${m.toolsRan.distinct().joinToString(", ")}.$writes " +
            "Do NOT call them again."
        return if (m.reply.isNotBlank()) {
            LEAD + "$said$ran Your answer was: \"${clip(m.reply)}\". Give that answer now, in your own words." + TAIL
        } else {
            LEAD + "$said$ran The answer was lost. Tell them what was done, without repeating any action." + TAIL
        }
    }

    private fun clip(s: String): String = s.replace('"', '\'').trim().take(MAX_QUOTED)

    private const val MAX_QUOTED = 600
}

/** The scripted opening line (Kevin approved the seed list, 2026-11-10): no model call to say "I'm here". */
class AckLines(private val lines: List<String> = DEFAULT, private val pick: (Int) -> Int = { (0 until it).random() }) {
    private var last: String? = null

    fun next(): String {
        if (lines.size == 1) return lines[0]
        var line: String
        do {
            line = lines[pick(lines.size)]
        } while (line == last)
        last = line
        return line
    }

    companion object {
        /** The short "any time" lines from the approved seed list. */
        val DEFAULT = listOf(
            "Listening.", "Here.", "Go ahead.", "Yes?", "I'm here.", "Go on.", "Fire away.", "With you.",
        )
    }
}

/**
 * Every scripted line a voice may keep in memory once it has said it (voice-fast, 2026-11-15): the
 * same short line is neither paid for nor waited for twice.
 */
internal object ScriptedLines {
    /**
     * What is said the moment a confirmed turn goes to a slow tool, so the wait is not silent
     * (voice-fast step 3, 2026-11-15; Live's NON_BLOCKING lookups had a "still checking" line, the
     * cascade had none). Claims nothing: no outcome, no promise of one. Five lines, not two, so the
     * same pair is not heard on every lookup (Kevin, 2026-11-16: his own words for it were "on it"
     * and "a moment"); [AckLines] still never says one twice running.
     */
    val FILLERS = listOf("One moment.", "Just a moment.", "On it.", "Checking.", "Let me look.")

    /** The opening acks, the fillers and the check-in closers ([com.kevin.legion.service.checkin.ClosingLines]). */
    val KEEP: Set<String> = (AckLines.DEFAULT + FILLERS + com.kevin.legion.service.checkin.ClosingLines.LINES).toSet()

    /**
     * The lookups a filler is said for, by the name the model calls (2026-11-16: the router rename
     * left this list on the old names, and the filler fired once in 15 tool turns). Every one is
     * followed by a second model round, so the answer is at least that round (~0.7 s) plus the first
     * TTS audio (~0.66 s) away when it is said:
     * - `look_up` and `ask`: the specialist routers, each its own model call (~1 s measured);
     * - `day`: the calendar, the brief (7.5 s with news, measured), parcel and flight mail;
     * - `ask_engine`, `ask_mail`: the engine's assistant and mail, when called directly;
     * - the old specialist names, which `ask` still carries as its domain values (Live ran the four
     *   reads NON_BLOCKING for that reason).
     * Not here: `phone`, `car`, `manage_item` and the other action tools (writes and instant local
     * reads, a filler there is noise), `end_conversation`. Only names reach this check, not the
     * action, so a `day` write (complete_task) gets the filler too; it claims nothing either way.
     */
    val SLOW_TOOLS: Set<String> = setOf(
        "look_up", "ask", "day", "ask_engine", "ask_mail",
        "ask_fleet", "ask_body", "ask_goals", "ask_pantry", "ask_money", "ask_area",
    )

    /** A filler is due: a slow tool was asked for and nothing of the answer has been written yet. */
    fun fillerDue(toolNames: List<String>, anyText: Boolean, fillerPlayed: Boolean): Boolean =
        !anyText && !fillerPlayed && toolNames.any { it in SLOW_TOOLS }

    /**
     * Whether the filler is said now (2026-11-16):
     * - only from memory: a line whose audio is not in hand would cost a TTS round trip (805 ms
     *   measured) and could hold the answer up behind it, so it is skipped and its audio fetched
     *   for next time instead;
     * - only with barge-in on: once a filler is audible the phone counts the reply as playing, and
     *   half-duplex (Bluetooth, a call) does not listen then, so words said during the lookup would
     *   be lost instead of merged into his turn.
     */
    fun fillerNow(due: Boolean, inHand: Boolean, bargeIn: Boolean): Boolean = due && inHand && bargeIn
}

/** The cascade's `LIVE_LATENCY` lines. The `key_ms=<n>` shape `tools/voice_latency.py` reads. */
internal object CascadeLines {
    fun open(ms: Long, probeMs: Long, plan: CascadeRules.TurnPlan, ttsStreams: Boolean, backend: String): String =
        "cascade_open_ms=$ms probe_ms=$probeMs backend=$backend " +
            "eot_mode=${if (plan.serviceEndsTurns) "service" else "phone"} " +
            "tts_text_streaming=$ttsStreams barge_in=${plan.config.bargeIn} smart_turn=${plan.config.smartTurn}"

    fun fallback(from: String, to: String, stage: String, reason: String): String =
        "engine_fallback from=$from to=$to stage=$stage reason=${reason.replace(' ', '_')}"

    fun eot(source: CascadeRules.EotSource, silenceMs: Long?, prob: Float? = null): String =
        "eot_source=${source.wire} eot_silence_ms=${silenceMs ?: -1}" +
            (prob?.let { " eot_prob=${"%.2f".format(java.util.Locale.US, it)}" } ?: "")

    /** A speculative draft began ([TurnDraft]); counts only, never the words. */
    fun specStart(trigger: String, backend: String, chars: Int): String =
        "spec_start trigger=$trigger backend=$backend chars=$chars"

    /** The turn was confirmed with the draft's words: how far ahead it started and how far it got. */
    @Suppress("LongParameterList")
    fun specKept(
        trigger: String,
        leadMs: Long,
        firstPartAheadMs: Long?,
        draftChars: Int,
        heldAtTool: Boolean,
        done: Boolean,
    ): String =
        "spec_kept trigger=$trigger lead_ms=$leadMs first_part_ahead_ms=${firstPartAheadMs ?: -1} " +
            "draft_chars=$draftChars held_at_tool=$heldAtTool done=$done"

    fun specDiscard(trigger: String, reason: String, ageMs: Long): String =
        "spec_discard trigger=$trigger reason=$reason age_ms=$ageMs"

    fun usage(model: String, u: GeminiTextLoop.Usage): String =
        "llm_usage model=$model prompt=${u.prompt} cached=${u.cached} out=${u.output} thoughts=${u.thoughts} " +
            "rounds=${u.rounds} cache_rounds=${u.cachedRounds} inline_rounds=${u.inlineRounds}"
}
