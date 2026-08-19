---
map: mission-control
ticket: 14
title: "Build: bezel, shell, status line, nav keys and boot"
type: task
status: resolved
status-detail: ""
blockers: ["13"]
blocked-by: ["[[13-build-theme-and-controls]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Build: bezel, shell, status line, nav keys and boot

## Question

Land the global bezel and the shell chrome around it.

Graduated from fog 2026-08-14.

## Scope

1. **`DeckBezel`**, drawn once at shell level around content AND the pinned rows, to ticket 03's
   geometry: 6dp inset, 1dp `chromeDim`, r14 corner arcs, a 64dp break at top and bottom centre, 6dp
   registration ticks in full `chrome` inset 5dp inside each corner, content padding 9/10/9/12.
2. **Insets.** The bezel sits INSIDE the system insets. The device has a **38 x 32dp centred notch
   and a 48dp three-button nav bar** (ticket 05, measured). The notch sits where the bezel's top
   break is; confirm they do not collide in the flesh rather than trusting the arithmetic.
3. **The pinned shell**: status line at top, Alfred strip and the five hard keys at the bottom, per
   ticket 05. None of the three scrolls. The bezel does not scroll.
4. **`StatusLine`** keeps its deferred-read cursor verbatim (ticket 07), gains ticket 04's ALARM
   segment which **replaces `SYNC` and `OBD` while present** and navigates to the alarm on tap.
5. **The cursor yields** (ticket 07): on a surface that defines its own ambient element, the cursor
   renders solid.
6. **Boot** to ticket 07's sequence, inside the unchanged 800ms: 0-250ms bezel traces from the
   corners, 250ms ticks land, 250-450ms status line types, 450-800ms content draws in. Cold start
   only; a warm resume from recents is still instant.

## Verification, all of it binding

- `compileDebugKotlin` and `testDebugUnitTest` green.
- **Measure the real content budget on the device and compare it to ticket 05's derived 584dp.**
  That figure came from estimated shell band heights and ticket 05 named it as the first number to
  re-measure once the shell exists. Every other budget on that ticket depends on it, so a
  discrepancy is a finding that goes back to ticket 05, not a rounding error to absorb.
- **Layout Inspector on the cursor and, when it lands, the uplink sweep**: recomposition counts must
  stay flat for the element and every ancestor while it animates (ticket 07).
- Install and verify by hash. Check the notch and the nav bar on the real device.

## Answer

Built 2026-08-14. `DeckBezel` is wired into the shell, `StatusLine` gained ticket 04's ALARM segment
and ticket 07's yielding cursor, and `BootOverlay` was rebuilt around the bezel trace.

### The measurement this ticket existed to take

**560dp, against ticket 05's derived 584dp.** That ticket named this as the first number to
re-measure once the shell existed, and it was 24dp high. **Ticket 05 carries the correction**; the
short version is that its system-chrome estimate was 4dp pessimistic and the entire error sat in its
own shell band estimates (170dp measured against 142dp guessed), which it had already tagged
`reasoned`.

Method: `uiautomator` bounds dump of the scrollable NavHost region, cross-checked against
`dumpsys window`'s `mFullConfiguration`. Bands sum to exactly 806dp, which is the self-check.

### What is in

- **`DeckBezel` wraps the whole `Scaffold`** - content and the pinned rows both - so the frame reads
  as the device rather than as a container. Confirmed on-device: corner arcs, the 64dp top and
  bottom centre breaks, and the four full-`chrome` registration ticks all render, distinct from the
  dim structural line.
- **`StatusLine` gained `alarmCount` / `onOpenAlarm`** (the inverted-pill ALARM segment that
  replaces `SYNC` and `OBD` while present) **and `cursorSolid`** (ticket 07's yielding cursor).
  **Both default to today's exact behaviour and nothing sets them yet.** There is no alarm state
  source in the app, and inventing one was explicitly out of scope; wiring belongs to the surface
  build tickets. The defaults are byte-identical code paths to the pre-ticket-14 branch.
- **`BootOverlay`** follows ticket 07's sequence inside the unchanged 800ms, reusing the real
  `DeckBezel` for the trace rather than a lookalike.
- **The app is not edge-to-edge**, so the bezel needs no `windowInsetsPadding` of its own. `themes.xml`
  sets opaque bars, there is no `enableEdgeToEdge` call, `targetSdk` is 34, and Android reserves both
  bars outside the Compose tree. Recorded on ticket 05 as a durable device fact.

### A misdiagnosis, recorded because the record is the point

Reviewing the first screenshot, **the orchestrator reported that the bottom bezel line passed behind
the hard-key row** and sent the build back for a fix. **That was wrong.** Pixel sampling of that
same original screenshot shows the amber key ending at y=1500 and the bezel line at y=1511 - an
11px gap. There was never an overlap; it was misread from a downscaled image.

The resulting change (`Modifier.fillMaxSize()` on the `Scaffold` inside `DeckBezel`'s content
lambda) **produced no visual change.** A global image diff of before and after is 5,928 differing
pixels out of 1,160,640, and all of them are the status-bar clock and battery indicator.

The change is **kept, but re-described honestly**: a `Box` bounds an unsized child without forcing
it to fill, so a `Scaffold` with no modifier sizes itself by its own content rather than by the
region the parent's padding carved out. That is a real robustness improvement and the reasoning is
sound. It is not a bug fix, and it did not fix anything.

**The lesson, and it is a repeat of an existing one in a new costume:** a screenshot inspected by eye
is evidence of the same quality as a colour judged by eye. Ticket 06 established that judging colour
separation by eye is not a check. **Judging a 1dp alignment from a downscaled screenshot is not a
check either.** Sample the pixels.

### The real deviation, found by sampling

**Bottom content padding renders ~5.5dp against ticket 03's specified 12dp.** The bezel line sits
correctly at 6.5dp from the window edge (spec: 6dp inset), so the frame itself is right; it is the
gap between the hard-key row and the line that is short.

Not fixed here. It is a 6.5dp discrepancy on one edge, it was found by measurement rather than
complaint, and it should be corrected by whoever next touches the shell rather than by a third
round-trip on this ticket.

### Verification accounting (CLAUDE.md §8, L11)

| Step | Status |
|---|---|
| `compileDebugKotlin -Pnokey` | **DONE**, green, run directly |
| `testDebugUnitTest` | **DONE**, green, run directly |
| Measure the content budget against 584dp | **DONE**, 560dp, correction filed on ticket 05 |
| Install and verify by hash | **DONE**, local and device SHA-256 both `061b361e...` |
| Screenshot and confirm the bezel renders | **DONE**, plus pixel sampling, which is what caught the misdiagnosis |
| Layout Inspector on the cursor | **UNMET, impossible here.** Cannot attach Layout Inspector to a physical device from this session. Ticket 07 made flat recomposition counts a verification step for any ambient element; **this remains owed** and moves to the first build ticket that lands one on a real surface |
| Capture the boot sequence mid-animation | **UNMET, and it revealed something.** Cold process start on this device takes **over 1.2 seconds** before Compose first draws, which is longer than the 800ms boot animation itself. Five burst-screenshot attempts caught only the settled end state |

### The boot finding is worth more than the ticket

**The boot sequence may be largely invisible on a real cold start.** It is timed at 800ms, and the
process takes over 1.2s to reach first draw on this hardware, with main-thread contention during
class loading appearing to compress the whole transition once it frees. The settled end state is
verified; the animation is not.

This is not a defect in this ticket's code and it is not fixable by re-timing alone. It is a real
question about whether the boot theatre earns its complexity on this device, and it should be put
to Kevin rather than absorbed.

### Assumptions ledger

| Claim | Tag |
|---|---|
| Compile and tests green | **`tested`** - run directly by the orchestrator |
| 560dp content budget | **`on-device`** - uiautomator + dumpsys, bands sum to 806dp |
| APK installed is byte-identical to the built one | **`on-device`** - SHA-256 both sides |
| Bezel renders with arcs, breaks and ticks | **`on-device`** - screenshot |
| No overlap ever existed; the fix changed nothing visually | **`tested`** - pixel sampling and a global image diff |
| Bottom padding renders ~5.5dp vs 12dp spec | **`on-device`** - pixel sampling |
| App is not edge-to-edge | **`traced`** + **`on-device`** |
| `alarmCount`/`cursorSolid` defaults reproduce prior behaviour exactly | `traced` - code path is byte-identical at defaults |
| Cold start exceeds 1.2s to first draw | **`on-device`** - burst screenshots |
| The bezel trace animation reads as intended | **`reasoned`** - never captured mid-animation |
| Recomposition stays flat while the cursor blinks | **`reasoned`** - Layout Inspector not run. This is the owed check |
