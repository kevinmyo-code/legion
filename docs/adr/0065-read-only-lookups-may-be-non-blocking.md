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
answers when the result arrives. The set is `LiveToolbox.NON_BLOCKING_READ_TOOLS`, today `ask_fleet`,
`ask_body`, `ask_goals` and `ask_pantry`. **A write is a user-visible record change the assistant
could claim as an outcome** (Kevin, 2026-10-28); internal bookkeeping is not one. Its result goes back with `scheduling: WHEN_IDLE`. A setting, "Answer lookups
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
read may be in the set: a test proves it, and every tool behind it, is disjoint from
`isMutatingTool`, from the read-through tools, and from `ask_engine`, `aspect_clerk` and `ask_mail`.
Kevin ruled on 2026-10-28 what "write" means here: a user-visible record change the assistant could
claim as an outcome. `lookup_vin`'s spec-cache refresh (behind `ask_fleet`) and `ask_advisor`'s
advice-log row (behind `ask_goals`) are bookkeeping, so both dispatchers are reads. The result goes back `WHEN_IDLE`, never `INTERRUPT` (it would cut the holding line) and
never `SILENT` (a lookup the user asked for must be spoken). The prompt's `LOOKUP_HOLD_CLAUSE`, at
file scope beside `CANNOT_CLAUSE`, allows one holding line with no figure, date, name, fact or
outcome, then nothing until the result. Source: `.scratch/async-lookups/issues/01-*.md`.

## Consequences

- **Bookkeeping may run under a holding line.** A spec-cache refresh or an advice-log row can land
  while the model is still talking; neither is something the user asked to have recorded, so
  neither is an outcome to claim. An advisor proposal is still applied only by `accept_proposal`,
  which blocks.
- **`lookup_vin` is not in `MUTATING_TOOLS`.** If it ever joins that set, the dispatcher test fails
  and points here: decide which ruling changed rather than silently dropping `ask_fleet`.
- **Unverified on the model.** Google's Live tools page does not list 3.8 as supporting
  asynchronous calls. If a `WHEN_IDLE` result is not spoken unprompted, the default flips to off
  (one line, `VoiceTurnSettings.ASYNC_LOOKUPS_DEFAULT`). Ticket 04 is that test.
- **The follow-up window waits for the lookup.** A lookup still running when the 8 s window
  ([ADR 0064](0064-phone-turn-taking-local-end-of-turn-and-barge-in.md)) expires holds the
  conversation open, and its result re-arms the window, so an answer never spoken still ends it. Since ticket 07 the window holds while an answer is owed (a sent result or a bare holding line), at most 20 s, so a holding line spoken after the result cannot end the conversation before the answer.
- **A cancelled lookup is dropped.** Barge-in cancels it like any call; nothing was written, so
  there is nothing to report.
- **The prompt rule is the only lever.** As with ADR 0031, a test guards the clause's presence,
  never its obedience.
