---
map: async-lookups
ticket: "07"
title: "Build: ticket 04 run 3 follow-ups (still-checking nudge, dead holding lines, sign-off, writes, figures, meal dates)"
type: build
status: built
status-detail: "Built 2026-10-28 on feat/async-read-tools, 10 commits. Still-checking nudge at 4 s and 12 s (Kevin); dead-holding-line nudges and a 20 s answer hold on the follow-up window; sign-off, write-on-request and time-varying-figure prompt rules; meal dates; voice_audit.py fixes. 5,489 tests, 0 failures; detekt the 4 pre-existing; assembleFast builds. Owed: a phone run read through voice_audit.py --summary (nudges and what followed them)."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# Build: ticket 04 run 3 follow-ups

Ticket 04's third on-device run (A25, `gemini-3.8-live`, 2026-10-28 11:24-11:31, Kevin's real
conversation, read through `tools/voice_audit.py`) and the coordinator's run 3c buffer (11:47).
The late-result fix and the fleet router fix both held: 6 of 8 async lookups were answered from the
`WHEN_IDLE` result in the model's own next turn, and "any trouble codes on the Jeep" reached
`get_code_history` twice. What was still wrong, and what was built. Phone only, branch
`feat/async-read-tools`.

## Findings

| | When | What happened |
|---|---|---|
| F1 | 11:25:16 | `ask_fleet` result 0.7 s after a silent tool-call turn; the next turn said only "Checking." The answer never came, and the 8 s follow-up window closed the conversation 8.1 s later |
| F2 | 11:27:01 | Result at 04.5; the user said "what?" at 06.5 before the model spoke; the model said "Let me look." and nothing more. Window closed 8.1 s later |
| F3 | 11:27:44 | Two-call `ask_fleet` chain, `investigate diagnose_codes 7974ms`; no holding line at all, 13.8 s from end of speech to first audio |
| F4 | 11:31:02 | Sign-off "Very good, Sir.", the stock line the register forbids |
| F5 | 11:29:35 | A trip mentioned as a plan was put on the calendar unasked; the user then said it was a rough plan |
| F6 | 11:30:00 | Weather at a park in November: "Let me look.Checking.", no tool the phone can see, then climate figures |
| F7 | 11:25:57 | `list_recent_meals` rows had no date, so "what did I eat yesterday" could not be answered |
| F8 | | `voice_audit.py` crashed on cp1252 (U+2691); `--summary` counted "Checking the" / "records." as two holding lines |
| 3c | 11:47:13 | A general research question; "Checking." with NO tool call and nothing pending; window closed 8.1 s later |

Result-to-first-audio after a `WHEN_IDLE` result measured 0.8 s, 1.8 s and 1.9 s across run 3, so a
user who speaks in that gap is speaking over a pending answer (F2).

**Mail, run 3c:** `ask_mail` (blocking) answered 5 of 5, at 1,440, 1,493, 2,513, 2,623 and 2,360 ms,
every result and every assistant line after it `[redacted]` in the audit log.

## What was built

1. **Still-checking nudge (F3, Kevin: *"if checks are longer than x seconds, send another 'still
   checking' etc msg"*).** `service/turn/StillCheckingNudge.kt`. A NON_BLOCKING lookup running
   `STILL_CHECKING_AFTER_MS = 4_000`, with nobody speaking and no reply being generated, gets one
   system note; at most once more at `STILL_CHECKING_AGAIN_AFTER_MS = 12_000`. The clock is each
   call's own start (F3's 9 s run was the second call of a chain). Never after the result, never
   while a BLOCKING tool is in flight, never while another lookup's sent result is still waiting
   for its answer (the model is generating it, 0.8-1.9 s before first audio, and a note would
   interrupt it), never for a call the user has spoken over; cleared on
   cancellation, barge-in, speech onset, park and session end. No two nudges closer than 4 s.
   On whenever async lookups are on (only NON_BLOCKING calls are registered). Logs
   `LIVE_AUDIT nudge reason=still_checking tool=<name> after_ms=<n>` and
   `LIVE_LATENCY nudge_ms=<n> tool=<name>`.
2. **Dead holding lines (F1, F2, 3c).** `LOOKUP_HOLD_CLAUSE` gained three sentences (below).
   `service/turn/HoldingLineWatch.kt` is the guarded code fallback. A holding line is at most 5
   words, no digit, and contains "check", "look", "moment" or "second".
   - After a result: the first turn that STARTS after the result was sent, if it is a holding line
     with no new tool behind it, gets one note (`nudge reason=late_holding_line`). Once per result.
     **Deliberately narrower than "the next turn":** at 11:25:57 "Checking the records." began
     0.7 s before its result went back, and its answer was the following turn; a rule keyed on
     "the next turn to complete" would have interrupted that answer mid-generation.
   - No result pending, no tool called in the turn, nothing in flight, and the turn is a holding
     line: one note (`nudge reason=empty_holding_line tool=none`). Once, until a turn with
     substance or a tool call.
   - **The follow-up window holds** (`FollowUpWindow.State.heldForAnswer`) from a sent
     NON_BLOCKING result or a bare holding line until a substantive turn, capped at
     `ANSWER_HOLD_CAP_MS = 20_000` (the close lands at the first 8 s expiry at or after the cap).
     Hold changes log to `LIVE_FOLLOWUP` as `answer hold: held_for_answer | holding_line |
     answered | answer_hold_cap`.
3. **Sign-off (F4).** `end_conversation`'s result text is
   `LiveSessionController.END_CONVERSATION_INSTRUCTION` and names no phrase; the declaration says
   the same; `ALFRED.clause` names "Very good" and "Right away" style stock as out.
4. **Writes only when asked (F5).** `WRITE_ON_REQUEST_CLAUSE` at file scope beside `CANNOT_CLAUSE`.
   No weaker general version existed; the maintenance walkthrough's "user-initiated only" covers
   one flow. Not changed, for Kevin: `manage_vehicle`'s description says "Use 'add' when the user
   mentions another car they own ('add my F-150', 'I also drive a...')", whose second example is a
   mention rather than a request.
5. **Figures only from a result (F6).** No function tool returns a forecast: `area_info` weather is
   NWS severe alerts at the live fix, `get_sitrep` weather is current conditions at the phone
   (Open-Meteo `current=`). `googleSearch` IS declared in the Live setup, but the "use your search
   tool" sentence named news, scores and prices and not weather. Nothing logs `groundingMetadata`,
   so whether a search ran on that turn is unauditable; the figures came from no result the phone
   saw. The search sentence now names "weather and forecasts", and `LOOKUP_HOLD_CLAUSE` ends with
   the time-varying-figures rule.
6. **Meal dates (F7).** `list_recent_meals` rows carry `date` (ISO, the phone's zone, the same
   local day `MealGap` buckets "today" by) and `weekday`. `MealLog.loggedAt` existed; no Room
   change.
7. **`voice_audit.py` (F8).** UTF-8 stdout (guarded `reconfigure`); `--summary` merges chunks split
   only by a timing line; `nudge` events in the timeline and summary (count, and the line each was
   followed by).

## The channel

The nudges are user-role `clientContent` text turns with `turnComplete: true`, framed
`(System note, not spoken by the user: ...)` exactly as `CancelledToolReport.noteFor` already is.
The Live API reference (ai.google.dev/api/live, read 2026-10-28) offers nothing cleaner: system
instructions are setup config and "You cannot update the configuration while the connection is
open"; `realtimeInput.text` is a stream with no turn control. `clientContent` "will interrupt any
current model generation", which is why every note waits for quiet: not speaking, not playing back,
no `activityEnd` awaiting a reply, the user not mid-turn, not a typed turn.

## Prompt text added, verbatim

`LOOKUP_HOLD_CLAUSE`, appended after every existing sentence:

> Say the holding line only in the same breath as the tool call it covers; if you are not calling a
> tool there is nothing to check, so do not say it, and a question you can answer yourself gets the
> answer. If the result is already in front of you when you begin to speak, there is no holding
> line to say: answer. If the user speaks while a result is pending or has just arrived, answer
> their new words first, then give that result in the same turn unless they cancelled it. Weather,
> prices, news, balances and anything else that changes over time come only from a tool or search
> result; with no tool or no result, say you cannot check it, and never give a typical or usual
> figure instead.

`WRITE_ON_REQUEST_CLAUSE`:

> Create or change a record only when the user asks for it. A plan, intention or possibility
> mentioned in passing is not a request: offer once, in a clause, and act only on a yes.

`ALFRED.clause`: *Butler stock like "Very good" or "Right away" is out, as an answer and as a
farewell.* Search sentence: *news, scores, prices, weather and forecasts*.

`LiveSetupPayloadSizeTest`: ~23,466 before this ticket, ~23,713 after (ceiling 24,250).

## Known gaps

- "Give me a second with that." (six words, the prompt's own example) reads as an answer to the
  detector. Pinned as a known gap in `HoldingLineWatchTest`.
- A holding line that began BEFORE its result and is followed by no answer gets the 20 s hold but
  no nudge. Not seen in any run.
- If the model ever splits a `WHEN_IDLE` answer into a bare "Checking." turn and a second turn, the
  late nudge lands while the second is generating and interrupts it. Not seen; the next capture
  should look for `interrupted source=server` right after a `nudge` line.
- In pure server-VAD mode with no `voiceActivity` messages, nothing stops a still-checking nudge
  while the user is mid-sentence (transcripts arrive at utterance end). 3.8-live sends
  `ACTIVITY_START`, which clears the nudge.
- The holding-line watch and its hold are not gated on "Answer lookups while talking": the 11:47
  turn had nothing asynchronous about it, so the empty-holding-line nudge runs with the setting off.
- With captions off there is no transcript, so a turn with speech counts as an answer and no
  holding-line nudge can fire.

## Verification

- Built and green: `compileDebugKotlin -Pnokey`; `testDebugUnitTest` 5,489 tests, 0 failures
  (baseline 5,451); `detekt` the 4 pre-existing findings; `python -m doctest tools/voice_audit.py`;
  `voice_guide.py --check`; `assembleFast` builds (not installed).
- Owed, on the phone: a capture read through `voice_audit.py --summary`. Listen for: a still-checking
  line on any lookup over 4 s; no bare "Checking." turn left unanswered; no conversation closed
  8 s after a holding line; no stock sign-off; no write from a mention; no figure without a result;
  "yesterday" answered from meal dates.
