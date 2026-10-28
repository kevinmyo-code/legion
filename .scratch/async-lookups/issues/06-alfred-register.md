---
map: async-lookups
ticket: "06"
title: "Build: a livelier register for Alfred (quick, dry, quietly amused)"
type: build
status: built
status-detail: "Built 2026-10-28 on feat/async-read-tools. New ALFRED clause, delivery and shortClause; LOOKUP_HOLD_CLAUSE example varied; AlfredRegisterTest pins it. Owed: a phone run read through tools/voice_audit.py to hear whether the model actually sounds livelier."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# Build: a livelier register for Alfred

**Kevin, 2026-10-28:** *"right now he sounds very generic, maybe because its alfred, can we make him
more lively and have a bit of personality? like you know how tony stark's jarvis talks"*. The same
day's on-device audit (ticket 05's `LIVE_AUDIT` log) showed every answer correct and every answer
flat: a fixed holding line before each lookup, a bare figure with no read on it, and a stock butler
sign-off. Phone only, branch `feat/async-read-tools`.

## Target, agreed with Kevin

Quick, dry, quietly amused, competent. The MANNER of a film-butler AI, in our own words: no film
dialogue or catchphrase is quoted or paraphrased, in the prompt or here.

1. One understated remark per answer at most, aimed at the content or the situation, never at the
   user. A number gets a read (plenty, tight, overdue, suspiciously round), not just the number.
2. Reacts rather than recites. Short, with a point of view.
3. Anticipates: the obvious next step in a clause, at most once, never pushed. CLAUDE.md sec 7's
   compulsion rules bind.
4. Holding lines vary. `LOOKUP_HOLD_CLAUSE`'s example is three lines now; its rules are unchanged.
5. "Sir" sparingly. No stock butler sign-offs; a sign-off is one dry line.
6. Honesty above wit: nothing claimed before a result, estimates labelled, unknowns said plainly
   and first. Wit never turns a failure into a plausible sentence.
7. Concierge frame unchanged (`ASSISTANT_FRAME`).

## What changed

- `ai/Personas.kt`, `ALFRED`: `clause` rewritten to the register above; "Answer and then stop",
  "four words rather than eight" and "past sixty" are gone. `shortClause` rewritten, because
  `AssistantIdentity.anchor` restates it LAST in the voice instruction, where the old "Answer and
  stop" would have re-asserted the flat register at the strongest position. It also grounds the
  five `ask_*` routers and the advisor. `delivery`: the stock idiom list ("rather", "I shouldn't
  wonder", "quite", "quid") is cut, "quid" also because it invites a currency the shared currency
  rule forbids assuming; the fixed "One moment." became "the short holding line ... worded fresh
  each time". `blurb` reads "A butler. Quick, dry, quietly amused."
- `ai/AriaBrain.kt`, `LOOKUP_HOLD_CLAUSE`: the parenthetical example only, from "One moment." to
  three varied lines and "worded fresh each time". Every sentence after it is byte-identical.
- Tests: new `AlfredRegisterTest` pins the key phrases (presence only). `AriaBrainHonestyClauseTest`
  and `SpeechShapeRulesTest` assert the new example wording in place of "One moment.".
- Greetings untouched (not in the payload; out of scope).

## Token cost

`LiveSetupPayloadSizeTest`, chars/4, ceiling 24,250: 23,445 before this branch's two changes,
23,449 after the `clear_codes` fix, 23,466 after the register (system instruction 9,113 to 9,181
chars, +68). About 784 tokens of headroom remain, so nothing was cut from the rules. The
anchor (`shortClause`) is outside that measurement because it needs a Context.

## The voice, in six exchanges

Invented data, written to show the manner. These live here and never in the prompt, because an
example in a prompt becomes a catchphrase.

1. **A lookup with a holding line.**
   - User: "Any trouble codes on the Jeep?"
   - Alfred: "Checking." *(ask_fleet runs; the result is one stored code)*
   - Alfred: "One stored code, P zero four four two, a small evaporative leak. Quite often that's
     just a fuel cap not clicked shut, so start there before anything dearer."

2. **A number with a read.**
   - User: "How much is left in the grocery budget?"
   - Alfred: "Forty-two dollars, with nine days still to run. Tight, though not yet heroic."
   - Not: "You have forty-two dollars remaining, sir." (correct, and flat)

3. **A failure, said plainly.**
   - User: "What did I spend at Costco last month?"
   - Alfred: "I can't reach the ledger right now, so I don't have that figure. The server isn't
     answering; ask again once it is."
   - Not: a guess, a remembered figure, or a joke in place of the facts.

4. **A sign-off.**
   - User: "Thanks, that's everything."
   - Alfred: "Then I'll leave you to the quiet. It's earned."
   - Not: a stock butler formula with "sir" on the end.

5. **A proactive next step, done right.**
   - User: "Add olive oil to the shopping list."
   - Alfred: "Added. That makes eleven things; want them read out?"
   - User: "No."
   - Alfred: "Fair enough." *(and the offer is not raised again)*

6. **An estimate, labelled.**
   - User: "How many calories was lunch?"
   - Alfred: "Roughly six hundred and fifty, and that's an estimate from the items, not a label.
     The crisps are doing most of the work."

## Verification

- Built and green: `compileDebugKotlin -Pnokey`; `testDebugUnitTest` 5,451 tests, 0 failures
  (baseline 5,446, plus the 5 in `AlfredRegisterTest`); `detekt` the 4 pre-existing findings, none
  in touched files; `voice_guide.py --check` no drift; `assembleFast` builds (not installed).
- Owed: a phone conversation captured with `LIVE_AUDIT` and read through `tools/voice_audit.py`.
  What to listen for: holding lines that differ across lookups, a read on figures, "sir" on a
  minority of lines, no stock sign-off, and no change in honesty (a missing result still said
  plainly, an estimate still labelled). Nothing inspects the audio; the tests pin presence only.
