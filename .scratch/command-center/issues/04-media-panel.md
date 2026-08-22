---
map: command-center
ticket: "04"
title: "Music gets buttons"
type: build
status: built
status-detail: "Built: transport, volume, queue, search, browse, mini-bar for Home. Transport works without Spotify. Owes the on-phone pass. Drift note: transport ordering restates a private LiveToolbox helper - wave 2 ticket 08 lifts it to shared."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Music gets buttons

Survey: all five media tools voice-only - transport, volume, play, browse, queue. The domain most
used in a loud car, which is where the mic fails.

## Build

A now-playing panel (`ui/media/`): track/artist from `NowPlayingController`, play/pause/next/prev
via the same `SpotifyController`/`MusicController` paths `control_music` dispatches to (trace
first), volume via the same path `control_volume` uses, and a queue list from the same source
`get_music_queue` reads. Search-and-play and library browse get a minimal surface (text field +
results + tap-to-play through the same resolution `play_music` uses).

Own route plus a compact mini-bar composable exported for Home (ticket 01 consumes it).

## Rules

- ADR 0035: same controllers. Spotify not connected is an honest state naming the fix, not an empty
  panel. No auto-connect side effects from merely opening the panel.

## Verification

- Suite green both ways. On the phone: pause from the panel while a track plays, change volume, see
  the queue.
