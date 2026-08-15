# Ambient motion budget, one element per surface

Type: grilling
Status: resolved
Blocked by: 03

## Question

Which single element on each surface moves, and what does moving cost?

The charting decision raised `cyberdeck-ui` ticket 04's ration: **at most one continuously
animating element per visible surface, preferring low frequency**, with the three theatre moments
(boot, ingest commit, quarantine) surviving. This ticket spends that budget concretely.

**Read first:** `.scratch/cyberdeck-ui/issues/04-motion-vocabulary.md` and `ui/theme/DeckMotion.kt`.
Consult `compose-animations` and `compose-recomposition-performance` before deciding.

**Resolves:**

1. **The per-surface assignment.** One element each for HOME, BIO, LOG, FLEET, CRED, Pantry,
   Notes, Agenda, Lists, and each drilldown family - or an explicit "none", which is a good answer
   for a surface whose job is reading a chart.
2. **What "one element" means when a surface has an alarm.** Ticket 04 may spend motion on
   escalation; a surface cannot afford both. Decide precedence: alarm wins, presumably, and the
   ambient element stops.
3. **Frequency and amplitude ceilings.** A 1Hz cursor blink and a 20-second sweep are both "one
   element" and cost wildly different amounts. Set the ceiling.
4. **Containment.** Each animating element must not recompose its ancestors. Name the mechanism
   per element - deferred reads via lambda-form modifiers, `drawBehind`, or a leaf composable - and
   make it a build-ticket verification step, not a hope.
5. **Battery and the always-on case.** LEGION runs a foreground service. Decide what ambient motion
   does when the screen is on but the app is backgrounded or the phone is in a pocket, and whether
   anything animates in driving mode (ticket 08 has first claim on that answer).
6. **Reduced-motion.** Collapses to instant, per the shipped rule. Confirm each assigned element
   has a sane still state, and that nothing conveys information *only* by moving.
7. **The three theatre moments** under the bezel. Boot in particular changes - the frame can now
   draw itself in first. Decide whether it does.

**Constraint.** The shipped rule that motion never carries meaning alone still holds.

## Answer

Grilled with Kevin, 2026-08-14, after reading `ui/theme/DeckMotion.kt` and `StatusLine` in
`ui/common/DeckPanels.kt`, and consulting `compose-recomposition-performance`.

### 0. The finding: the budget was already spent

The charting decision raised the ration to "at most one continuously animating element per visible
surface". Reading the shipped code showed that ration is **already fully consumed by the shell**.

`StatusLine`'s blinking cursor lives in the **pinned** shell, and ticket 05 confirmed the status
line never scrolls away. So the cursor is a continuously animating element visible on **every**
surface at once. Any per-surface element would have been the second moving thing in view, not the
first - which is not what the raise intended.

### 1. The cursor yields to the surface

**Exactly one element moves in view at any moment**, but surfaces can still have character.

- A surface that defines its own ambient element: **the cursor renders solid and stops.**
- A surface that defines none: the cursor blinks, as shipped.

The shell defers to the content. This preserves the intent of the raise without ever putting two
ambient animations on screen together.

### 2. Assignment: FLEET only, and only while OBD is connected

| Surface | Ambient element |
|---|---|
| FLEET, OBD connected | **uplink sweep** |
| FLEET, OBD disconnected | none - cursor blinks |
| HOME, BIO, LOG, CRED, Pantry, Notes, Agenda, Lists | none - cursor blinks |
| Every drilldown | none - a drilldown exists to be read |

**The principle, and it is the load-bearing part of this ticket: an ambient element not tied to
genuinely live data is decoration.** The uplink sweeps because the car link is actually polling and
data is actually arriving. Disconnected, there is nothing to sweep for, so it stops. Motion here
means "something is arriving", which makes it information rather than atmosphere. Nothing else in
the app has a genuinely live datum behind it - everything else is logged history, and history does
not move.

This spends far less than the raise permits. That is deliberate.

### 3. Ceilings

| | |
|---|---|
| Cursor blink | 1Hz (the shipped `StatusLine` keyframes: 1000ms, 450 on / 500 off) |
| Uplink sweep | period >= 4s |
| Alarm pulse | ~0.5Hz (ticket 04) |
| **Ceiling** | **nothing ambient above 1Hz, ever** |
| Amplitude | alpha and translation only |

**Amplitude is capped at alpha and translation on purpose, not for taste:** those are draw-phase
properties. Anything animating a size, a bound, or a layout parameter re-runs layout every frame,
which is the expensive case and the one that drags ancestors in with it.

### 4. Containment: the mechanism, named per element

No ambient element may read its animation `State` during **composition**. Each names its mechanism:

| Element | Mechanism |
|---|---|
| Status cursor | `Modifier.graphicsLayer { alpha = cursorAlpha.value }` - the **lambda** overload, which defers the read to draw. **Already implemented correctly**; carry it over verbatim. |
| Uplink sweep | a leaf `Canvas` / `Modifier.drawBehind { }` reading the animation `State` inside the draw lambda |
| Alarm pulse | the same `graphicsLayer` lambda pattern, applied to the pill |

**This is a verification step for every build ticket that lands one, not an aspiration.** The check:
open Layout Inspector, animate the element, confirm recomposition counts stay flat for the element
and for every ancestor. `StatusLine`'s existing doc comment already states this pattern and why;
it is the reference implementation.

### 5. Precedence, one stack

**alarm pulse > surface ambient > shell cursor.** Exactly one runs at any moment.

This resolves ticket 04's handoff: when an alarm is present, the surface's ambient element stops
*and* the cursor stays solid. An alarming FLEET does not sweep.

### 6. Battery and lifecycle

The real lever here is **the data condition, not the lifecycle**: the sweep stops when OBD
disconnects, which is the case that would otherwise animate indefinitely in a pocket. A phone whose
screen is off is not producing frames, and the foreground service has no UI to animate, so neither
needs special handling.

No further lifecycle work is specified. If a later measurement shows otherwise, that is a new
finding, not a gap this ticket left.

### 7. Reduced motion

`deckMotionEnabled()` remains the **single read** every consumer gates on. Under scale 0:

| | |
|---|---|
| Cursor | solid |
| Uplink sweep | off |
| Alarm pulse | solid (ticket 04 - safe only because the static alarm treatment carries the meaning alone) |
| Boot | instant |
| One-shot draw-ins | `snap()` to final state |

**Nothing in the deck conveys information only by moving.** The sweep's absence is never the only
sign that OBD is disconnected - the status line says `OBD --` in words.

### 8. Boot, redesigned around the bezel

Cold start only, tap-to-skip, **still 800ms total** (`BOOT_DURATION_MS` unchanged).

| Window | What |
|---|---|
| 0ms | black |
| 0-250ms | bezel traces on from the corners |
| 250ms | registration ticks land |
| 250-450ms | status line types |
| 450-800ms | panels and meters draw in (`DRAW_IN_MS`) |

The bezel becomes the subject of the boot without buying more time. A warm resume from recents is
still instant, always - unchanged from the shipped rule.

The theatre ration stays at **three moments**: boot, ingest commit, quarantine. Count unchanged.

### 9. Handed on

- Every build ticket landing an ambient element inherits section 4's Layout Inspector check as a
  verification step.
- `DeckMotion.kt`'s doc comment needs rewriting: its "ambient motion is exactly ONE element
  app-wide" clause is superseded by section 1, and its ticket references point at `cyberdeck-ui`.

### Assumptions ledger

| Claim | Tag |
|---|---|
| The cursor is pinned and visible on every surface | `traced` - `StatusLine` in `DeckPanels.kt`, plus ticket 05's pinned-shell decision |
| The cursor's deferred-read implementation is already correct | `traced` - read the `graphicsLayer` lambda and its doc comment |
| `deckMotionEnabled()` is the single existing gate | `traced` - read `DeckMotion.kt` in full |
| `BOOT_DURATION_MS` 800, `DRAW_IN_MS` 350 | `traced` - constants in `DeckMotion.kt` |
| Alpha and translation are draw-phase, size is not | `reasoned` - standard Compose phase behaviour, consistent with `compose-recomposition-performance`; not measured here |
| A 4s sweep and a 1Hz ceiling read as calm | `reasoned` - **not rendered, not seen on a device** |
| No lifecycle work is needed beyond the data condition | `reasoned` - **not measured**; no battery profiling was done |
| Nothing else in the app has genuinely live data | `reasoned` - judgement across the nine surfaces, not an exhaustive audit |

## Revision 2026-08-14 (Kevin): boot is DROPPED

**Section 8 of the answer above is superseded. There is no boot sequence.**

Ticket 14 tried to capture the boot animation on the device and could not, which surfaced the
reason: **cold process start on the target phone exceeds 1.2 seconds to first draw, against an
800ms sequence.** The animation was largely invisible in practice, and main-thread contention
during class loading appears to compress whatever remains once the thread frees.

Put to Kevin as a question about whether the theatre earned its complexity. **Answer: it did not.**

What changed in code:

- `ui/BootOverlay.kt` deleted.
- `BOOT_DURATION_MS` removed from `ui/theme/DeckMotion.kt`.
- `DeckBezel`'s `traceProgress` / `ticksVisible` parameters removed, along with the interpolation
  they drove. Deleted rather than left defaulted, because a dead parameter whose doc points at a
  deleted file is exactly the rot this repo's conventions warn against.
- The `BootOverlay` references in `MainActivity` and `GlanceCardOverlay`'s doc comment updated.

**The theatre ration drops from three moments to two: ingest commit and quarantine.** Both remain
unbuilt and belong to their own surface tickets.

Everything else in this ticket stands. The cursor still yields, FLEET still owns the only ambient
element and only while OBD is connected, the 1Hz ceiling and the alpha/translation amplitude cap
are unchanged, and `deckMotionEnabled()` remains the single gate.

**Verified:** the bezel renders pixel-identically after the removal - 20,500 frame pixels and 2,590
tick pixels on HOME, before and after, sampled on-device. Compile and tests green.

`on-device`, `tested`.

## Built 2026-08-14

The uplink sweep landed in `d5e037c`, the last unbuilt decision on this map. A leaf `Canvas` reading
its animation `State` only inside the draw lambda, 4400ms period, alpha and translation only. It
runs only while OBD is connected, and `StatusLine`'s `cursorSolid` is wired so the cursor yields.
Precedence is alarm > sweep > cursor, with the alarm term hardcoded false and documented because no
alarm source exists yet.

**Could not be observed running** - the phone reported `OBD NO LINK` throughout. What was verified
is that the pane renders correctly with no sweep artifact while disconnected, which is the specified
behaviour. **The Layout Inspector flat-recomposition check remains OWED**; it cannot run from a
headless session, and it is the one thing this ticket asked for that has never been satisfied.

## Containment check, 2026-08-14: as far as it can be taken, and why

Ticket 07 asked for Layout Inspector confirming recomposition stays flat for the sweep and its
ancestors. Android Studio's Layout Inspector cannot be attached from an agent session, so the
underlying question was measured another way: a throwaway instrumented build with a
`SideEffect`-based recomposition counter on the sweep's leaf and every ancestor up to the surface
root, an on-screen readout (this device filters the app's own logcat), and `sweepActive` forced true
to bypass `OBD NO LINK`.

**Counts, 17 seconds apart - more than three sweep periods:**

| Composable | Sample 1 | Sample 2 |
|---|---|---|
| `FleetScreen` | 2 | 2 |
| `FleetContent` | 2 | 2 |
| `FleetListing` | 1 | 1 |
| `UplinkPane` | 1 | 1 |
| `UplinkSweep` | 1 | 1 |

Flat. Every piece of instrumentation was reverted, verified by an empty `git diff`, and the clean
APK reinstalled and hash-verified.

### The finding that matters more than the check

**The animation never ticked, because this device has animations globally disabled.**
`animator_duration_scale`, `transition_animation_scale` and `window_animation_scale` all read
**`0.0`**. Compose binds its `MotionDurationScale` to that setting, so every `infiniteRepeatable`
is frozen at its initial value. Two screenshots of the sweep band were **pixel-identical, exactly
zero difference in every channel**.

`deckMotionEnabled()` reads the same setting, so **the whole deck motion vocabulary is dormant on
Kevin's phone**: the status cursor renders solid and never blinks, `DeckMeter` fills snap rather
than filling, and the sweep would not run even with OBD connected. **The app is correct** - this is
precisely the reduced-motion path this ticket specified, working as designed. It is also why every
screenshot in this effort showed a solid cursor.

### Verdict

**Containment holds.** Three lines of evidence, honestly weighted:

1. **`traced`** - `FleetScreen`, `FleetContent`, `FleetListing` and `UplinkPane` were read in full;
   none reads `phase` or any of the sweep's `State`. The sole read is inside `UplinkSweep`'s
   `Canvas { }` draw lambda, which is what §4 requires.
2. **`on-device`** - the flat counts above are real, but **weak on their own**: with the value never
   changing, they prove only that nothing recomposes when nothing changes.
3. **`reasoned`** - Compose's snapshot scoping means a `State` read confined to a draw lambda
   invalidates that leaf's draw pass only, however often the value changes.

**What is still not proven: flatness under real motion.** Nothing suggests it would fail, and the
mechanism argues it cannot, but it has not been observed.

**This is no longer a tooling gap - it is a device-state gap.** Confirming it needs animations
enabled on the phone (`settings put global animator_duration_scale 1`, which this session lacks the
permission to set) and an OBD connection. Both are Kevin's to arrange, and at that point the check
is a ten-second look at a screen rather than an instrumented build.

## Containment CONFIRMED under real motion, 2026-08-15

The gap left above is closed. The check is satisfied in substance.

**The obstacle, and the way around it.** The phone denies `WRITE_SECURE_SETTINGS` to the shell over
wireless ADB, so `animator_duration_scale` cannot be raised from here - the earlier attempt and a
direct retry both returned `SecurityException`. That path is closed and should not be retried.

**`withFrameNanos` is not scaled by `MotionDurationScale`.** A throwaway build drove `phase` from a
`LaunchedEffect { while (true) { withFrameNanos { ... } } }` loop into a `mutableFloatStateOf`,
cycling over the production 4400ms, **with the read left exactly where it is - inside the
`Canvas { }` draw lambda**, since that is the thing under test. `sweepActive` was forced true to
bypass both `state.connected` and `deckMotionEnabled()`.

**Proof the value genuinely changed this time**, which is the precondition the earlier run failed:
diffing the UPLINK band across 15 seconds gives **31,904 of 410,400 pixels changed**, against the
earlier run's *exactly zero*. Two crops two seconds apart show the band visibly shifted. **This is
the first time the sweep has been observed rendering at all.**

**Recomposition counts, T0 vs T1, 17 seconds apart, under confirmed motion:**

| Composable | T0 | T1 |
|---|---|---|
| `FleetScreen` | 2 | 2 |
| `FleetContent` | 2 | 2 |
| `FleetListing` | 1 | 1 |
| `UplinkPane` | 1 | 1 |
| `UplinkSweep` | 1 | 1 |

**Flat. No ancestor recomposed. Containment holds under real motion.**

`UplinkSweep`'s own count staying at 1 is correct rather than a measurement gap: a `State` read
confined to a draw lambda invalidates that leaf's draw pass, so even the hosting composable does not
recompose. That is the same mechanism `StatusLine`'s cursor doc has cited since ticket 13, now
observed rather than argued.

**Reverted completely.** The production sweep keeps `infiniteRepeatable` and its correct
reduced-motion behaviour, which is not a bug and was not "fixed". `git diff` empty, compile and tests
green, and the phone reinstalled with the clean build, hash-verified `8910219d...`.

### What this leaves

Nothing on this ticket. The one caveat worth carrying forward is about the device, not the code:
**every `infiniteRepeatable` in this app is frozen on Kevin's phone** because all three animation
scales read `0.0`. The app is behaving correctly - that is the reduced-motion path working - but no
motion in the deck can be seen on that device until the setting is changed in Developer Options,
which only Kevin can do.

| Claim | Tag |
|---|---|
| Recomposition counts flat under confirmed real motion | **`on-device`** |
| The sweep genuinely animated | **`tested`** - 31,904 pixels changed against a prior baseline of exactly zero |
| Only the draw lambda reads the sweep's state | `traced` - all five bodies read |
| Tree reverted, phone on the clean build | **`tested`** - empty `git diff`, matching APK hash both sides |
| `withFrameNanos` is unscaled by `MotionDurationScale` | `reasoned`, and **consistent with the observed result** - the loop ticked on a device where `infiniteRepeatable` had been proven frozen |
