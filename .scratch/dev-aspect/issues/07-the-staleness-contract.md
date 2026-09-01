---
map: dev-aspect
ticket: "07"
title: "The staleness contract, and the three states of not-knowing"
type: build
status: resolved
status-detail: "Resolved 2026-09-01. ProjectsReachability + 15 tests, suite 2829 green in an isolated worktree. Pure logic, nothing owed on hardware - the speaking is ticket 08."
blockers: ["05"]
blocked-by: ["[[05-the-github-sync]]"]
open-blockers: 0
ready: false
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

## Resolution (2026-09-01) - built and verified

`projects/ProjectsReachability.kt` and `ProjectsReachabilityTest.kt`. Pure logic, no Android types
and no network, same shape as `calendar/OpenerCalendarBriefing.kt`.

| Verification step | Outcome |
|---|---|
| Three tests, one per row of the table | **done**, and a fourth for the case the table omits |
| A `generated_at` older than 24h puts an age into the spoken text | **done** |
| A synthetic 200-with-`Retry-After` produces the cannot-see sentence | **done** |
| Presence testable, obedience not - stated rather than glossed | **done**, in the KDoc and here |

`compileDebugKotlin` clean, suite **2829 tests, 0 failures, 0 errors**, read from the JUnit XML
rather than the console summary. All 15 new tests ran; the class was confirmed present in the
results rather than assumed.

**Built in an isolated git worktree** (`.claude/worktrees/projects`, branch `feat/projects-surface`)
because another session was writing to the main tree. That is not incidental - `memory/` records
that two Gradle writers here corrupt each other's builds and that contention can fake a passing
run, and the first attempt at this verification sat queued for 40 minutes behind exactly that.

### The deviation from this ticket, made deliberately

**This ticket's table lists three states. The real shape is two axes**, readable and fresh, and the
row list silently omits **stale-and-empty**. That is the most dangerous of the four: "no open work"
spoken from a four-day-old file is a confident claim about today built from last week's evidence.
It gets the age clause exactly as a stale non-empty reading does, it has its own test, and the
KDoc says the ticket's table is the weaker statement of the rule and the file is the stronger one.

### Two guards for ticket 06 landed here

Both are pure logic, both come from ticket 03's research, and both belong to the not-knowing
contract rather than to the HTTP client:

- `classify(status, retryAfter)` checks **`Retry-After` before the status code**. Azure DevOps
  signals a throttle with HTTP 200; a classifier testing `status == 200` first hands a throttled
  body to the parser, finds nothing, and reports "nothing pending". A test asserts the ordering.
- `truncatedIfAtCap()` refuses to speak a count from a WIQL result sitting on the silent 20,000
  cap.

**Ticket 06 must call these rather than branching on a status code itself.**

