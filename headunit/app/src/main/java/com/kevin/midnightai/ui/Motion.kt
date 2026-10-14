package com.kevin.midnightai.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs
import kotlin.math.sin

/**
 * Frame-clock animation phase, immune to the system animator-duration-scale
 * setting. Cheap head units (and battery savers) ship with animations disabled,
 * which zeroes Compose's tween/infiniteTransition durations — everything built
 * on them silently freezes (the "reels don't spin / scanner doesn't sweep" bug).
 * [withFrameNanos] ticks regardless, so motion built on this always runs.
 *
 * Returns a 0..1 sawtooth over [periodMs]. When [running] is false the phase
 * holds its last value (a stopped reel stays where it stopped) and no frame
 * work happens at all.
 */
@Composable
internal fun animPhase(periodMs: Int, running: Boolean = true): Float = animPhaseState(periodMs, running).value

/**
 * [animPhase] as a [State], so a caller can read it in the DRAW phase (Canvas / drawBehind /
 * graphicsLayer) and redraw each frame without recomposing. Same frame clock, same holds.
 */
@Composable
internal fun animPhaseState(periodMs: Int, running: Boolean = true): State<Float> {
    val phase = remember { mutableStateOf(0f) }
    LaunchedEffect(periodMs, running) {
        if (!running) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    phase.value = (phase.value + (now - last) / 1_000_000f / periodMs) % 1f
                }
                last = now
            }
        }
    }
    return phase
}

/** Sawtooth phase to triangle wave (0-1-0), for back-and-forth motion. */
internal fun triangle(phase: Float): Float = 1f - abs(2f * phase - 1f)

/** Sawtooth phase to sine in 0..1, offset by [shift] cycles (for staggered bars). */
internal fun wave(phase: Float, shift: Float = 0f): Float =
    (sin((phase + shift) * 2.0 * Math.PI).toFloat() + 1f) / 2f

/**
 * One-shot 0..1 ramp over [durationMs] on the frame clock — the non-looping
 * sibling of [animPhase], for entrance reveals. Same animator-scale immunity.
 * Holds at 1 once finished; the frame loop stops.
 */
@Composable
internal fun rememberRamp(durationMs: Int): Float {
    var p by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (p < 1f) {
            withFrameNanos { now ->
                p = ((now - start) / 1_000_000f / durationMs).coerceAtMost(1f)
            }
        }
    }
    return p
}
