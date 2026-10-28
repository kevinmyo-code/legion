---
status: accepted
decided: 2026-10-28
decided-by: Kevin
source: "[[decisions#2026-10-28 - Voice: read-only lookups may run non-blocking; writes stay blocking (Kevin)]]"
tags: [adr]
---

# 65. Read-only lookups may run non-blocking; writes stay blocking

## Standing

**On the phone's Live socket, a write tool always blocks, and a named read-only lookup may run
`NON_BLOCKING`**: the model says one short holding line, keeps the floor while the lookup runs, and
answers when the result arrives. The set is `LiveToolbox.NON_BLOCKING_READ_TOOLS`, today `ask_body`
and `ask_pantry`. Its result goes back with `scheduling: WHEN_IDLE`. A setting, "Answer lookups
while talking" (Settings > Assistant, default on), empties the set; off means every declaration
blocks, as before. Typed chat and the head unit are untouched.

## Context

`gemini-3.8-live` made non-blocking function calls the default, and on 2026-09-27 every declaration
was stamped `BLOCKING` because the model could say "done" before a result existed, which makes
[ADR 0031](0031-speech-honesty-clause.md)'s outcome rule unobeyable. That reasoning is about
outcomes. A read has none to misreport; its risk is a fact spoken before the data exists. Slow
lookups (a sub-agent POST plus tools) left dead air that blocking could not fill.

## Decision

`GeminiLiveSession.withToolBehavior` stamps `NON_BLOCKING` on a name in the set and `BLOCKING` on
everything else, at the one choke point, so a new tool blocks unless someone opts it in. Only a
read may be in the set: a test proves it is disjoint from `isMutatingTool`, from the writes found
behind dispatchers, from the read-through tools, and from `ask_engine`, `aspect_clerk` and
`ask_mail`. The result goes back `WHEN_IDLE`, never `INTERRUPT` (it would cut the holding line) and
never `SILENT` (a lookup the user asked for must be spoken). The prompt's `LOOKUP_HOLD_CLAUSE`, at
file scope beside `CANNOT_CLAUSE`, allows one holding line with no figure, date, name, fact or
outcome, then nothing until the result. Source: `.scratch/async-lookups/issues/01-*.md`.

## Consequences

- **A dispatcher is a write if anything behind it writes.** `ask_fleet` is out because `lookup_vin`
  upserts the car's spec row; `ask_goals` is out because `ask_advisor` logs every answer. So the
  trouble-code questions this was charted for still block. Moving `lookup_vin` out from behind
  `ask_fleet` is what would let it in.
- **Unverified on the model.** Google's Live tools page does not list 3.8 as supporting
  asynchronous calls. If a `WHEN_IDLE` result is not spoken unprompted, the default flips to off
  (one line, `VoiceTurnSettings.ASYNC_LOOKUPS_DEFAULT`). Ticket 04 is that test.
- **The follow-up window waits for the lookup.** A lookup still running when the 8 s window
  ([ADR 0064](0064-phone-turn-taking-local-end-of-turn-and-barge-in.md)) expires holds the
  conversation open, and its result re-arms the window, so an answer never spoken still ends it.
- **A cancelled lookup is dropped.** Barge-in cancels it like any call; nothing was written, so
  there is nothing to report.
- **The prompt rule is the only lever.** As with ADR 0031, a test guards the clause's presence,
  never its obedience.
