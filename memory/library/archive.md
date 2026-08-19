---
shelf: archive
status: frozen
kind: archive
tags: [library]
---

# Archive

> **STATUS: FROZEN ARCHIVE (banner added 2026-08-01).** This shelf is Midnight AI history: a
> head-unit car launcher with a commercial model and a city-pop design language. All three of
> those premises died in the 2026-07-30/31 pivot to LEGION. Nothing below governs LEGION.
> Read it as reference for why something was built the way it was. **Do not act on its
> blockers, sprints, backlog items, or hardware notes.** Live rules are in CLAUDE.md; live
> state is in memory/MEMORY.md. See CLAUDE.md §11.


Closed or superseded items, historical session log. Maintained by the librarian. Backlog order
below reflects the original session-16 triage order.

## Historical session log

| Session | Date | File | Summary |
|---|---|---|---|
| 16 | 2026-07-05 | session-16-field-notes.md (never created; see note below) | Real-world Cherokee hardware test. 8 bugs, 12 UX polish, 4 features, 1 character fix (C1), 1 reopen request (R1). |

Note: `session-16-field-notes.md` was referenced by the old MEMORY.md's Session log table but was
never created on disk; this summary row is all that survives of it. Sessions from now on are
filed by the librarian as `memory/library/session-YYYY-MM-DD-<slug>.md`.

## Split-screen NATIVE fix (found + fixed alongside B3, 2026-07-07)

[SUPERSEDED 2026-07-08 by R1 Companion Badge, see [[decisions]]]

NATIVE mode had no panel fallback: `FLAG_ACTIVITY_LAUNCH_ADJACENT` is a documented no-op from
Midnight AI's fullscreen home-screen start; NATIVE blindly trusted it with zero verification, so
the launched app opened fullscreen and covered the companion with no split at all ("app displays
over our AI"). AUTO already had a settle-check + panel-fallback safety net; NATIVE reused the same
`attemptNativeThenPanel` logic in `SplitLauncher.kt`. This entire approach (native multi-window +
full-height floating panel) was killed 2026-07-08 and replaced by the Companion Badge; see
library/decisions.md R1 entry. `SplitLauncher.kt` has been deleted.
