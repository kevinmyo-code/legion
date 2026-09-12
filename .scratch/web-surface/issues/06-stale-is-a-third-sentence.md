---
map: web-surface
ticket: "06"
title: "Stale is a third sentence"
type: build
status: open
status-detail: ""
blockers: [" say when the data was last read, and when a refetch failed"]
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Stale is a third sentence

Charted 2026-09-12. See `.scratch/web-surface/map.md` for the evidence this rests on - every figure
there was read from the live engine in Kevin's own browser, not assumed.

## Why this one goes first

Every other ticket on this map renders data whose freshness is currently unstated, and this map was
opened the same day `/api/changes` was found returning an empty events list with a cheerful 200 for
what may have been weeks. **That bug and this gap are the same shape**: the screen was confident and
wrong, and nothing on it could have told Kevin otherwise.

## The three sentences, of which only two exist

| State | What it means | Today |
|---|---|---|
| **Empty** | The read worked. There is nothing. | Built - "Nothing on the calendar today." |
| **Unreachable** | The read failed and there is nothing cached. | Built - `changes.isError` renders "Could not reach the engine, so this is not today's real list." |
| **Stale** | The read FAILED but a previous one succeeded, so the screen is showing older data. | **Missing.** |

TanStack Query serves the last good response when a refetch fails. `isError` is false while cached
data exists, so the screen renders a day that may be hours or days old with nothing said. **A day
view that is quietly a day old is indistinguishable from a correct one**, which is the whole reason
CLAUDE.md §1 separates empty from unreadable in the first place.

## Build

1. **Say when the data was last read**, in words, on Today and Lists. Not a spinner and not a
   relative-time badge alone - a sentence a person can act on.
2. **When a refetch fails but cached data is showing**, say so and keep the data. Never blank a
   screen that has something true on it, and never let it pass as current.
3. **Distinguish it from unreachable-with-nothing.** Those are different: one has stale truth, the
   other has nothing at all.
4. Use `dataUpdatedAt` / `isFetching` / `failureCount` from the existing query rather than a parallel
   clock.

## Verification

- A test per sentence, asserting the three render differently and that stale never reads as current.
- In the browser: load, sign in, stop the engine or block `/api/changes`, refetch, and see the stale
  sentence with the data still on screen.
