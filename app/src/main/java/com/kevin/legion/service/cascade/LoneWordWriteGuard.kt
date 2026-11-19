package com.kevin.legion.service.cascade

import com.kevin.legion.service.LiveToolbox
import com.kevin.legion.service.tools.LiveSurface
import com.kevin.legion.service.tools.ToolReads
import org.json.JSONObject

/**
 * One heard word never changes anything on its own (2026-11-19, eval case `lone_word_write`: a bare
 * "Five." switched the active car and nothing was said). In a spoken turn whose words are one word
 * (his companion's name and a leading hey/ok/okay aside), every write is refused with `needs_yes`
 * and nothing runs: the model asks him in one question, and his next word can let it run.
 *
 * Unless the previous turn's TOOL RESULT explicitly asked him something ([asks]): then one word is
 * his answer ("Yes." after a held write or a mail draft). Only a field in the result decides, never
 * the model's words and never a plain failure (review F6: a read that failed offline must not open
 * the next one-word write). A question the model asked on its own, or a tool's clarifying refusal
 * with no such field ("Which car?"), does not count, so a one-word answer to it is held once more
 * (the stated cost, Kevin told 2026-11-19).
 *
 * Only for words in a script written with spaces between words (review F7): a Chinese, Japanese,
 * Thai, Lao, Burmese, Khmer or Tibetan sentence has no spaces, so a whole sentence would count as
 * "one word". Those are never held (a known limit: a lone word in them is not guarded).
 *
 * Voice only: the cascade's executor and the voice eval call it; typed chat and Live never do.
 */
object LoneWordWriteGuard {

    const val REFUSAL = "Nothing was done: his whole turn was one word, which is not enough to change " +
        "anything. Ask him in one short question whether to do it; his yes lets it run."

    /** The refusal for the write [name]/[args] in a turn of [heard], else null (it may run). */
    fun refusal(
        name: String,
        args: JSONObject,
        heard: String,
        companionName: String,
        previousAsked: Boolean,
    ): JSONObject? {
        if (previousAsked || !isLoneWord(heard, companionName) || !CascadeRules.isWrite(name, args)) return null
        // `needs_answer`: this result asks him, so his next one word may answer it ([asks]).
        return JSONObject().put("success", false).put("needs_yes", true).put(NEEDS_ANSWER, true)
            .put("message", REFUSAL)
    }

    /** On a tool result that asks him a question (the adapter's "which car is this?", this guard's hold). */
    const val NEEDS_ANSWER = "needs_answer"

    /**
     * A tool result that explicitly asked him something, so that one word next is his answer:
     * [NEEDS_ANSWER] (the one field for a question, 2026-11-19; [refusal]'s own hold carries it), a
     * confirmation ask that existed before it (`needs_confirmation`), a mail draft waiting for its
     * send (`draft_id`), or the first step of a two-step write (a writing tool called so that it did
     * not write, e.g. an unconfirmed clear_codes, which returns the warning to read back). Never a
     * plain `success: false`.
     */
    fun asks(name: String, args: JSONObject, response: JSONObject): Boolean {
        val (resolved, resolvedArgs) = LiveSurface.resolve(name, args)
        val firstStep = LiveToolbox.isMutatingTool(resolved) && !CascadeRules.isWrite(name, args) &&
            !ToolReads.isRead(resolved, resolvedArgs)
        return response.optBoolean(NEEDS_ANSWER, false) || response.optBoolean("needs_confirmation", false) ||
            response.has("draft_id") || firstStep
    }

    /**
     * [heard] is one word once [companionName] (at either end) and a leading hey/ok/okay are set
     * aside. Never for text in a script written without spaces ([UNSPACED]).
     */
    fun isLoneWord(heard: String, companionName: String): Boolean {
        if (heard.codePoints().anyMatch { Character.UnicodeScript.of(it) in UNSPACED }) return false
        var words = words(heard)
        val name = words(companionName)
        fun dropName() {
            if (name.isEmpty()) return
            if (words.take(name.size) == name) words = words.drop(name.size)
            if (words.takeLast(name.size) == name) words = words.dropLast(name.size)
        }
        dropName()
        while (words.size > 1 && words.first() in LEAD_INS) {
            words = words.drop(1)
            dropName()
        }
        return words.size == 1
    }

    /** Lowercase words; an apostrophe inside a word keeps it one word ("don't"). */
    private fun words(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).map { it.trim('\'') }.filter { it.isNotEmpty() }

    private val LEAD_INS = setOf("hey", "ok", "okay")

    /** Scripts written without spaces between words: "one word" cannot be counted there. */
    internal val UNSPACED = setOf(
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.THAI, Character.UnicodeScript.LAO, Character.UnicodeScript.MYANMAR,
        Character.UnicodeScript.KHMER, Character.UnicodeScript.TIBETAN,
    )
}
