---
map: spotify-voice
ticket: "14"
title: "Build: a mood or genre request plays something, instead of landing on a coincidental title"
type: build
status: open
status-detail: "charted 2026-11-24; ruled a ticket of its own rather than part of that day's music bug fixes"
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# A mood or genre request plays something

## What the code does today

`play_music` is a named-thing search and nothing else - its own description says "Play a named
song, artist, album or playlist". No mood, genre or radio capability exists anywhere in the music
surface.

A request that names no specific thing therefore has nothing to resolve against, and its free text
goes to Spotify's track search as though it were a title. Two separate faults sit behind that, and
they need different fixes:

1. **No capability.** There is no path from a mood or genre to a playable Spotify context.
2. **A gate that can read a description as a title.** `SpotifyMatch`'s token-overlap rule accepts
   at `TOKEN_OVERLAP_THRESHOLD` (0.6), so a three-word descriptive phrase sharing two words with
   some track's title clears it. The rule exists to reject the wrong RECORDING of a named song; it
   was never meant to adjudicate a phrase that names no song.

The 2026-11-24 leftover-token fix narrows fault 2: an unexplained leftover query token now rejects
a title hit, so a descriptive phrase has to match the candidate's title or artist almost wholly to
be accepted. It does not create fault 1's capability.

## Scope of the build

1. A `type` (or a sibling action) for a request that names no specific thing, routed to a Spotify
   context rather than a track search: genre seeds, a mood or activity playlist, or the
   artist-radio path, whichever survives the quota check below.
2. **Name what played, out of the result, every time.** `pickedLabel` already carries this. A
   loosely resolved request is exactly the case where the result cannot be predicted from the
   words, so the spoken line has to say what started.
3. Decide which side refuses a description-shaped query: the declaration steering the model to the
   new type, or the tool rejecting it. That is this ticket's design question, not an assumption.

## Verify before building on it

- **The endpoint.** The February 2026 cull removed several reads for development-mode apps,
  `/recommendations` among the candidates. Confirm what a dev-mode token can still call before
  choosing among the three options; an absence of evidence is not evidence of availability
  (ticket 13 paid for that lesson).
- Search `limit` is a measured 10 for this app, not the documented 50 (`SEARCH_LIMIT`'s own doc).

## Open design question

Whether a request phrased as asking for an opinion should OFFER rather than play. Today every
resolvable request plays. Not assumed either way here.
