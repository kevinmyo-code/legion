---
map: spotify-voice
ticket: 03
title: "Fold ten capabilities into the tools that already exist"
type: task
status: open
status-detail: "Built (0f94dee). MusicAction enum, 20 actions, all implemented, count pinned by a test. NOT installed, NOT verified on the phone."
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Fold ten capabilities into the tools that already exist

## Question

There are ~89 tool declarations today. The 2026-08-17 dispatcher split exists because **a bloated
tool surface makes the live model choose worse** - the routing bug that cost a session
(`log_workout_set` never called, the model routing to `ask_goals` instead) is what that split was
for.

This map adds queue, like, unlike, follow, unfollow, shuffle, repeat, seek, now-playing, playlist
play, playlist add and a recommendation path. **As separate declarations that is thirteen new tools
on a surface already known to be too big.**

Settled with Kevin 2026-08-19: **fold them.** Also settled: **music does NOT go behind an
`ask_music` dispatcher** - a dispatcher costs a second model round trip, and music is the one domain
where two seconds of latency IS the complaint.

## Scope of the build

1. **`control_music` grows an action enum** rather than spawning siblings: the existing
   play/pause/next/previous, plus `queue`, `shuffle_on`, `shuffle_off`, `repeat_off`,
   `repeat_track`, `repeat_context`, `seek_forward`, `seek_back`, `restart`, `like`, `unlike`,
   `follow_artist`, `unfollow_artist`. Optional `seconds` for seeks, optional `query` for queue.
2. **`play_music` keeps its shape** and gains what ticket 08 needs (playlist by name) and ticket 11
   needs (a recommendation seed). No new declaration.
3. **`browse_my_music` keeps its five sources** and gains whatever tickets 08 and 11 read.
4. **Net new declarations: at most one.** If the count grows past that, this ticket has failed.
5. **Every action's description states what it CANNOT do**, per ADR 0031. The failure this repo
   keeps hitting is a description promising what the code does not do - `play_music` advertised
   album search while searching tracks only.

## Verification

- [ ] Count declarations before and after. Net new is at most one.
- [ ] `LiveToolboxDeclarationSetTest` still passes and covers the new actions.
- [ ] Speak each new action on the phone and confirm the model routes to it - an enum the model
      cannot reach is worth nothing. `on-device`.
- [ ] Ask for two actions in one breath ("shuffle it and skip") and confirm what happens is what was
      said happened. `on-device`.
