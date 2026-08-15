package com.kevin.legion.ui.theme

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * The deck's motion vocabulary (cyberdeck-ui ticket 04, grilled with Kevin
 * 2026-08-07; superseded in part by mission-control ticket 07, 2026-08-14 -
 * see point 3 below). Constants only, plus the reduced-motion read - the boot
 * composable that spends the "boot" allotment is a later mission-control
 * build's job, not this file's; [DeckPanels.DeckMeter]/[DeckPanels.StatusLine]
 * and [DeckControls.DeckTextField]'s focused caret are this file's consumers
 * so far.
 *
 * The system, in full (cyberdeck-ui ticket 04 answer, point 3 amended by
 * mission-control ticket 07):
 * 1. Boot: cold start only, ~800ms, tap-to-skip. Warm returns from recents are
 *    instant - there is no "boot" on a warm resume, ever.
 * 2. One-shot draw-ins: meters fill and charts draw over ~350ms on screen
 *    entry. Never loop - a meter that has already drawn in does not redraw on
 *    every recomposition, only once per logical "entry".
 * 3. **Ambient motion is budgeted PER SURFACE, not exactly one element
 *    app-wide** (mission-control ticket 07, superseding cyberdeck-ui ticket
 *    04's original "exactly ONE element" claim). [DeckPanels.StatusLine]'s
 *    blinking block cursor is the SHELL's ambient element, but it is not the
 *    only one allowed to exist: a surface that defines its own ambient
 *    element (an alarm pulse, a focused [DeckControls.DeckTextField]'s own
 *    caret blink) gets to run it alongside the shell cursor. **Precedence is
 *    alarm pulse > surface ambient > shell cursor** - ticket 04's
 *    (`.scratch/mission-control/issues/04-alarm-without-hue.md`) alarm
 *    escalation answer, section 2: while an ALARM is present on a surface,
 *    that surface's own ambient element stops and the alarm pulse spends the
 *    budget instead. **The shell cursor YIELDS** to a surface that claims its
 *    own element - it does not additionally suppress the shell cursor itself
 *    on a plain (non-alarm) surface; the two are independent budgets that
 *    happen to share a precedence order only in the alarm case. **The uplink
 *    sweep itself is built in `ui/FleetScreen.kt`**
 *    ([com.kevin.legion.ui.FleetScreen]'s private `UplinkSweep`/`UplinkPane`)
 *    - period >= 4000ms, alpha/translation only,
 *    a leaf `Canvas` reading its animated `State` in the draw lambda, gated
 *    on [deckMotionEnabled] plus `FleetUiState.connected` plus a hardcoded
 *    `false` alarm placeholder (no alarm state source exists anywhere in the
 *    app yet). This file only carries the RULE, not the implementation.
 * 4. The theatre ration is now spent at exactly TWO moments: ingest commit and
 *    quarantine, each a later mission-control build ticket's job. **Boot was
 *    the third and was DROPPED 2026-08-14 by Kevin** - mission-control ticket
 *    14 measured cold process start on the target device at over 1.2s to first
 *    draw, against an 800ms sequence, so the animation was largely invisible in
 *    practice and did not earn its complexity. `BOOT_DURATION_MS` and the
 *    `BootOverlay` composable are both gone; this file reserves only
 *    [DRAW_IN_MS] so the remaining two tickets do not invent their own timing.
 * 5. Reduced-motion / animator scale 0 collapses every one-shot to instant and
 *    stops every ambient element, shell or surface - [deckMotionEnabled] is
 *    the single read every motion consumer in the deck must gate on.
 */

/** One-shot draw-in duration (meters filling, charts drawing on screen entry). Ticket 04 answer #2. */
const val DRAW_IN_MS: Int = 350

/**
 * Whether the deck's motion (one-shots, the ambient cursor, the rationed
 * theatre beats) should run at all. Reads `Settings.Global.ANIMATOR_DURATION_SCALE`
 * directly rather than `Settings.Global.getFloat(..., 1f)` with Compose's own
 * `LocalDensity`/animation-spec machinery, because that system setting is the
 * actual accessibility signal (Android's own "Remove animations" developer/
 * accessibility option sets it to 0) and it is the same knob the old head-unit
 * frame-clock rule used to read (CLAUDE.md §6: "Motion is NOT restricted" - but
 * scale-0 stays a real accessibility path, not a dead constraint, per ticket
 * 04 answer #5: "it must still render a complete UI").
 *
 * A scale of exactly `0f` means every one-shot in the deck (`DeckMeter` fills,
 * future chart draw-ins) must render its FINAL state immediately rather than
 * animating toward it, and [DeckPanels.StatusLine]'s cursor must stop
 * blinking and render solid. Any other scale (including values above `1f` from
 * Android's own "animator duration scale" developer setting) is treated as
 * motion-enabled - this app does not attempt to rescale its own durations by
 * that multiplier, only to detect the "off" case.
 *
 * `Settings.Global.getFloat` throws `SettingNotFoundException` if the key has
 * never been written, which happens on some OEM images - caught and treated
 * as "motion enabled" (the setting defaulting to 1x is the correct fallback,
 * not a crash).
 */
@Composable
fun deckMotionEnabled(): Boolean {
    val context = LocalContext.current
    val scale = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    } catch (_: Settings.SettingNotFoundException) {
        1f
    }
    return scale != 0f
}
