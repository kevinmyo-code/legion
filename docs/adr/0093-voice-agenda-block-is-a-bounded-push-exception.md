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

ACCEPTED, BUILT (stage 5, 2026-11-16), not yet verified on a phone. **The phone's voice cascade
puts today's remaining calendar events and the work due in the next 24 hours into his turns, so
"what do I have today" and "what's due" are answered in one model round with no tool call.** This is
the one standing exception to CLAUDE.md section 7's "Pull-based tools always". Everything else in
that rule still holds, and no other domain may cite this ADR to pre-inject its own data.

- **What is injected** (`service/cascade/AgendaBlock.kt`): a `TODAY'S AGENDA` block built from the
  greeting facts pack the phone already keeps (`greeting/GreetingPackCache.kt`, ADR 0091). Two
  sources only: `today_events` (today's events not yet ended) and `due_soon` (to-dos and coursework
  due in the 24 hours after the pack was read). Each is stated as facts, as EMPTY (read, nothing on
  file) or as COULD NOT BE READ, in the greeting block's own words, so an unread source is never a
  clear one. The block says weather is not in it and names the tool for it.
- **Where:** in the first user turn, after the per-conversation preamble
  (`CascadePrompt.withAgenda`), in his turns and their speculative drafts only. Never in the system
  instruction or the tools, so the explicit context cache prefix (ADR 0092) is unchanged and
  `LiveSetupPayloadSizeTest`'s 10,500-token ceiling does not count it. Not in the greeting, an
  opener (Brief me, proactive lines) or a test conversation.
- **Staleness:**
  - The block names the time the pack was read and the model may say "as of" that time.
  - A pack older than 30 minutes, or from another day, is not used. The block then says the agenda
    is not in context and that this says nothing about his day.
  - At conversation open, a fetch already running is waited for at most 1.5 s, beside the live
    context.
  - The first write in the conversation (any action tool call that changes data, success or not)
    **withdraws** the block for every later turn: the turn says the copy is out of date and to
    call `day read_calendar`. The write's own tool result carries the same note for the rest of
    that turn. It is withdrawn, not refreshed: a refresh inside 30 s returns the same pack, and an
    engine fetch (1.6 to 2.0 s) is longer than the gap to his next turn.
- **What still goes through tools:** every change (complete, add, move, delete); any other day or
  range; overdue work (the pack drops an item once its due time passes); events that already
  ended; anything not listed; weather. The block itself tells the model to call `day
  read_calendar` for these.

## Context

The 2026-11-16 voice latency plan measured the cloud voice at about 4.9 s from his last word to the
first sound on a tool turn. 6 of the 12 measured cloud tool turns were `day` calls, and each paid a
model round (about 1,017 ms median to the function call) plus the tool (about 80 ms) before the
answering round. Answering those from context drops both: about 1 s per agenda question (about
4.9 s to about 3.8 s, arithmetic from the measured medians, not yet measured on a phone). The cost
is the block in the uncached half of each request, estimated at about $0.0003 per round.

## Decision

Kevin approved it on 2026-11-16 knowing it skips the lookup step ("yes i agree", to "today's due
items and calendar kept in the prompt, skipping the lookup step for common questions"). It is
bounded to the two sources above, read from data the phone already fetches, stated honestly per
source, withdrawn on the first write.

## Consequences

- **Revisited when on-phone speech-to-text lands** (the plan's item E). Speech-to-text is the
  largest part of the turn today; once it is on the phone, the tool round may be cheap enough
  relative to the whole turn that the pull rule can come back for this case too.
- The model may answer a wider question ("this week") from the 24-hour list. The block forbids it;
  only a phone run shows whether the model obeys.
- Weather is not in the pack (`FactsParser.ENGINE_SOURCES`); adding it is an engine change and a
  separate decision.
- CLAUDE.md section 7 points here; the build checklist's "pull-based tool" item still applies to
  every other domain.
