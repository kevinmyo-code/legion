package com.kevin.legion.service.cascade

import org.json.JSONArray
import org.json.JSONObject

/**
 * A tool turn that ends on filler (2026-11-16, real-model runs): after a tool result, often a
 * refusal that names another tool, flash-lite sometimes ends the turn with only "Checking." /
 * "Let me look." / "Checking the modules.", so he hears a filler and then silence.
 * [GeminiTextLoop] holds back such a line instead of speaking it, and asks the model ONCE more,
 * tool-free, with [NOTE]. A second filler-only answer ends the turn on the existing failure line
 * ([com.kevin.legion.service.TextFailure.NO_TEXT]); it never loops.
 *
 * **The rule ([isFillerOnly]), written down so it stays tight.** The text, after the
 * [LeadingFiller] strip (one leading exact scripted filler sentence before more text), is filler
 * only when it is ONE of:
 * 1. exactly a scripted filler line ([ScriptedLines.FILLERS]), case, surrounding whitespace and the
 *    final `.!?…` aside; or
 * 2. one sentence of at most [MAX_WORDS] words with no digit and no `, ; :` that begins with a
 *    filler opener ([OPENERS]: checking, looking, let me look, let me check, one moment, just a
 *    moment, on it, hold on) and whose remaining words, if any, start with a word from [TAIL_START]
 *    ("Checking the modules.", "Looking into it.", "Checking now."). "Looking good.", "On it, timer
 *    set." and "Checking: two codes." are not filler.
 * Pure: no clock, no Android (only `org.json` for [noteContent]).
 */
internal object FillerTurn {
    const val MAX_WORDS = 4

    /** Past this many characters a line still being written is no longer held back. */
    const val HOLD_MAX_CHARS = 60

    private const val ENDS = ".!?…"

    val OPENERS = listOf(
        "checking", "looking", "let me look", "let me check", "one moment", "just a moment", "on it", "hold on",
    )

    /** The first word after an opener that keeps a line filler ("checking THE modules"). */
    val TAIL_START = setOf(
        "now", "into", "at", "up", "for", "the", "that", "this", "it", "your", "those", "on", "over",
        "through", "please", "again",
    )

    /** The one-time system note: the lookup is over, answer now from what came back (honesty clause stands). */
    const val NOTE = "(System note: the lookup is finished. Give him the answer now, from the tool results " +
        "above, in plain words. If it could not be done or the results do not say, tell him that plainly. " +
        "Do not say you are checking or looking.)"

    fun noteContent(): JSONObject = JSONObject().put("role", "user")
        .put("parts", JSONArray().put(JSONObject().put("text", NOTE)))

    /** True when [text] is nothing but filler under the rule above. Blank is not filler. */
    @Suppress("ReturnCount") // one exit per clause of the rule
    fun isFillerOnly(text: String): Boolean {
        val stripped = LeadingFiller.strip(text.trim()).trim()
        if (stripped.isEmpty()) return false
        val core = LeadingFiller.core(stripped)
        if (core in LeadingFiller.CORES) return true
        if (core.any { it in ENDS || it in ",;:" || it.isDigit() }) return false
        val words = core.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size > MAX_WORDS) return false
        val opener = OPENERS.firstOrNull { o -> core == o || core.startsWith("$o ") } ?: return false
        val tail = core.removePrefix(opener).trim()
        return tail.isEmpty() || tail.substringBefore(' ') in TAIL_START
    }

    /**
     * While a line is still streaming: could it still turn out to be filler? Only then is it held.
     * [partial] is the round's text so far.
     */
    fun mayBeFiller(partial: String): Boolean {
        val s = partial.trimStart().lowercase()
        return s.isEmpty() || (s.length <= HOLD_MAX_CHARS && OPENERS.any { o -> o.startsWith(s) || s.startsWith(o) })
    }

    /** [partial] has a finished sentence with more text after it: content follows, so it all goes. */
    fun contentFollows(partial: String): Boolean {
        val s = partial.trim()
        val end = s.indexOfFirst { it in ENDS }
        if (end < 0) return false
        var i = end
        while (i < s.length && s[i] in ENDS) i++
        return s.substring(i).isNotBlank()
    }

    /**
     * One model round's text after a tool ran, held while it may be filler ([mayBeFiller]) and let
     * go the moment it is not. [end] says whether the whole round was filler.
     */
    class Hold {
        private val buf = StringBuilder()
        private var decided = false

        /** True once [end] judged the held round to be filler only. */
        var filler = false
            private set

        /** The text to pass on now ("" while held). */
        fun feed(delta: String): String {
            if (decided) return delta
            buf.append(delta)
            val s = buf.toString()
            return if (mayBeFiller(s) && !contentFollows(s)) "" else release()
        }

        /** The round is over: the held text if it was not filler, else "" and [filler] is set. */
        fun end(): String = when {
            decided -> ""
            isFillerOnly(buf.toString()) -> {
                decided = true
                filler = true
                buf.setLength(0)
                ""
            }
            else -> release()
        }

        private fun release(): String {
            decided = true
            val s = buf.toString()
            buf.setLength(0)
            return s
        }
    }
}
