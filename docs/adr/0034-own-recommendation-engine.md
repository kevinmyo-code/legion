---
status: accepted
decided: 2026-08-19
decided-by: Kevin
source: "[[decisions#2026-08-19 - Spotify voice control: the settled shape (.scratch/spotify-voice, tickets 01-09 + 13 BUILT, none on-device)]]"
tags: [adr]
---

# 34. Music discovery is built in-house; Spotify's is unavailable

## Standing

"Play something like this" is answered by LEGION's own logic, never by a Spotify discovery
endpoint. Catalogue navigation ("more from this artist") is built and preferred; the recommender
itself is deliberately last.

## Context

Spotify closed its discovery surface (recommendations, related-artists, audio-features) to apps
registered after 2024-11-27, and LEGION's BYO client IDs ([[0033-byo-spotify-client-id]]) are all
post-cutoff registrations. There is no API to call, so the alternative was never "use Spotify's" -
it was "build one" or "don't have one".

## Decision

Build in-house, under §7's honesty rules: a suggestion resolves against the real catalogue before
it is spoken, and a dead one is admitted as the model's guess, never asserted as a fact about
Spotify's catalogue. Reasons must be falsifiable facts, not stories about the driver's taste. It
plays and queues, never saves; a skip lasts one drive and is never stored. Kevin's own priority
call: the recommender is the least important thing on the map ("i usually know what i want"), so
catalogue navigation shipped first and ticket 11 is built last.

## Consequences

- Every "like this" answer costs a Gemini call plus Spotify search round-trips on the driver's own
  keys; there is no cheap server-side similarity to lean on.
- One research claim stays downgraded: whether `/artists/{id}/albums` 403s on a post-cull client ID
  could not be re-verified from primary docs. The built code degrades to an honest spoken failure
  if it does.
