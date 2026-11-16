---
status: accepted
decided: 2026-11-16
decided-by: Kevin
supersedes: []
source: "[[decisions#2026-11-16 - Today's agenda rides in the voice turn: a bounded exception to pull-based tools (Kevin)]]"
tags: [adr]
---

# 93. Today's agenda rides in the voice turn: a bounded exception to pull-based tools

## Standing

ACCEPTED, BUILT (stage 5, 2026-11-16), NARROWED the same day by the agenda gate, then WIDENED again
on `feat/agenda-block-overdue` (same day, below; not yet gated on the model, merged or verified on a
phone). **The phone's voice cascade puts today's agenda into his turns: today's calendar events,
open work due today and open overdue work, read from the phone's own data by the same function
`day read_calendar` uses for today. A question about today (what he has today, what is due, what
is overdue, what is on his calendar) may be answered from it with no tool call; a plain "what's
due" with no day named means today plus overdue.** This is the one
standing exception to CLAUDE.md section 7's "Pull-based tools always". Everything else in that rule
still holds, and no other domain may cite this ADR to pre-inject its own data.

- **What is injected** (`service/cascade/AgendaBlock.kt`): a `TODAY'S AGENDA` block built from
  `calendar/CalendarWindowRead.kt`'s read of today (local midnight to midnight), the one read the
  tool `read_calendar` also makes (`service/LiveToolbox.kt`), so the block is what the tool would
  return for today, with its read time. Phone data only (Room: calendar events and tasks, dated
  to-dos); no network. Three parts:
  - today's calendar events (`kind = event`; one already over is marked so; tasks are under due);
  - open work due later today, in `DueList`'s own sentence ("N open and due, not done, not overdue
    yet: ...");
  - open overdue work, in `DueList`'s own sentence ("N OVERDUE (past due, not done): ..."). Work
    due earlier today that has passed is overdue, as in the tool. The look back is the tool's
    (`DueList.TASK_OVERDUE_LOOKBACK_DAYS` for calendar tasks, none for to-dos).

  Each part is stated as facts, as EMPTY (read, nothing on file) or as COULD NOT BE READ; one due
  source unread (calendar tasks or to-dos) makes the due parts PARTLY READ with the tool's "may be
  missing items: say so". An unread source is never a clear one. Done work today is counted, not
  listed. The block says weather is not in it and names the tool for it.
- **Size cap:** overdue and due today are capped separately at 8 listed each (`MAX_DUE_LISTED`),
  events at 12 (`MAX_EVENTS`). Past a cap the true count is still stated and the list ends "and N
  more not listed here (ask day read_calendar for the full list)"; nothing is cut silently. With 30
  overdue rows the block lists the 8 oldest (the tool's order) and says "and 22 more". The caps are
  separate so 30 overdue rows never hide what is due today. The tool itself is never capped.
- **Where:** in the first user turn, after the per-conversation preamble
  (`CascadePrompt.withAgenda`), in his turns and their speculative drafts only. Never in the system
  instruction or the tools, so the explicit context cache prefix (ADR 0092) is unchanged and
  `LiveSetupPayloadSizeTest`'s 10,500-token ceiling does not count it. Not in the greeting, an
  opener (Brief me, proactive lines and check-ins all reach the cascade as a `CascadeOpener`) or a
  test conversation.
- **When it is read:** at conversation open, inside `preambleReady` beside the live context, on the
  IO dispatcher, bounded at 1 s (`AGENDA_WAIT_MS`; a Room read, expected well under 100 ms). The
  greeting never waits for it (`awaitPrompt(liveContextWaitMs = 0)`); his first turn waits for
  `preambleReady` up to its existing 2 s, where the live context is normally the longer half. A
  read that times out or fails gives the "not in this conversation's context" line instead.
- **Staleness:**
  - The block names the time it was read and the model may say "as of" that time.
  - Checked at every turn (`AgendaBlock.current`): a read more than 30 minutes old, or from another
    day, is withdrawn, and the turn says so and names the tool.
  - The first write in the conversation (any action tool call that changes data, success or not)
    **withdraws** the block for every later turn: the turn says the copy is out of date and to
    call `day read_calendar`. The write's own tool result carries the same note for the rest of
    that turn. It is withdrawn, not re-read, so a turn never pays for a read it may not need.
- **What still goes through tools:** every change (complete, add, move, delete); any other day or
  range (tomorrow, this week); work due after today; the full list behind an "and N more" line;
  anything not listed; weather. The block tells the model so.

## Context

The 2026-11-16 voice latency plan measured the cloud voice at about 4.9 s from his last word to the
first sound on a tool turn. 6 of the 12 measured cloud tool turns were `day` calls, and each paid a
model round (about 1,017 ms median to the function call) plus the tool (about 80 ms) before the
answering round. Answering those from context drops both: about 1 s per agenda question (about
4.9 s to about 3.8 s, arithmetic from the measured medians, not yet measured on a phone). After the
gate below it applied to calendar-only questions only; the widening below brings it back to today,
due and overdue questions, pending its own gate. The cost is the block in the uncached half of each
request, estimated at about $0.0003 per round.

## Decision

Kevin approved it on 2026-11-16 knowing it skips the lookup step ("yes i agree", to "today's due
items and calendar kept in the prompt, skipping the lookup step for common questions"). It is
bounded to today's read above, from data already on the phone, stated honestly per part, capped
with a stated count, withdrawn on the first write or after 30 minutes.

## The agenda gate (2026-11-16, before ship)

The block's first wording told the model to answer "what he has on today, or what is due" from it
without a tool call. A gate on the real model (flash-lite, invented household, the block in the
first user turn as the phone builds it, an overdue item that only the `day` tool returns) ran 27
times: three packs (both sources empty; one event and one item due today; the due source could not
be read) by three questions ("what do I have today", "what's due", "anything overdue?").

- With items in the block, "what do I have today" was answered in one round from the block alone
  and dropped the overdue item: 3 of 3 runs with an event and a due item, 1 of 3 with the due source
  unread (that answer also left the unread source unmentioned).
- "What's due" and "anything overdue?" called the tool every time, so the block saved no round there.
- No answer said nothing was due, and none called the unread source clear.

So the shipped block (d14f903f) said overdue work was not in it and that today, due, to-do and work
questions read the tool first; only calendar-only questions skipped the lookup.

## Widened: the block is the tool's own read of today (2026-11-16, `feat/agenda-block-overdue`)

The gap was the source, not the wording: the greeting pack's `due_soon` drops an item once its due
time passes, while `day read_calendar` reads the phone's own rows and always includes overdue work.
So the block is now built from the same read the tool makes for today
(`CalendarWindowRead.today`), not from the pack, and its wording allows today, due and overdue
questions again.

| | Shipped (pack) | Now (phone's own read) |
|---|---|---|
| Source | greeting pack `today_events` + `due_soon` (engine, cached) | `CalendarWindowRead`, the tool's read for today (Room) |
| Overdue work | not in it | listed, capped at 8 with "and N more" |
| Due window | 24 h after the pack was read | today (later work goes to the tool) |
| Events | today's not yet ended | all of today's, ended ones marked |
| Answered without a tool | calendar-only questions | today, due, overdue and calendar questions for today |
| Open path | waited up to 1.5 s for a pack fetch | local read bounded at 1 s, no fetch |
| 30-min / other-day limit | checked at open only | checked at every turn |

Gate before it ships: the stage 5 agenda gate rerun with this block, the three variants as phone
data (no events and nothing due today but one overdue item; an event, two due today and an overdue
item; the same with to-dos unread) plus one with 30 overdue rows, five questions ("what do I have
today", "what's due", "anything overdue?", "what's on my calendar today", "what's due tomorrow?"),
3 runs each. Pass: every today, due and overdue answer names the overdue work or says how many
more; none says nothing is due when something is; an unread source is never called clear;
"tomorrow" calls the tool. Not yet run.

## Consequences

- **Revisited when on-phone speech-to-text lands** (the plan's item E). Speech-to-text is the
  largest part of the turn today; once it is on the phone, the tool round may be cheap enough
  relative to the whole turn that the pull rule can come back for this case too.
- The model may answer a wider question ("this week", "tomorrow") from today's list. The block
  forbids it; the gate's "what's due tomorrow?" checks it, and only a phone run shows the rest.
- The block is as fresh as the phone's replica: a change synced from the engine or the web after
  the read is not in it until the next conversation (or 30 minutes). The tool has the same limit
  within a turn.
- Weather is not in it; adding it is an engine change and a separate decision.
- CLAUDE.md section 7 points here; the build checklist's "pull-based tool" item still applies to
  every other domain.
