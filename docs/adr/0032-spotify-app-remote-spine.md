---
status: accepted
decided: 2026-08-19
decided-by: Kevin
source: "[[decisions#2026-08-19 - Spotify voice control: the settled shape (.scratch/spotify-voice, tickets 01-09 + 13 BUILT, none on-device)]]"
tags: [adr]
---

# 32. Spotify App Remote is the spine, and its connection is held

## Standing

App Remote is how sound comes out; the Web API only resolves names into URIs. The connection is
held for the life of `AriaForegroundService`, deliberately against Spotify's own lifecycle guidance.

## Context

LEGION holds no Spotify playback scopes, so the Web API cannot start or steer playback anywhere.
App Remote can, and it is the only route that CREATES an active device - asking for music with the
Spotify app closed is solvable no other way. Spotify's documentation says to connect in `onStart`
and disconnect in `onStop`; a per-command connect costs latency on every utterance, and the driver
is driving.

## Decision

`media/SpotifyWebApi.kt` resolves what to play; `media/SpotifyController.kt` (App Remote) plays it.
`AriaForegroundService` calls `connectSilently` in `onCreate` and never calls `disconnect` in its
own lifecycle. The violation of vendor guidance is deliberate and is written into the code as a
comment at both the connect site and the absent disconnect.

## Consequences

- The cold case works by construction: play-by-voice with Spotify killed should bind, wake the app,
  and play. This is the headline claim and it has never been run on a phone.
- Now-playing truth comes from App Remote's own pushed `PlayerState`, not from guessing.
- If Spotify ever enforces its lifecycle guidance harder, this is the first place to look.
