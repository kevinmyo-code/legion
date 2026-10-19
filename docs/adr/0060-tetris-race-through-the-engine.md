---
status: accepted
decided: 2026-10-18
decided-by: Kevin
supersedes: []
source: "[[decisions#2026-10-18 - Tetris, built in, and a score race with Mia through the engine (Kevin)]]"
tags: [adr]
---

# 60. Built-in Tetris, and a household score race held transiently by the engine

## Standing

**Tetris is built into LEGION on the phone and the PWA.** Multiplayer is a **score race**: one
shared seed, one engine-set start time, three minutes, highest score wins.

**The engine holds a match only while it lives.** Invite, seed, clock and live progress, deleted
once the match ends or the invite expires. No scores or history are kept. This amends ADR 0058,
whose "nothing reaches the engine" still holds for the ROM launcher.

**Invites go both ways.** To a PWA: Web Push sent at invite time. To the phone: a background poll
of the engine and a local notification, because the phone has no push channel by ruling.

**Clients poll; there are no websockets.** A race syncs a score, not a board.

## Consequences

- Kotlin and TypeScript implement the same rules and are tested against one shared fixture file,
  `shared/tetris/fixtures.json`.
- An invite that expires, is declined or is cancelled says so in words to the other side.
- The new table is household-scoped and listed in `TENANT_TABLES`; the new push kind `game_invite`
  is a per-person preference like the others.
