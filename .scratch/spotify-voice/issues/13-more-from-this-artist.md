---
map: spotify-voice
ticket: 13
title: "More from this artist, and what else they have"
type: task
status: open
status-detail: "Built (8fc0ee0). /artists/{id}/albums availability is traced but NOT tested - it may 403. NOT installed, NOT verified on the phone."
blockers: ["02", "03"]
blocked-by: ["[[02-app-remote-spine]]", "[[03-tool-surface]]"]
open-blockers: 2
ready: false
tags: [ticket]
---
# More from this artist, and what else they have

## Question

Kevin, 2026-08-19, correcting the premise of tickets 10 and 11 mid-grill:

> its more of like > more music from this artist. or any other albums from him etc.

**This is not a recommendation and it needs no model guessing.** "Keep going with this artist" and
"what else has he got" are catalogue questions with exact answers, and they are almost certainly
what he will actually say in the car - he told the rec-engine ticket outright that he usually knows
what he wants.

It is also **more reliable than anything ticket 11 can build**, because every result is a real
Spotify row rather than a model suggestion that has to survive a 10-result search before it can be
spoken.

## Scope of the build

1. **"More from this artist"** - take the artist from player state (ticket 02's
   `subscribeToPlayerState`), then play the artist context. `PlayerApi.play("spotify:artist:...")`
   is documented as supported and needs no search at all.
2. **"What else does he have"** - list the artist's albums, spoken as a short handful, never the
   whole discography. **Verify the endpoint first**: the Feb-2026 cull removed
   `GET /artists/{id}/top-tracks` and every batch multi-get for dev-mode apps.
   `GET /artists/{id}/albums` was NOT on the removed list in the research, but that is an absence of
   evidence, not evidence of absence - **check it before building on it**
   (`research/01-api-capability-surface.md` flags exactly this class of uncertainty).
3. **"Play his album <name>"** - resolve within the artist's own albums rather than through open
   search, which is capped at 10 and will rank the catalogue against him.
4. **Fold into the existing tools** per ticket 03 - this is `play_music` and `browse_my_music`
   growing, not two new declarations.
5. **When the artist cannot be determined** (nothing playing, non-Spotify audio), say so. Do not
   guess at an artist from the last thing seen.

## Why this sits ahead of the recommendation engine

Ticket 10's resolution records Kevin's own priority: the recommender is the least important thing on
this map. This ticket is the capability he described wanting, it is cheaper, and every answer it
gives is checkable. **Build this before 11.**

## Verification

- [ ] Ask for more from the current artist mid-track; the artist's music keeps playing. `on-device`.
- [ ] Ask what else they have; the albums named are real and are that artist's. `on-device`.
- [ ] Ask for one of those albums by name; the whole album plays. `on-device`.
- [ ] Ask with nothing playing; it says it cannot tell who you mean. `on-device`.
- [ ] Confirm `GET /artists/{id}/albums` is actually available to this Client ID before relying on
      it - and if it is not, record what replaced it.
