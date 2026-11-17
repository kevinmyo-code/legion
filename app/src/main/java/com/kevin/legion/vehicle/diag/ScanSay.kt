package com.kevin.legion.vehicle.diag

import org.json.JSONObject

/**
 * What a finished module scan says by voice, built by code (2026-11-16, stage 7 fleet eval: the main
 * model dropped the unanswered module when it composed the answer from the per-module JSON), and
 * which fleet questions are module scans. Split from [DiagVoice] so each stays small.
 */
object ScanSay {
    /**
     * The voice result of a finished scan (2026-11-16, stage 7 fleet eval). The words are built here,
     * by code ([say]), and handed back in `say` with [SAY_RULE]; no model composes them from the
     * per-module JSON any more (that is what dropped the unanswered module). The per-module detail
     * lives on the status_list card and the Faults screen.
     */
    internal fun answer(label: String, r: ScanReport, diff: ScanDiff?): JSONObject {
        val text = say(label, r, diff)
        return JSONObject()
            .put("success", r.stopped == null)
            .put("vehicle", label)
            .put("complete", r.complete)
            .put("say", text)
            .apply { if (r.stopped != null) put("message", text) }
            .put("rule", SAY_RULE + " " + ScanWords.ESTIMATE_RULE)
    }

    /**
     * The spoken answer for [r], built by code: the car, [ScanWords.summary], what changed since the
     * last scan. Checked against [ScanWords.mustSay]: a required line the summary somehow lacks is
     * appended, so the not-reached line can never be missing from `say`.
     */
    internal fun say(label: String, r: ScanReport, diff: ScanDiff?): String {
        val text = buildString {
            append("$label: ")
            append(ScanWords.summary(r))
            diff?.let { d ->
                if (d.added.isNotEmpty()) append(" New since the last scan: ${d.added.joinToString()}.")
                if (d.gone.isNotEmpty()) append(" Gone since the last scan: ${d.gone.joinToString()}.")
            }
        }
        val missing = ScanWords.mustSay(r).filterNot { it in text }
        return if (missing.isEmpty()) text else text + " " + missing.joinToString(" ")
    }

    /** Rides on every finished scan's result: say it back, never drop what was not reached. */
    const val SAY_RULE = "Say `say` back in full; only joining words may be your own. Every module it names " +
        "as not reached or not read must be said, and none of them called clear or fine. Never shorten it to " +
        "the modules that answered."

    /** The fleet questions answered by a module scan without the fleet helper model ([LiveToolbox] ask_fleet). */
    fun isModuleScanQuestion(question: String): Boolean = SCAN_ASK.containsMatchIn(question)

    private val SCAN_ASK = Regex(
        // "scan" alone is a module scan here: ask_fleet and clear_codes reach this only with car words.
        "\\b(every|all|each|any|the|my)\\b[\\w ]{0,16}\\b(modules?|computers?|ecus?|controllers?)\\b" +
            "|\\bmodule scan\\b|\\bscan\\b",
        RegexOption.IGNORE_CASE,
    )
}
