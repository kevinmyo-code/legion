---
map: dev-aspect
ticket: "07"
title: "The staleness contract, and the three states of not-knowing"
type: build
status: open
status-detail: ""
blockers: ["05"]
blocked-by: ["[[05-the-github-sync]]"]
open-blockers: 0
ready: true
tags: [ticket]
---
# The staleness contract, and the three states of not-knowing

## What changed from charting

Half of this ticket dissolved. Azure is read-through now (ticket 02), so **a live answer has no
age** and nothing about Azure needs a staleness clause. What is left is the LEGION board feed
(ticket 05), which is a file on GitHub Pages with a `generated_at`, plus a failure the research
surfaced on the Azure side that is not staleness at all.

## Build

**For the board feed.** An answer built from `docs/board.json` states the age in words when it is
older than the threshold: *"as of Tuesday morning, twelve ready tickets."* Words, in the spoken
answer - not a colour, not a small timestamp on a screen the listener cannot see. Voice has no
other channel.

Threshold: **24 hours.** The commit hook regenerates on every commit, so a feed older than a day
means Kevin has not committed for a day, which is itself worth saying. Written down here rather
than left to the model.

**For Azure.** No age clause. But the research found the failure that matters more: a dead PAT
returns 401, and a throttled request returns **HTTP 200** with a `Retry-After` header. Both must
reach the user as "I cannot see Azure DevOps right now", and a 200 that is really a throttle must
never be read as an empty result set.

## The three states, which must never collapse into each other

CLAUDE.md section 1's unreadable-versus-empty rule, which cost an invented lunch appointment when
it was got wrong. It has three states here rather than two:

| State | What the assistant says |
|---|---|
| Never fetched / no PAT / 401 / throttled-200 | "I cannot see it" - names the source and the reason |
| Fetched, zero open items | "Nothing open" |
| Fetched, but stale past the threshold | The count, **and its age**, in the same sentence |

Telling Kevin he is clear when the app cannot see is the failure. It is the same sentence as
telling him a receipt reconciled when the parser found nothing.

## Verification

- Three separate tests, one per row of that table, asserting the three produce different strings.
- A `generated_at` older than 24 hours puts an age into the spoken text.
- A synthetic 200-with-`Retry-After` produces the cannot-see sentence, not "nothing open".
- Prompt-surface presence is testable; obedience is not. That limit is stated, not glossed - the
  same honesty `AriaBrainHonestyClauseTest` carries.
