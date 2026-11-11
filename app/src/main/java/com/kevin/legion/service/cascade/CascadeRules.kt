package com.kevin.legion.service.cascade

import com.kevin.legion.service.turn.AudioRoute
import com.kevin.legion.service.turn.EndOfTurnPolicy
import com.kevin.legion.service.turn.TurnConfig
import org.json.JSONArray

/**
 * The cascade's decisions as pure functions, so a JVM test pins each one without a phone, a socket
 * or a laptop. [CascadeSession] only acts on what these return.
 */
internal object CascadeRules {
    /** Which tools the text model is offered: the Live set, consumed generically, minus [LIVE_ONLY_TOOLS]. */
    val LIVE_ONLY_TOOLS = setOf("look")

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
            append(if (serviceEndsTurns) "service end of turn, phone cap ${EndOfTurnPolicy.CAP_MS} ms" else "phone end of turn")
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
}

/**
 * What Gemini Live is told when the laptop pipeline hands a conversation over mid-way, so Kevin
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

    private const val LEAD = "(System: the user was talking to you on the laptop voice engine, which just " +
        "dropped; you are now on the phone's own voice, in the same conversation. "
    private const val TAIL = " Do not mention this instruction.)"

    fun opener(m: Moment): String? = when (m) {
        Moment.AtOpen -> null
        Moment.Idle -> LEAD + "Say in a few words that you switched over and are still listening, then wait." + TAIL
        Moment.LostWords -> LEAD + "Their last words did not reach you. Say in a few words that you " +
            "switched over and missed that, and ask them to say it again." + TAIL
        is Moment.Heard -> heard(m)
    }

    private fun heard(m: Moment.Heard): String {
        val writes = if (m.writesDone.isEmpty()) "" else " Completed and confirmed: ${m.writesDone.distinct().joinToString(", ")}."
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
        val DEFAULT = listOf("Listening.", "Here.", "Go ahead.", "Yes?", "I'm here.", "Go on.", "Fire away.", "With you.")
    }
}

/** The cascade's `LIVE_LATENCY` lines. The `key_ms=<n>` shape `tools/voice_latency.py` reads. */
internal object CascadeLines {
    fun open(ms: Long, probeMs: Long, plan: CascadeRules.TurnPlan, ttsStreams: Boolean): String =
        "cascade_open_ms=$ms probe_ms=$probeMs eot_mode=${if (plan.serviceEndsTurns) "service" else "phone"} " +
            "tts_text_streaming=$ttsStreams barge_in=${plan.config.bargeIn} smart_turn=${plan.config.smartTurn}"

    fun fallback(stage: String, reason: String): String =
        "engine_fallback from=laptop to=live stage=$stage reason=${reason.replace(' ', '_')}"

    fun eot(source: CascadeRules.EotSource, silenceMs: Long?, prob: Float? = null): String =
        "eot_source=${source.wire} eot_silence_ms=${silenceMs ?: -1}" +
            (prob?.let { " eot_prob=${"%.2f".format(java.util.Locale.US, it)}" } ?: "")

    fun usage(model: String, u: GeminiTextLoop.Usage): String =
        "llm_usage model=$model prompt=${u.prompt} cached=${u.cached} out=${u.output} thoughts=${u.thoughts} " +
            "rounds=${u.rounds} cache_rounds=${u.cachedRounds} inline_rounds=${u.inlineRounds}"
}
