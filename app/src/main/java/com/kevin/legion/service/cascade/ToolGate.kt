package com.kevin.legion.service.cascade

import org.json.JSONArray

/**
 * Which of two fixed tool sets a cascade turn is sent with (#453): [Set.CHAT] (search, plus the one
 * tool that ends a conversation) for a turn that is plainly small talk or general knowledge, and
 * [Set.FULL] (everything) for every other turn. Two sets, not a list built per turn, because the
 * explicit Gemini context cache keys on the tool declarations ([CascadePrefix.key]): each set has a
 * prefix and a cache of its own ([ContextCacheStore] holds one per set), and the choice per turn is
 * only which prefix to send.
 *
 * The decision is computed once per turn from the user's words and the conversation, never by a
 * model call (an extra call would cost what the gate saves). It is biased to FULL: a turn is CHAT
 * only when it is a whole-utterance pleasantry, or a wh-/is/are question with none of
 * [PERSONAL_TRIGGERS] in it. A turn that needs a personal tool and does not get one is the failure;
 * a small-talk turn that gets the full set only costs what it cost before. A tool that is not
 * declared cannot be called, so the honesty rules still hold: nothing here lets the model claim
 * more.
 */
internal object ToolGate {
    enum class Set { CHAT, FULL }

    /** [why] is a log token: the table row that decided. */
    data class Verdict(val set: Set, val why: String)

    /** The tools a chat turn keeps: ending the conversation ("that's all") is itself small talk. */
    val CHAT_TOOLS = setOf("end_conversation")

    /** Longest turn, in characters, that can be chat. A long ask is rarely small talk. */
    const val MAX_CHAT_CHARS = 160

    // Whole-utterance pleasantries and closings. Affirmatives (yes, ok, sure, do it) are NOT here:
    // they answer something, and what they answer may have been an offer to write.
    private val PLEASANTRY = Regex(
        "^(?:(?:hi|hello|hey|yo)(?: there)?|(?:good )?(?:morning|afternoon|evening|night)|" +
            "(?:thanks|thank you|thx|cheers)(?: (?:so |very )?much)?(?: (?:a lot|again))?|" +
            "how are you(?: doing)?(?: today)?|how'?s it going|what'?s up|how'?s your day|" +
            "(?:good ?)?bye(?: now)?|goodbye|see you(?: later| soon)?|talk to you later|" +
            "that'?s all|that will be all|never ?mind|stand down|" +
            "(?:nice|cool|great|awesome|wow|lol|haha|ha ha|hmm+|interesting|funny|perfect)|" +
            "(?:that'?s|that is) (?:funny|great|nice|cool|awesome|interesting|good|perfect)|" +
            "(?:you'?re|you are) (?:funny|great|awesome|the best)|" +
            "tell me (?:a|another) (?:joke|story|fun fact)|are you there)$",
    )

    /** A question about the world, or an explain-me request. Only reached when no trigger fired. */
    private val KNOWLEDGE = Regex(
        "^(?:who|whom|whose|what|why|how|which|where|when|is|are|was|were|" +
            "explain|define|translate|describe|" +
            "tell (?:about|why|how|what|who|when|where))\\b.*",
    )

    /**
     * Words that make a turn possibly about him, his data or an action, so FULL. Deliberately wide:
     * each is a reason a personal tool could be wanted. Matched as whole words anywhere in the turn.
     * One row per family; [Verdict.why] names the row.
     */
    private val PERSONAL_TRIGGERS: List<Pair<String, Regex>> = listOf(
        "first_person" to words("i|i'?m|i'?ve|i'?ll|i'?d|me|my|mine|myself|we|we'?re|we'?ve|our|ours|us|let'?s"),
        "memory" to words("remember|remind|reminder|reminders|forget|recall|note|notes|memory|memories|save|saved|keep track"),
        "calendar" to words(
            "calendar|schedule|scheduled|event|events|meeting|meetings|appointment|appointments|agenda|" +
                "today|tonight|tomorrow|yesterday|weekend|this (?:morning|afternoon|evening|week|month)|" +
                "next (?:week|month)|free|busy|booked|due|deadline|birthday|anniversary",
        ),
        "records" to words(
            "add|log|record|track|delete|remove|update|edit|change|set|create|cancel|move|rename|" +
                "list|lists|checklist|checklists|todo|task|tasks|item|items|table|records?",
        ),
        "money" to words(
            "spent|spend|spending|bought|buy|paid|pay|owe|owed|balance|budget|bank|account|accounts|" +
                "checking|savings|ledger|money|cash|dollars?|bucks|cents|receipt|receipts|" +
                "transaction|transactions|bill|bills|rent|income|salary|net worth|expense|expenses|\\$\\d*",
        ),
        "home_data" to words(
            "pantry|fridge|groceries|grocery|meal|meals|macros|calories|protein|workout|weight|waist|sleep|" +
                "steps|health|car|cars|vehicle|truck|oil|mileage|odometer|gas|fuel|tire|tires|maintenance|" +
                "recall|recalls|trip|trips|drive|drives|place|places|home|house|books?|library|vault",
        ),
        "device_action" to words(
            "play|playing|plays|played|pause|stop|skip|next|volume|music|song|songs|playlist|spotify|" +
                "call|calling|calls|called|missed|voicemail|contact|contacts|text|texted|email|emailed|mail|" +
                "message|messages|navigate|navigation|directions|route|traffic|timer|alarm|open|launch|send|" +
                "turn|start|photo|camera|look|brightness|bluetooth|wifi|battery|phone",
        ),
        "here_and_now" to words(
            "weather|forecast|temperature|rain|raining|snow|outside|nearby|near|nearest|closest|here|" +
                "time|date|day|now|current|currently|this|these|those|it|its|new|latest|news|score|scores",
        ),
        "claude_and_code" to words("claude|session|github|pull request|repo|deploy|build|update|version"),
    )

    private fun words(alternatives: String) = Regex("(?<![a-z0-9'])(?:$alternatives)(?![a-z0-9'])")

    /**
     * Lowercase, straight apostrophes, punctuation to spaces, whitespace squeezed, and a leading
     * "hey <name>" call removed when [companion] is the name he is talking to.
     */
    internal fun normalise(heard: String, companion: String?): String {
        var t = heard.lowercase().replace('’', '\'')
        t = t.replace(Regex("[^a-z0-9' $]"), " ").replace(Regex("\\s+"), " ").trim()
        val name = companion?.lowercase()?.trim().orEmpty()
        if (name.isNotEmpty()) {
            t = t.replace(Regex("^(?:(?:hey|hi|hello|ok|okay|yo) )?${Regex.escape(name)}(?: |$)"), "").trim()
        }
        return t
    }

    /**
     * The set for this turn. [sticky]: an earlier turn of this conversation was FULL (a personal
     * tool may be in play, and a bare "and tomorrow?" has no words of its own), so it stays FULL.
     */
    fun choose(heard: String, sticky: Boolean, companion: String? = null): Verdict {
        if (sticky) return Verdict(Set.FULL, "sticky")
        val text = normalise(heard, companion)
        return when {
            text.isEmpty() -> Verdict(Set.FULL, "empty")
            text.length > MAX_CHAT_CHARS -> Verdict(Set.FULL, "long")
            PLEASANTRY.matches(text) -> Verdict(Set.CHAT, "pleasantry")
            else -> {
                // "tell me about X" is the asking form of a knowledge question, not "me" in the first-person sense.
                val probe = text.replace(Regex("^tell me "), "tell ")
                val hit = PERSONAL_TRIGGERS.firstOrNull { it.second.containsMatchIn(probe) }
                when {
                    hit != null -> Verdict(Set.FULL, "trigger_" + hit.first)
                    KNOWLEDGE.matches(probe) -> Verdict(Set.CHAT, "knowledge")
                    else -> Verdict(Set.FULL, "no_chat_shape")
                }
            }
        }
    }

    /** [full] cut to [CHAT_TOOLS]: the same instruction and search, a prefix and cache of their own. */
    fun chatPrefix(full: CascadePrefix): CascadePrefix {
        val kept = JSONArray()
        for (i in 0 until full.functionDeclarations.length()) {
            val d = full.functionDeclarations.optJSONObject(i) ?: continue
            if (d.optString("name") in CHAT_TOOLS) kept.put(d)
        }
        return full.copy(functionDeclarations = kept)
    }

    /** The prefix to send for this turn, and the verdict (for the log). */
    fun prefixFor(full: CascadePrefix, verdict: Verdict): CascadePrefix =
        if (verdict.set == Set.CHAT) chatPrefix(full) else full
}
