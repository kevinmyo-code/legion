---
map: hands-and-senses
ticket: 23
title: "An audit trail of every conversation and every tool call"
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# An audit trail of every conversation and every tool call

## Why

Kevin, 2026-08-21: *"i want a way to record all the voice conversations i have with the ai as a log
+ all the tool calls etc so we have an audit trail and debug."*

This is the missing half of a defect this map already named.
[Ticket 20](20-it-said-142k.md) - the assistant claiming the Jeep was at 142k when the record said
227k - could not be closed for one reason, in its own words: **"nothing records what the assistant
SAID."** `memory_audit` (Room v27) fixed half of that and proved its worth tonight, when it showed
the mileage spoken correctly, with its estimate caveat intact.

What it still cannot answer is the question that ticket actually poses: **did the model skip the
tool, or get the right answer and say a different one?** `MidnightEvents` records that `ask_fleet`
was dispatched, and nothing about what was asked of it or what came back.

## Decided - 2026-08-21 (Kevin, 4 calls)

### 1. A turn's record holds: both sides of the conversation, every tool call WITH ARGUMENTS, every tool RESULT

The third is the one that matters most and is the one currently missing everywhere. Without results,
*"the tool returned the right number and the model ignored it"* is indistinguishable from *"the tool
returned nothing"* - and those are different bugs with different fixes.

Timing was offered and not taken. Worth revisiting only if a hang recurs.

### 2. Read-through content is REDACTED, not stored

**This was the sharp one, because a full transcript directly contradicts a rule made hours earlier.**
Mail bodies and the sitrep's news summary must not be stored - it is why `get_sitrep` sits in
`LiveToolbox.EPISODIC_EXCLUDED_TOOLS` and why a scheduled sitrep carries
`ProactiveRaise.carriesReadThroughContent`.

So a turn flagged read-through records **that it happened** - the tool, the arguments, the fact a
result came back - and stores `"[read-through content omitted]"` in place of the words.

**The debugging value survives the redaction**, which is what makes this the right call rather than
a compromise: the failure being chased is *the model asserting something its tools did not return*,
and that is visible from the tool NAMES and the assistant's own words. It never required the mail
text itself.

**Reuse the existing flag. Do not invent a second notion of read-through** - two flags meaning "do
not store this" is how one of them ends up checked in half the places.

### 3. A Room table, exportable to Kevin's Drive folder on demand

Queryable on-device, so the app can answer "why did you say that" from it later. Exportable so an
investigation does not require a laptop, a cable, and Kevin present - which is exactly what reading
`memory_audit` cost tonight.

Same BYO posture as everything else: his own Drive, nothing Kevin-hosted (CLAUDE.md §7).

### 4. A rolling retention window, oldest dropped

Debugging needs recent history, not all history. Bounded growth on a daily-use assistant.

**Keep-only-interesting-turns was explicitly rejected**, and the reason is the whole point of this
ticket: **the 142k turn looked like an ordinary successful turn.** No error, no refusal, no tool
failure. A filter tuned to interesting-looking turns would have dropped the single most important
record in the app's history.

The exact window (14 or 30 days) is a build-time call.

## Build notes

- `memory_audit` already exists and already stores spoken lines. **Decide early whether this extends
  that table or sits beside it** - two overlapping audit stores would be worse than either.
- The tool-call seam is `LiveToolbox.dispatch` and `GeminiLiveSession.handleToolCall`; the
  read-through flag is already computed there for the episodic gate
  (`isEpisodicExcludedTool` / `readThroughToolTouchedThisTurn`).
- Room change: verbatim generated SQL, additive, `exportSchema`, per CLAUDE.md §5. Diff the
  migration against the generated `createSql` - that check has caught a real mistake twice this week.

## Verification

- Suite green, with the redaction path tested directly: a turn that touched `ask_mail` or
  `get_sitrep` must contain the tool name and **must not** contain its content.
- On the phone: hold a real conversation, pull the export, and confirm both sides plus arguments and
  results are readable.
- **The real test is a recurrence.** Next time the assistant says something wrong, this either
  explains it or it does not - and if it does not, the gap it leaves is the next ticket.
