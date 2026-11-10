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
     *
     * **Raised to 21,000 on 2026-09-01 (Kevin). Measured that day: 84 declarations, 74,124 chars of
     * tools JSON plus 7,879 chars of system instruction = 82,003 chars, ~20,500 estimated tokens.**
     * Eighteen declarations and 17,245 chars of tools JSON more than the 2026-08-22 figure. The four
     * voice-note tools (`start_voice_note`, `stop_voice_note`, `read_voice_note`,
     * `list_voice_notes`) crossed the old ceiling. Their descriptions are long because three of
     * them carry honesty caveats that are load-bearing rather than decorative - `stop_voice_note`
     * must not let the model claim a note is transcribed when only the audio has been saved, and
     * `read_voice_note` must not let a figure heard in a recording be repeated as confirmed fact
     * (CLAUDE.md §7's outcome-verb rule and ADR 0041's "nothing numeric heard in a recording may be
     * asserted as fact"). Trimming them to fit would have bought ~500 tokens by weakening exactly
     * the sentences that keep the feature honest, so the ceiling moved instead.
     *
     * The headroom is now ~2% rather than ~23%, which is the real cost of this decision: the next
     * tool addition trips this test. That is the intended behaviour, not a problem to pre-empt -
     * re-measure and justify again, or trim, at that point.
     *
     * **Raised to 22,000 on 2026-09-05 (one-today ticket 09's voice slice, `manage_checklist`).**
     * Measured that day: 86 declarations, 77,749 chars of tools JSON plus 7,879 chars of system
     * instruction = 85,628 chars, ~21,407 estimated tokens. `manage_checklist` alone (create/add/
     * tick/untick/remove/read/lists, plus measured-item support) was already trimmed once - 2,787
     * down to 2,262 chars, ~565 tokens, the single largest declaration in this surface - before this
     * raise, the same order the 2026-08-17 `manage_item` trim went in; trimming it further would
     * cost the schedule/measured-item rules an `action`-dispatched tool like this genuinely needs,
     * not decoration. Exactly the "next tool addition trips this test" the prior entry predicted.
     *
     * **Raised to 22,250 on 2026-10-02 (engine-mcp ticket 11, `ask_engine`).** Measured that day:
     * 86 declarations, 80,567 chars of tools JSON plus 7,879 chars of system instruction = 88,446
     * chars, ~22,111 estimated tokens, against ~21,950 before it (about 160 tokens). It is the ONE
     * declaration ticket 04 ruled for the whole engine tool registry (option C), so its cost is
     * constant however many tools the engine grows; the alternative was one declaration per
     * engine tool. Already trimmed to a two-sentence description and a one-line `intent`; the
     * rules it carries (engine-unreachable wording, record-versus-ask) cannot move out because
     * the model only ever sees this block.
     *
     * **Raised to 22,500 on 2026-10-03 (`complete_task`, voice marking of calendar tasks done).**
     * Measured that day: ~22,279 estimated tokens with the tool, so it costs ~170 tokens against
     * ~22,110 before it. Trimmed twice first (a `to` arg dropped, description cut to three
     * clauses); what is left is the trigger phrases that route "I finished X" here rather than to
     * `manage_item` (reminders), and the "claim only what the result says changed" clause that
     * CLAUDE.md section 7's outcome-verb rule needs on a write tool.
     *
     * **NOT raised on 2026-10-04 (purchase-log ticket 08, `bought_log`).** Measured that day: 88
     * declarations, 82,089 chars of tools JSON plus 7,879 chars of system instruction = 89,968 chars,
     * ~22,492 estimated tokens, so the bought log costs ~213 tokens against ~22,279 before it and
     * leaves 8 tokens of headroom. It is ONE action-dispatched declaration (log / last / recent),
     * not three, and its description was trimmed three times to fit; the next tool addition trips
     * this test and needs its own measurement and justification.
     *
     * **Raised to 22,750 on 2026-10-03 (mapbox-nav ticket 11, the four navigation tools).**
     * Measured that day: 89 declarations, 82,547 chars of tools JSON plus 7,879 chars of system
     * instruction = 90,426 chars, ~22,606 estimated tokens, against ~22,111 before (about 495
     * tokens net: four declarations in, `open_navigation` out). Kevin ruled four wide tools
     * (ticket 04) precisely because Live re-bills every declaration every turn, so the surface
     * is already the narrow option; each description was cut to the sentences that carry an
     * honesty rule (an outcome verb only after a successful result, "several places match means
     * nothing started", "not navigating" is its own answer, mute never mutes the assistant) and
     * `navigate` is the largest at roughly 1,200 chars. Trimming those would weaken the exact
     * clauses CLAUDE.md sec 7 requires, so the ceiling moved. Headroom is ~0.6%, so the next
     * tool addition trips this again, as intended.
     *
     * **Raised to 23,800 on 2026-10-05 (integration of the nine finished branches).** Kevin ruled
     * option A (raise), 2026-10-05, over trimming descriptions or making tools context-loaded. Measured
     * on the merged tree: 91 declarations, 84,069 chars of tools JSON plus 7,879 chars of system
     * instruction = 91,948 chars, ~22,987 estimated tokens for Alfred (this test's subject). The
     * other two built-in companions that carry a longer persona clause, same tools plus Marcus's
     * `consult_meditations` for him (`MarcusPayloadTest`, which asserts the same ceiling): Kratos
     * ~23,320, Marcus ~23,629. Headroom for the worst of the three is ~171 tokens (~0.7%), so
     * the next tool addition trips this again, as intended.
     *
     * **Raised to 24,000 on 2026-10-09 (places by address, rename_place, the data-access clause) - Kevin's ruling.**
     * `rename_place` is a new declaration and `tag_place`/`forget_place` gained `address` and
     * `confirmed`; all three descriptions were cut to the minimum (the confirm and pick-one protocol
     * rides in the tool result instead). Measured: Alfred ~23,179, Kratos ~23,512, Marcus ~23,892.
     * Marcus was ~37 tokens under the old ceiling before this change, so no rename tool fits without
     * either this raise or trimming other tools. Kevin chose raising over trimming tool descriptions.
     *
     * **Raised to 24,250 on 2026-10-19 (books ticket 02, the `books` voice tool) - Kevin's ruling,
     * recorded in `memory/library/decisions.md` (books engine entry).** The brief for the Books
     * launcher requires a voice tool, and ADR 0035 requires it. It is ONE action-dispatched
     * declaration (573 chars of JSON, ~143 tokens), its description already cut to three clauses.
     * Measured: Alfred ~23,385, Kratos ~23,719, Marcus ~24,099, so Marcus was ~61 tokens under the
     * old ceiling before it and ~99 over after. Headroom is now ~150 tokens for the worst persona.
     *
     * **2026-10-27 (Kevin): Alfred is the only persona.** Marcus and `consult_meditations` are
     * deleted, `MarcusPayloadTest` with them, and Dorothy, Kratos and `switch_companion` with
     * them. Every figure above for another persona is history; this test's subject was always
     * Alfred, and the ceiling is left where it is.
     *
     * **NOT raised on 2026-11-03 (voice-browser ticket 01, the `browser` tool, ADR 0071).** Measured
     * on `feat/voice-browser` merged with dev that day: 93 declarations, 86,086 chars of tools JSON
     * plus 9,774 chars of system instruction = 95,860 chars, ~23,965 estimated tokens. `browser` is
     * ONE action-dispatched declaration (seven actions) at 1,135 chars, ~283 tokens; its description
     * keeps only the outcome-verb rule, the password refusal, the ask-first instruction and the
     * "answer in words goes to ask_engine" routing. Headroom is now ~285 tokens.
     *
     * **Raised to 24,500 on 2026-11-03 (Kevin: "lets switch it on", claude-session merged into dev
     * with ticket 09's HTML delivery).** Measured on `integrate/claude-session-on`: 94
     * declarations, 87,241 chars of tools JSON plus 10,388 chars of system instruction = 97,629
     * chars, ~24,407 estimated tokens, against ~23,965 before (~442 tokens). `claude_session` is ONE
     * action-dispatched declaration (eight actions) at 1,155 chars, ~289 tokens, the trade `browser`
     * and `books` made; `CLAUDE_SESSION_CLAUSE` adds 614 chars, ~153 tokens, to the shared
     * instructions. Every part of that clause is a send gate or an honesty rule: named-or-offered
     * (ruling 2), the one offer and the very-next-words yes (ruling 3), "queued, not done", a file
     * never sent unasked (ruling 8), and the page that opens only on a yes (ticket 09). Trimming any
     * of them to fit would weaken the rule that stops Alfred sending work to a laptop unasked.
     * Headroom is now ~93 tokens: the next tool needs a trim or its own justification here.
     *
     * **NOT raised on 2026-11-05 (`ENGLISH_CLAUSE`, reply in English).** Measured on
     * `fix/phone-daily-bugs` before the clause: dev had already grown to within ~27 tokens of the
     * ceiling. A 133-char first wording measured 94 declarations, 87,505 chars of tools JSON plus
     * 10,521 chars of system instruction = 98,026 chars, ~24,506 estimated tokens, 6 over; it was cut
     * to 99 chars (~24,498), about 2 tokens of headroom. The long reason rides on the route-result
     * frame instead (`SubAgent.ROUTE_ENGLISH`), which is billed per result, not per setup.
     *
     * **Raised to 24,650 on 2026-11-05 (alfred-ota ticket 06, `app_update`), PENDING KEVIN'S
     * RATIFICATION; the alternative is trimming another description by ~115 tokens.** Measured on
     * `feat/alfred-ota-phone`: 95 declarations, 87,967 chars of tools JSON plus 10,487 chars of
     * system instruction = 98,454 chars, ~24,613 estimated tokens, against ~24,498 before (the
     * Alfred pack, ticket 02, renders the bundled wording byte for byte and added nothing).
     * `app_update` is ONE action-dispatched declaration (check, whats_new, install) at 461 chars,
     * ~115 tokens; its description keeps only the action list, the confirmed-only-after-a-yes rule
     * and "say only what the result says". Map decision 3 (always ask; Kevin's yes by voice or
     * tap) needs a voice tool for the voice yes, and ADR 0035 needs the Updates screen beside it.
     * Headroom is ~37 tokens. The pack's `UNREADABLE_IS_NOT_EMPTY` (~70 tokens) does not fit.
     *
     * **NOT raised on 2026-11-05 (memory-and-vault tickets 09 and 12: `memory`, `show_vault_item`).**
     * Measured on `feat/memory-vault-phone`: 95 declarations, 87,949 chars of tools JSON plus 10,487
     * chars of system instruction = 98,436 chars, ~24,609 estimated tokens, against ~24,613 before.
     * `remember` (312 chars) and `recall_memory` (733) became ONE action-dispatched `memory`
     * (recall, remember, forget, list; 732 chars), the trade `books`/`browser`/`app_update` made;
     * the recall description's "never guess, never refuse" sentence moved onto the empty-recall
     * result (`MemoryToolbox.RECALL_EMPTY_NOTE`), billed per result. `show_vault_item` is 295 chars.
     * Headroom is ~41 tokens.
     *
     * **NOT raised on 2026-11-09 (voice log audit fixes, `fix/audit-2026-11-09-phone`).** Measured
     * on that branch before the change: 94 declarations, 87,323 chars of tools JSON plus 10,487
     * chars of system instruction = 97,810 chars, ~24,452 estimated tokens (dev had shed ~160 since
     * the entry above). After: 87,500 + 10,554 = 98,054 chars, ~24,513 (+61). The lone "¿Qué?"
     * sentence in `ENGLISH_CLAUSE`; "keep 'nearest'" on `navigate.destination`; "'the list' with
     * food means Groceries" on `manage_checklist`; "car status only from this" on `ask_fleet`;
     * `get_current_location` reworded token-neutral. The car-status rule itself
     * (`CAR_STATUS_CLAUSE`) rides in `buildLiveContext`, per conversation. Headroom is ~137 tokens.
     *
     * **NOT raised on 2026-11-09 (alfred-clock ticket 06, `clock`, ADR 0079).** `clock` is ONE
     * action-dispatched declaration (14 actions: alarms, timers, stopwatch, bedtime) at 693 chars,
     * ~173 tokens; its params carry no descriptions. Paid for mostly by `manage_item.exact`, whose
     * "wake me up at exactly 6am" example also routed wake-ups to reminders: cut to one line naming
     * `clock`. Measured on `feat/alfred-clock` merged with `integrate/2026-11-09` (the audit fixes):
     * 95 declarations, 87,981 chars of tools JSON plus 10,554 chars of system instruction = 98,535
     * chars, ~24,633 estimated tokens. Headroom is ~17 tokens.
     *
     * **Lowered usage, ceiling unchanged, 2026-11-09 (`feat/memory-quickwins`).** Measured on
     * dev `8e983a60`: 94 declarations, 87,500 chars of tools JSON plus 10,554 chars of system
     * instruction = 98,054 chars, ~24,513 tokens. After: 87,500 + 10,336 = 97,836 chars, ~24,459
     * (-54). `SHARED_RECORD_RULES` named the retired `remember` and `recall_memory` tools; its
     * memory sentence is now "Never claim to remember something without checking the memory tool
     * first." The `memory` tool's own description says when to recall and save. Headroom ~191 tokens.
     *
     * **Both together, ceiling unchanged, 2026-11-09 (`integrate/2026-11-09-r2`).** Clock plus
     * memory-quickwins plus phone-log-upload on dev `8e983a60`: 95 declarations, 87,979 chars of
     * tools JSON plus 10,336 chars of system instruction = 98,315 chars, ~24,578 tokens. Headroom is
     * ~72 tokens.
     * **NOT raised on 2026-11-09 (files to phone, ADR 0080, `feat/claude-files-to-phone`).** No new
     * declaration: `claude_session` gains one `files` boolean ("True if he asked for files on his
     * phone."), paid partly by "send_file only on his yes to a found file" (was "only after he says
     * yes to having a file on his phone"); `CLAUDE_SESSION_CLAUSE`'s found-file sentence now carves
     * out `files true`. Measured on dev `2779ef88` plus the branch: 95 declarations, 88,042 chars of
     * tools JSON plus 10,356 chars of system instruction = 98,398 chars, ~24,599 tokens (+21).
     * Headroom is ~51 tokens.
     * **NOT raised on 2026-11-10 (ADR 0082, `feat/proactive-voice`).** No new declaration:
     * `why_did_you_say_that` gains one `stop` string ("stop the morning brief") and its description
     * was tightened to pay for most of it (+33 chars of JSON). Measured on dev `ea6e968b` plus the
     * branch: 95 declarations, 88,075 chars of tools JSON plus 10,356 chars of system instruction =
     * 98,431 chars, ~24,607 tokens. Headroom is ~43 tokens.
     *
     * **NOT raised on 2026-11-10 (standing watches, ADR 0083, `feat/watches`).** `watch` is ONE
     * action-dispatched declaration (list, set, pause, resume, remove; params action, kind, dollars,
     * text; the id rides in `text`), 489 chars. Paid for by trimming `get_current_time` (530 to 299
     * chars: the routing "whenever the answer depends on the time or date" and the never-guess rule
     * kept, the examples and "Instant, offline, free" cut) and `why_did_you_say_that` (527 to 449:
     * its empty-result sentence was already the result's own words). Measured on dev `ea6e968b` plus
     * the branch: 96 declarations, 88,223 chars of tools JSON plus 10,356 chars of system
     * instruction = 98,579 chars, ~24,644 tokens (+45 over dev's 98,398 / ~24,599). Headroom ~6
     * tokens (24 chars): the next tool needs a trim of its own.
     *
     * **NOT raised on 2026-11-10 (`look`, "look at this", ADR 0084, `feat/alfred-look`).** Measured
     * on dev `ea6e968b` before: 95 declarations, 88,042 + 10,356 = 98,398 chars, ~24,599 tokens.
     * After: 96 declarations, 88,175 + 10,356 = 98,531 chars, ~24,632 (+133 chars, +33 tokens).
     * `look` takes no parameters and a 39-char description; its trust rules ride on the tool RESULT
     * and the button's opener (`LookRules.RULES`), billed per look, not per setup. Headroom ~18.
     *
     * **All three together, NOT raised, 2026-11-10 (`integrate/2026-11-10-jarvis`).** Each branch
     * measured itself against dev alone. Merged (with `why_did_you_say_that` taking proactive-voice's
     * wording minus the empty-result sentence watches cut, plus `stop`): 97 declarations, 88,404
     * chars of tools JSON plus 10,356 chars of system instruction = 98,760 chars, ~24,690 tokens,
     * 40 over. Trimmed text with no steering value, every caveat kept: `play_music` lost its
     * "if Spotify isn't connected, this fails with a message telling the user to connect it in
     * Setup..." sentence (the failure RESULT says exactly that, billed per result), description
     * 1,679 to 1,540 chars; `control_music` lost "hands-free", "(Spotify's own behaviour)" and its
     * playlist example (the `playlistName` param keeps one), 2,613 to 2,527. After: 97
     * declarations, 88,179 + 10,356 = 98,535 chars, ~24,633 tokens. Headroom ~17 tokens. Priced
     * for Kevin in the integration report: the setup text is re-billed on every turn of every
     * connect, so a raise is a per-turn cost, not a one-off.
     *
     * **NOT raised on 2026-11-10 (round 5, `feat/field-nav`).** No new declaration: the offline field
     * readout (heading, true north, grid, sun, moon, turnaround, bearing to saved places) rides in
     * `get_current_location`'s RESULT (`FieldToolbox`), billed per call; its description gained one
     * clause naming it. Measured on dev `ea6e968b` plus the branch: 95 declarations, 88,110 chars of
     * tools JSON plus 10,356 chars of system instruction = 98,466 chars, ~24,616 tokens (+17).
     * Headroom is ~34 tokens.
     *
     * **NOT raised on 2026-11-10 (round 5, `feat/conditions`).** No new declaration: rivers, alerts,
     * storms, roads and fire are the engine's `check_conditions`, reached through `ask_engine`, whose
     * description gained "rivers, floods, storms, road closures"; the phone's GPS fix rides the
     * sub-agent's instruction per call, not the setup. Measured on dev `ea6e968b` plus the branch:
     * 95 declarations, 88,081 chars of tools JSON plus 10,356 chars of system instruction = 98,437
     * chars, ~24,609 tokens (+10). Headroom is ~41 tokens.
     *
     * **NOT raised on 2026-11-10 (round 5, `feat/github-status`).** No new declaration: GitHub status
     * is the engine's `github_status`, reached through `ask_engine`, whose description gained
     * "GitHub builds and PRs". Measured on dev `ea6e968b` plus the branch: 95 declarations, 88,065
     * chars of tools JSON plus 10,356 chars of system instruction = 98,421 chars, ~24,605 tokens
     * (+6). Headroom is ~45 tokens. With round 5's field-nav (+68 chars) and conditions (+39) on top:
     * 98,528 chars, ~24,632 tokens, headroom ~18 (computed from the three measurements).
     *
     * **Jarvis on round 5, NOT raised, 2026-11-10 (`integrate/2026-11-10-jarvis-on-r5`).** Each
     * side was ~17-18 under on its own. Merged: 97 declarations, 88,309 chars of tools JSON plus
     * 10,356 chars of system instruction = 98,665 chars, ~24,666 tokens, 16 over. Trimmed text with
     * no steering value: `flight_status` lost "`read_calendar` no longer sees anything pushed onto
     * the user's real Google Calendar by an airline after the calendar was disconnected from Google
     * - it only knows what is stored locally." (history; the next sentence already routes: only
     * call this when read_calendar has nothing), 1,014 to 828 chars; `end_conversation` lost "and
     * ending the conversation there would be infuriating" (the reason, not the rule), 1,158 to
     * 1,103. After: 97 declarations, 88,068 + 10,356 = 98,424 chars, ~24,606
     * tokens. Headroom ~44 tokens.
     */
    private val ceilingTokens = 24_650

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
