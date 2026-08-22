package com.kevin.legion.service

import com.kevin.legion.ai.ALFRED
import com.kevin.legion.ai.SHARED_INSTRUCTIONS
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Measures what the Live `setup` message actually costs, and holds it under a ceiling.
 *
 * Hands-and-senses ticket 24: the server closes the prewarmed socket roughly every 2 minutes 33
 * seconds all day, and [GeminiLiveSession]'s `buildSetup` re-sends `systemInstruction` AND the full
 * `tools` array on every reconnect - a resume handle does not change that (nothing in `buildSetup`
 * branches on `requestedResumeHandle` except the `sessionResumption.handle` field itself). So the
 * size of this payload is paid ~565 times a day on the owner's own key with nobody using the app.
 * Nobody had ever measured it; the ticket's first deliverable is the number, not a fix.
 *
 * **The estimator is chars/4**, the same arithmetic [com.kevin.legion.advisor.PrimingTopic.MAX_CHARS]
 * and `PlaybookKeywordsTest` already use, and whose accuracy that doc comment records as about 4%
 * against `countTokens` on this codebase's prose. Deliberately not a second estimator: a figure
 * measured two ways cannot be compared with the ones already written down. Prose is what the tool
 * descriptions mostly are, but JSON punctuation and identifiers tokenize worse than prose, so treat
 * every token figure here as an ESTIMATE and a floor-ish one, never as a billed count.
 *
 * **What is measured and what is not.** The tools array is the real one -
 * [LiveToolbox.declarations], wrapped exactly as `buildSetup` wraps it (a `googleSearch` entry plus
 * one `functionDeclarations` object). The system instruction measured here is the DEVICE-INDEPENDENT
 * part only: the shipped persona register ([ALFRED]) plus [SHARED_INSTRUCTIONS]. Deliberately
 * excluded, because they need a Context, a Room read or today's date and would make the ceiling
 * drift with the machine rather than with the code:
 *
 * - `AriaBrain.safetyInstructions` (private) - 1,099 chars, ~274 tokens, counted off the source
 *   2026-08-22.
 * - the date/clock block, the driver profile fragment, the fleet fragment, and `buildLiveContext`.
 *
 * The excluded block is small next to the tool surface, which is the half that grows every time a
 * tool is added - and growth is what this ceiling exists to catch.
 *
 * Runs under Robolectric for `org.json`, which is stubbed (and throws) in a plain JVM unit test.
 */
@RunWith(RobolectricTestRunner::class)
class LiveSetupPayloadSizeTest {

    /** chars/4, floor. The one estimator this codebase uses - see the class doc. */
    private fun tokens(chars: Int): Int = chars / 4

    /** The tools array exactly as `buildSetup` assembles it. */
    private fun toolsArray(fns: JSONArray): JSONArray =
        JSONArray()
            .put(JSONObject().put("googleSearch", JSONObject()))
            .put(JSONObject().put("functionDeclarations", fns))

    /** The static half of the system instruction - see the class doc for what is left out. */
    private fun systemInstruction(): String =
        ALFRED.clause.trimIndent() + " " + ALFRED.delivery + " " + SHARED_INSTRUCTIONS

    /**
     * The ceiling.
     *
     * Measured 2026-08-22: **66** declarations, 56,879 chars of tools JSON plus 7,879 chars of
     * system instruction = 64,758 chars, ~16,189 estimated tokens. The ceiling is 20,000 estimated
     * tokens, about 23% of headroom, so an ordinary tool addition does not trip it but a dozen do.
     * If this fails, the tool surface grew - measure before trimming, and move the ceiling only
     * with the new measured figure written into this comment beside the old one.
     *
     * 66, not the 101 the ticket assumed: 101 is every `fn(name = ...)` in [LiveToolbox], which
     * includes the onboarding-only set and the tools the 2026-08-17 dispatcher split hid behind an
     * `ask_*` tool. [LiveToolbox.declarations] is what the setup message carries.
     */
    private val ceilingTokens = 20_000

    @Test
    fun `the setup payload stays under its stated ceiling`() {
        val fns = LiveToolbox.declarations()
        val toolsChars = toolsArray(fns).toString().length
        val instructionChars = systemInstruction().length
        val totalChars = toolsChars + instructionChars
        val totalTokens = tokens(totalChars)

        println(
            "setup payload: ${fns.length()} declarations, $toolsChars chars of tools JSON + " +
                "$instructionChars chars of system instruction = $totalChars chars, " +
                "~$totalTokens estimated tokens (chars/4)"
        )

        assertTrue(
            "the Live setup payload is ~$totalTokens estimated tokens (chars/4) and the stated " +
                "ceiling is $ceilingTokens - re-measure and justify before raising it, this is " +
                "re-sent on every reconnect",
            totalTokens <= ceilingTokens,
        )
    }

    /**
     * Names the largest declarations, biggest first. No assertion - the ceiling above is the gate;
     * this exists so whoever trips it can see which tool to look at rather than guessing. One
     * earlier data point to compare against: `manage_item` measured roughly 1,004 tokens when it
     * was trimmed on 2026-08-17, as the largest of 79 declarations.
     */
    @Test
    fun `per-tool breakdown names the largest declarations`() {
        val fns = LiveToolbox.declarations()
        val sized = (0 until fns.length())
            .map { fns.getJSONObject(it) }
            .map { it.getString("name") to it.toString().length }
            .sortedByDescending { it.second }

        println("per-tool declaration sizes, largest first (chars, ~tokens at chars/4):")
        for ((name, chars) in sized) println("  $name: $chars, ~${tokens(chars)}")

        val total = sized.sumOf { it.second }
        println("declarations total: ${sized.size} tools, $total chars, ~${tokens(total)} tokens")
    }
}
