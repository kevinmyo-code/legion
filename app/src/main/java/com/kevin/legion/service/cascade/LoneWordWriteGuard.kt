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
 * Unless the previous turn's TOOL RESULT asked him something ([asks]): then one word is his answer
 * ("Yes." after a held write, "Groceries." after "Which list?" from a tool). Only tool data decides,
 * never the model's words; a question the model asked on its own (no tool) does not count, so a
 * yes to it is held once more (the stated cost).
 *
 * Voice only: the cascade's executor and the voice eval call it; typed chat and Live never do.
 */
object LoneWordWriteGuard {

    const val REFUSAL = "Nothing was done: his whole turn was one word, which is not enough to change " +
        "anything. Ask him in one short question whether to do it; his yes lets it run."

    /** The refusal for the write [name]/[args] in a turn of [heard], else null (it may run). */
    fun refusal(name: String, args: JSONObject, heard: String, companionName: String, previousAsked: Boolean): JSONObject? {
        if (previousAsked || !isLoneWord(heard, companionName) || !CascadeRules.isWrite(name, args)) return null
        return JSONObject().put("success", false).put("needs_yes", true).put("message", REFUSAL)
    }

    /**
     * A tool result that asked him something, so that one word next is his answer: a refusal or a
     * question (`success: false`, which includes [refusal]'s own), an explicit confirmation ask
     * (`needs_yes`, `needs_confirmation`), a mail draft waiting for its send (`draft_id`), or the
     * first step of a two-step write (a writing tool called so that it did not write, e.g. an
     * unconfirmed clear_codes, which returns the warning to read back).
     */
    fun asks(name: String, args: JSONObject, response: JSONObject): Boolean {
        if (response.has("success") && !response.optBoolean("success", true)) return true
        if (response.optBoolean("needs_yes", false) || response.optBoolean("needs_confirmation", false)) return true
        if (response.has("draft_id")) return true
        val (resolved, resolvedArgs) = LiveSurface.resolve(name, args)
        return LiveToolbox.isMutatingTool(resolved) && !CascadeRules.isWrite(name, args) &&
            !ToolReads.isRead(resolved, resolvedArgs)
    }

    /** [heard] is one word once [companionName] (at either end) and a leading hey/ok/okay are set aside. */
    fun isLoneWord(heard: String, companionName: String): Boolean {
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
}
