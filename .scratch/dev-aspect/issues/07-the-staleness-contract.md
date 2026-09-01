---
map: dev-aspect
ticket: "07"
title: "The staleness contract"
type: build
status: open
status-detail: ""
blockers: ["05"]
blocked-by: ["[[05-the-github-sync]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# The staleness contract

## Build

Every answer this aspect gives is only as true as its last sync, and the assistant must never speak
as though it is live.

**The rule:** an answer built from rows older than the threshold states the age in words. *"As of
Tuesday morning, four open items on Project X."* Not a colour, not a small grey timestamp on a
screen the listener cannot see - words, in the spoken answer, because voice has no other channel.

This is CLAUDE.md section 7's outcome-verb rule pointed at freshness. The assistant may not assert
a present-tense fact about the world that it read from a cache without saying when the cache was
filled.

**A never-synced project and a project with no open work are different sentences.** The first says
it has not looked; the second says there is nothing. Rendering the first as the second tells Kevin
he is clear when the sync is broken - the same failure shape as the calendar permission case in
CLAUDE.md section 1. `last_synced_at` NULL and zero rows are not the same state and must not
produce the same speech.

**A failed sync is visible.** Two consecutive failures surface somewhere Kevin will see without
asking. Not a raise - a raise about a broken sync would need to satisfy the section 7 compulsion
test, and this is a status, not a nudge. The hands path (ticket 08) shows it.

## Decide during build

- The threshold at which the age is spoken. A daily cron makes anything over about a day worth
  saying; pick a number and write it down rather than leaving it to the model.

## Verification

- Rows with `last_synced_at` NULL produce "I have not synced that yet," never "nothing pending."
- A stale answer contains the age. Tested at the prompt-surface level the way
  `AriaBrainHonestyClauseTest` tests presence; obedience is not testable and that limit is stated,
  not glossed.
- Two failed runs are visible on the hands path.
