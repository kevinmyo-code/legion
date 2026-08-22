---
map: spotify-voice
ticket: 12
title: "Ship pass: installed on the A25 and drivable"
type: task
status: closed
status-detail: "Closed 2026-08-22 (Kevin): verified on a real drive - all Spotify functions work."
blockers: ["01", "02", "03", "04", "05", "06", "07", "08", "09", "11"]
blocked-by: ["[[01-scopes-and-one-reapproval]]", "[[02-app-remote-spine]]", "[[03-tool-surface]]", "[[04-queue]]", "[[05-library-writes]]", "[[06-shuffle-repeat-seek]]", "[[07-now-playing-truth]]", "[[08-playlists-by-name]]", "[[09-history-uri]]", "[[11-rec-engine]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Ship pass: installed on the A25 and drivable

## Question

**The destination of this map.** Not "it compiles", not "the tests pass" - a hash-verified install on
the A25 that Kevin can drive with, and a test list he actually runs.

Every other ticket here is `reasoned` until this one is done. **Nothing in the Spotify layer has ever
run against a real Spotify account** - not the four existing library endpoints, not album search, not
the re-auth flow.

## Scope

1. **Re-approval first, at a desk** (ticket 01). If the stale grant is discovered in the car, this
   map failed at its own settled decision 2.
2. **Build, install, verify by sha256.** Never by "Success" - the standing device note in
   `memory/MEMORY.md`.
3. **Walk Kevin's own test list** (2026-08-19):
   - [ ] Play an album by name, and the whole album plays, not one track off it.
   - [ ] Queue a track while something is playing.
   - [ ] Like what is playing, hands-free.
   - [ ] Shuffle on.
   - [ ] **The cold case: ask for music with Spotify closed.** Ticket 02's whole reason to exist.
   - [ ] A recommendation, per whatever ticket 10 decided.
4. **Account for every verification step on every ticket in this map** as done /
   deferred-with-a-named-follow-up / impossible-and-why, per CLAUDE.md sec 8 (L11). A surfaced gap is
   a gate, not a footnote.

## Verification

- [ ] Full suite green before the last commit.
- [ ] APK hash verified on the A25.
- [ ] Every box in step 3 ticked, or explicitly deferred with a reason.
