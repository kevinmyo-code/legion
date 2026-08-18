---
map: mission-control
ticket: 09
title: "Utility screens: how far relayout goes"
type: grilling
status: resolved
status-detail: ""
blockers: ["05"]
blocked-by: ["[[05-tiling-grammar]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Utility screens: how far relayout goes

## Question

Settings, Key, DriveSync, Cars and Companions are in scope for relayout now. What does that
actually mean for each?

`cyberdeck-ui` ruled these out of scope as inherit-only. That ruling is reopened by this map's
charting. But "in scope" is not the same as "becomes a console" - a settings list that tiles
itself into panels is worse at being a settings list.

**The screens** (`ui/SettingsScreen.kt`, `SettingsRows.kt`, `KeyScreen.kt`, `DriveSyncScreen.kt`,
`CarsScreen.kt`, `CompanionsScreen.kt`).

**Resolves, per screen:**

1. **Which of these are genuinely lists of controls** and should stay lists wearing the new chrome,
   versus which have real state worth rendering as a console panel. `DriveSyncScreen` has sync
   status, last-success time and failure states - that is telemetry. `SettingsScreen` is toggles.
2. **The form-control vocabulary.** Switches, text fields, buttons and the API-key paste field have
   no equivalent in any of the four refs, which are read-only displays. Decide what a switch, a
   text field and a destructive action look like in this language, because M3 defaults will not
   match and every one of these screens needs them. This is the substantive output of the ticket.
3. **`KeyScreen` specifically.** It handles a pasted secret and a validation ping with three
   outcomes (`VALID` / `INVALID_KEY` / `NETWORK_ERROR`). Those outcomes are states; decide their
   treatment against ticket 04's tiers.
4. **First-run and consent surfaces.** These are the screens a stranger sees first under
   clone-and-run. They must be legible before any of the deck's vocabulary has been learned.
5. **What stays plain.** Ruling a screen "tokens only, no relayout" is a valid and probably correct
   answer for several of them. Say which, and why, so a build ticket does not gold-plate it.

**Constraint.** CLAUDE.md §8 (L11): the consent screen is where the last theme's contrast bug
shipped. Whatever this ticket decides, its verification must include rendering these screens on
the device, not in a preview.

## Answer

Grilled with Kevin, 2026-08-14.

### 0. The premise was too narrow

The ticket treated the form-control vocabulary as a utility-screens problem. It is not.

**191 M3 controls across `ui/`. Only 49 are in the five utility screens.** The other 142 are in the
nine data surfaces this effort had already committed to rebuilding.

| Control | Utility screens | App-wide |
|---|---|---|
| `TextButton` | 26 | 120 |
| `OutlinedTextField` | 8 | 34 |
| `Button` | 7 | 13 |
| `AlertDialog` | 5 | 12 |
| `Switch` / `Checkbox` / `RadioButton` / `DropdownMenu` | 3 | 12 |

So "what does a switch look like in this language" was an **app-wide gap that no ticket owned**, and
the data surfaces hit it three times harder than the utility screens do. Same shape as ticket 04's
50 red call sites and ticket 07's already-spent motion budget: the premise assumed a small, tidy
scope and the grep said otherwise.

**This ticket therefore widens to own the control vocabulary for the whole app** (Kevin). Every
build ticket needs the answer, and this was already the only ticket touching controls.

### 1. Controls: deck-native look, M3 machinery underneath

Controls become outlined rectangles with stencil caps, matching the pills and the hard keys.

| Control | Deck form |
|---|---|
| Switch | a **two-state segmented toggle**, `ON` / `OFF`, active segment inverted. Not a sliding thumb. |
| Checkbox | `[X]` / `[ ]` before a stencil-caps label |
| Radio | `(*)` / `( )` before a stencil-caps label |
| Button | outlined rectangle, stencil caps - the hard-key shape at row scale |
| Text field | a label above, a value on a rule, a block cursor at the caret |
| Dropdown | a pane with 48dp rows, not a floating Material menu card |
| Dialog | a pane with a pill title, inside the bezel |

**The constraint that makes this safe, and it is not optional.** `Theme.kt` is explicit that M3 is
kept for component behaviour, touch targets and accessibility semantics; only the token layer was
ever meant to change. A custom shape must therefore carry the M3 machinery itself:
`Modifier.toggleable(role = Role.Switch)`, `selectable(role = Role.RadioButton)`, a real
`stateDescription`, and the 48dp target from ticket 05. **A control rebuilt as a bare `Box` with an
`onClick` is a regression, not a restyle**, and it will not be visible in a screenshot - it shows up
only in TalkBack.

Destructive controls follow ticket 04: `ink` outline normally, full `chrome` fill only on the
confirming step.

### 2. Utility screens: two get panels, four stay lists

| Screen | Treatment |
|---|---|
| `DriveSyncScreen` (+ `sync/DriveSyncRows`, `GoogleAccessScreen`) | **panels** - sync status, last-success time and failure states are genuine telemetry |
| `KeyScreen` | **panel** - the validation ping has three real outcomes and they are state |
| `SettingsScreen` (+ `SettingsRows`) | list, new chrome and controls |
| `CarsScreen` (+ `fleet/CarRows`) | list, new chrome and controls |
| `CompanionsScreen` (+ `companions/CompanionRows`) | list, new chrome and controls |
| `SpotifyScreen` (+ `spotify/SpotifyRows`) | list, new chrome and controls |

**A settings list tiled into panels is a worse settings list.** "In scope for relayout" was never the
same as "becomes a console", and four of the six earn nothing from tiling.

### 3. `KeyScreen`'s three outcomes, mapped to ticket 04

`GeminiKeyValidator` returns `VALID` / `INVALID_KEY` / `NETWORK_ERROR`. Under ticket 04's tiers:

| Outcome | Tier | Treatment |
|---|---|---|
| `VALID` | none | the word, no tag. Silence is the strong state. |
| `INVALID_KEY` | ADVISORY | `INVERTED_AMBER` tag - act on this |
| `NETWORK_ERROR` | ADVISORY | `INVERTED_AMBER` tag - act on this, and say it is the network, not the key |

**None of the three is ALARM.** A key that has not been pasted yet is the fresh-install state, not a
failure, and nothing has failed a gate.

### 4. First-run legibility, a constraint rather than a decision

`KeyScreen` and the consent surfaces are what a stranger sees first under clone-and-run, **before
any of the deck's vocabulary has been learned**. They must be legible cold: a segmented `ON`/`OFF`
toggle is only obvious if it looks like a control, and a pill-titled dialog is only obvious if the
action buttons read as buttons.

This is also exactly where the last theme's contrast bug shipped (CLAUDE.md §8, L11 - `surface` and
`errorContainer` colliding put quarantine-red body text on the consent screen). **Whoever builds
this renders these screens on the device, not in a preview.**

### 5. Handed on

- The control vocabulary is now a **prerequisite for every build ticket**, not just the utility ones,
  and probably wants building alongside the theme rather than per-screen.
- **A verification step for every build ticket landing a control:** confirm the M3 role, state
  description and 48dp target with TalkBack on. Screenshots cannot show this.

### Assumptions ledger

| Claim | Tag |
|---|---|
| 191 controls app-wide, 49 in the utility screens, and the per-type split | **`traced`** - grepped `app/src/main/java/com/kevin/legion/ui/` this session |
| `GeminiKeyValidator` returns those three outcomes | `traced` - named in CLAUDE.md §3 and the shipped `KeyScreen` |
| M3 is kept for behaviour, touch targets and semantics | `traced` - `Theme.kt` doc comment states it |
| A bare `Box` + `onClick` loses TalkBack semantics | `reasoned` - standard Compose behaviour; not demonstrated here |
| Which screens have "real state worth a panel" | `reasoned` - judgement from reading their contents, not an inventory of every field |
| Nothing was rendered, and no TalkBack pass was run | - |
