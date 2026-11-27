package com.kevin.legion.service.cascade

import org.json.JSONArray

/**
 * Which of two fixed tool sets a cascade turn is sent with (#453): [Set.CHAT] (search, plus the one
 * tool that ends a conversation) for a turn that is a whole-utterance pleasantry or closer, and
 * [Set.FULL] (everything) for every other turn.
 *
 * CHAT is a CLOSED allowlist ([PLEASANTRY]) and nothing else. An earlier cut also sent "wh-questions
 * with no personal word" to CHAT; blind review (2026-11-26) showed that open set never closes: a
 * weekday, a named event, a person, a stored fact, a car by name or a package all read as general
 * knowledge and then got "I can't see that". A turn that needs a personal tool and does not get one
 * is the failure; a small-talk turn that gets the full set only costs what it cost before.
 *
 * The decision is made once per turn from his words and the conversation, never by a model call. The
 * chat prefix is [CascadePrefix.cacheable] false: it is sent inline and never gets a cache of its own
 * (a pleasantry-only conversation is one or two turns, so a create would rarely be paid back), which
 * leaves the full set's one cache untouched.
 */
internal object ToolGate {
    enum class Set { CHAT, FULL }

    /** [why] is a log token. */
    data class Verdict(val set: Set, val why: String)

    /** The tools a chat turn keeps: ending the conversation ("that's all") is itself a closer. */
    val CHAT_TOOLS = setOf("end_conversation")

    // Whole-utterance only. Affirmatives (yes, ok, sure, do it) are NOT here: they answer something,
    // and what they answer may have been an offer to write. "ok thanks" is a closer, "ok" is not.
    private val PLEASANTRY = Regex(
        "^(?:(?:ok(?:ay)? )?(?:thanks|thank you|thx|cheers)(?: (?:so |very )?much)?(?: (?:a lot|again))?|" +
            "(?:hi|hello|hey|yo)(?: there)?|(?:good )?(?:morning|afternoon|evening|night)|" +
            "how are you(?: doing)?(?: today)?|how'?s it going|what'?s up|" +
            "(?:good ?)?bye(?: now)?|goodbye|see you(?: later| soon)?|talk to you later|" +
            "that'?s all|that will be all|never ?mind|stand down)$",
    )

    /**
     * Lowercase, straight apostrophes, punctuation to spaces (letters and digits of EVERY
     * script are kept, so a request in Mandarin, Thai or Burmese after "thanks" leaves words that fail the
     * match), whitespace squeezed, and a leading "hey <name>" call removed when [companion] is the name
     * he is talking to.
     */
    internal fun normalise(heard: String, companion: String?): String {
        var t = heard.lowercase().replace('’', '\'')
        t = t.replace(Regex("[^\\p{L}\\p{N}' ]"), " ").replace(Regex("\\s+"), " ").trim()
        val name = companion?.lowercase()?.trim().orEmpty()
        if (name.isNotEmpty()) {
            t = t.replace(Regex("^(?:(?:hey|hi|hello|ok|okay|yo) )?${Regex.escape(name)}(?: |$)"), "").trim()
        }
        return t
    }

    /**
     * The set for this turn. [sticky]: an earlier turn of this conversation went out FULL (or was
     * answered by the engine), so a bare "and tomorrow?" keeps its tools.
     */
    fun choose(heard: String, sticky: Boolean, companion: String? = null): Verdict {
        if (sticky) return Verdict(Set.FULL, "sticky")
        val text = normalise(heard, companion)
        return when {
            text.isEmpty() -> Verdict(Set.FULL, "empty")
            PLEASANTRY.matches(text) -> Verdict(Set.CHAT, "pleasantry")
            else -> Verdict(Set.FULL, "not_a_pleasantry")
        }
    }

    /**
     * The verdict for a turn. FULL without looking at the words when it is not his own speech, an opener
     * or a carried turn is in play ([notHisWords]), or [askedLast]: Alfred's last line asked or offered
     * something, so even "ok thanks" may be the answer to it.
     */
    fun decide(
        heard: String,
        fromUser: Boolean,
        notHisWords: Boolean,
        askedLast: Boolean,
        sticky: Boolean,
        companion: String?,
    ): Verdict = when {
        !fromUser || notHisWords -> Verdict(Set.FULL, "not_his_words")
        askedLast -> Verdict(Set.FULL, "offer_open")
        else -> choose(heard, sticky, companion)
    }

    /** [full] cut to [CHAT_TOOLS], sent inline: the same instruction and search, no cache of its own. */
    fun chatPrefix(full: CascadePrefix): CascadePrefix {
        val kept = JSONArray()
        for (i in 0 until full.functionDeclarations.length()) {
            val d = full.functionDeclarations.optJSONObject(i) ?: continue
            if (d.optString("name") in CHAT_TOOLS) kept.put(d)
        }
        return full.copy(functionDeclarations = kept, cacheable = false)
    }

    /** The prefix to send for this turn. */
    fun prefixFor(full: CascadePrefix, verdict: Verdict): CascadePrefix =
        if (verdict.set == Set.CHAT) chatPrefix(full) else full
}
