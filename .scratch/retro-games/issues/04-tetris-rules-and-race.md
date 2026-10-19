---
map: retro-games
ticket: "04"
title: "Decision: built-in Tetris, score race with Mia, transient engine match"
type: decision
status: resolved
status-detail: "Kevin 2026-10-18: score race, transient only, invites both ways always. ADR 0060"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# Decision: built-in Tetris, score race, transient engine match

Rulings in `memory/library/decisions.md` 2026-10-18 and ADR 0060. Builds: 05 (engine + PWA), 06 (phone).

## The rule set (binding on both implementations)

- Board 10 wide, 20 visible rows, 2 hidden spawn rows above.
- **RNG: mulberry32**, seeded with the match's uint32 `seed` (32-bit integer arithmetic throughout):
  ```
  a = seed
  next(): a = (a + 0x6D2B79F5) | 0
          t = imul(a ^ (a >>> 15), 1 | a)
          t = (t + imul(t ^ (t >>> 7), 61 | t)) ^ t
          return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  ```
- **7-bag:** each bag starts `[I, J, L, O, S, T, Z]`, Fisher-Yates: for i = 6 down to 1,
  `j = floor(next() * (i + 1))`, swap i and j. Bags concatenate.
- **Rotation: SRS** with the standard JLSTZ and I wall-kick tables. Hold (once per piece), ghost,
  5-piece preview.
- **Score:** single 100, double 300, triple 500, tetris 800, each times the level before the clear.
  Soft drop 1 per cell, hard drop 2 per cell. No T-spin, combo or back-to-back bonus.
- **Level** = 1 + floor(lines / 10). Gravity seconds per row = `(0.8 - (level - 1) * 0.007) ^ (level - 1)`.
- **Race:** 180 s from the engine's `start_at`. Topping out ends that player's game early; the score
  stands. Highest score wins; equal is a draw.
- **Fixtures:** `shared/tetris/fixtures.json` holds seed to first-N-pieces vectors and
  (level, lines cleared, drop cells) to score vectors. Both test suites read that one file.
