---
map: drive-test-2026-08-18
title: "Map: Drive test 2026-08-18"
charted: 2026-08-18
charted-by: "Kevin (drive) + Opus"
effort: "`.scratch/drive-test-2026-08-18/`"
tickets: 5
open: 4
status: open
tags: [map]
---
# Map: Drive test 2026-08-18

## Destination

**The four things Kevin hit on a real drive are either fixed on-device or consciously deferred, and
the assistant never again claims an action it did not take.**

Two of the four are builds, two are decisions. The honesty clause is the one that outlives this map:
three of the four failures were silent, and the fourth was worse than silent - it was a confident
false report.

## Notes

**Domain:** LEGION, Android phone app (Kotlin, Compose, Room v25), `com.kevin.legion`. Read
`CLAUDE.md` for rules and `memory/MEMORY.md` for state before deciding anything.

**Where this came from.** Kevin, on a real drive, 2026-08-18, verbatim:

> conversation drops after 3 turns. music > can we look up our favorite or recent albums? nav >
> google map doesnt work. ai doesnt open map. says hes opening it but it doesnt. voice > after a few
> turns, it doesnt remember the previous turn what we said. the context drops

Four reports, four distinct causes. **None of them was found by the test suite.** All four were found
by a human driving a car, which is the same shape as L11 and the mission-control screenshot findings:
install and look.

### What each report actually was - traced 2026-08-18, not remembered

| Kevin said | Actual cause | Ticket |
|---|---|---|
| "conversation drops after 3 turns" | A 10s idle timer parked the socket and stopped the mic with no notice. **Already fixed** in `1e3ee04`. | [01](issues/01-conversation-waits-for-the-driver.md) |
| "it doesnt remember the previous turn" | Separate defect. Gemini Live keeps history **in the socket**; `goAway` is ignored and no resumption handle is requested, so any socket death cold-restarts with no memory and says nothing. | [02](issues/02-context-dies-with-the-socket.md) |
| "says hes opening it but it doesnt" | **There is no navigation capability at all.** Not one of the 89 tools opens a map. With no tool to call, Gemini answered in free text and invented the compliance. | [03](issues/03-no-navigation-capability.md) |
| "can we look up our favorite or recent albums?" | One Spotify scope is granted (`user-read-private`) and the only data endpoint called is a tracks-only search. Library, recents and top items each need a scope that is not held. | [05](issues/05-reading-kevins-spotify-library.md) |

**The two reports Kevin filed as one thing were two things.** "Conversation drops after 3 turns" and
"it doesnt remember the previous turn" read as one complaint and are separate defects with separate
fixes - the first is a timer, the second is socket-scoped history. `1e3ee04` fixed the first and
says so in its own message; it explicitly did not touch the second.

### Standing preferences for this effort

- **Kevin is at the abstraction layer.** Bring him forks with real cost or taste; decide
  implementation without asking.
- **A tool result must reflect what actually happened.** The whole map turns on this.
- **Install and drive.** Nothing on this map is done because it compiles. Ticket 01 is committed and
  has **not** been re-tested on a drive.

### Settled, carried in - binding on every ticket

| # | Decision | Consequence |
|---|---|---|
| 1 | **A conversation waits indefinitely** (Kevin, 2026-08-18, asked directly). It ends on his tap, on socket death, or on the crisis path. | No upper bound on an active conversation. Accepted; see ticket 01. |
| 2 | **The assistant never claims an action it did not take.** | Ticket 03 builds the missing tool; ticket 04 decides what is said when no tool exists. Neither is optional. |
| 3 | **CLAUDE.md sec 4's posture applies to speech, by analogy.** Do not assert what you did not observe. | The garage relay clause (`ai/AriaBrain.kt:85-89`) is the codebase's own precedent and the model for ticket 04. |

## Decisions so far

<!-- one line per closed ticket -->

- [A conversation waits for the driver, not a ten-second timer](issues/01-conversation-waits-for-the-driver.md)
  — **Fixed in `1e3ee04`, not re-tested on a drive.** The `vadMode` branch of `turnComplete` no
  longer arms an idle timer; `armIdleTimeout` now governs speak-only sessions only. Kevin's call,
  asked directly: a hands-free conversation waits indefinitely. Accepted consequence, written into
  the commit: a forgotten conversation holds a live mic and a billed session until the service is
  torn down.

## Not yet specified

In scope, but not sharp enough to ticket. Graduates as the frontier advances.

- **Whether a lost thread should be restored or merely announced.** Ticket 02 decides the mechanism;
  what the driver actually hears when it happens is downstream of that and of ticket 04.
- **An upper bound on a conversation that is not a timer.** Settled decision 1 removed the timer on
  purpose. If the billed-session cost ever bites, the replacement is a different shape - ignition
  off, screen off, a distance - not a shorter timer.
- **Whether navigation is a tool or a surface.** Ticket 03 scopes the minimum honest tool. Whether
  LEGION ever knows where the driver is going, rather than just firing an intent at Maps, is a
  separate effort.
- **A local play history.** Raised inside ticket 05 as a fork. Cannot be specified until that ticket
  settles whether LEGION reads Spotify's memory or keeps its own.

## Out of scope

Ruled beyond this destination. Never graduates; returns only as a fresh effort.

- **Embedded navigation, a map surface, or any Mapbox revival.** Mapbox and `NavGeocoder` were
  dropped deliberately in the pivot. Ticket 03 fires an intent at an app the driver already has.
- **Rebuilding the assistant's core register.** `ai/Personas.kt` owns the voice. Ticket 04 writes an
  honesty clause that sits on top of it.
- **Reintroducing mixtapes or the music-taste ledger.** Both were retired in the pivot and the
  schema confirms they are gone. Ticket 05 may propose a NEW table; it may not resurrect those.
- **A Kevin-hosted anything.** The Spotify re-auth in ticket 05 is a browser redirect on the
  driver's own device, per CLAUDE.md sec 7.
