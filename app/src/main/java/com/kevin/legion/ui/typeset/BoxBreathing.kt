package com.kevin.legion.ui.typeset

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.TypesetType

/** The four sides of the box, 4 seconds each (Kevin, 2026-11-10). */
enum class BreathPhase(val words: String) {
    IN("Breathe in"),
    HOLD_FULL("Hold"),
    OUT("Breathe out"),
    HOLD_EMPTY("Hold"),
}

/** Where in the cycle [elapsedMs] falls: the phase, the count (1 to 4) and how far along the side (0 to 1). */
data class BreathState(val phase: BreathPhase, val count: Int, val progress: Float)

/**
 * Box breathing, pure (ADR 0081, Kevin 2026-11-10): "when i feel like scrolling ... a button to
 * press". Opened only by a tap (home's contents line, or "Breathe instead" on a blocked feed app).
 * It keeps nothing: no history, no streak, no count of sessions, no reminder (CLAUDE.md section 7's
 * compulsion test). Pinned by `BoxBreathTest`.
 */
object BoxBreath {
    const val PHASE_MS = 4_000L
    private const val SECOND_MS = 1_000L
    private val CYCLE_MS = PHASE_MS * BreathPhase.entries.size

    fun at(elapsedMs: Long): BreathState {
        val inCycle = Math.floorMod(elapsedMs, CYCLE_MS)
        val phase = BreathPhase.entries[(inCycle / PHASE_MS).toInt()]
        val inPhase = inCycle % PHASE_MS
        return BreathState(phase, (inPhase / SECOND_MS).toInt() + 1, inPhase.toFloat() / PHASE_MS)
    }

    /**
     * The dot on the square, as fractions of its side (0,0 top left): up the left side breathing in,
     * across the top holding, down the right side breathing out, back along the bottom holding.
     */
    fun dot(s: BreathState): Pair<Float, Float> = when (s.phase) {
        BreathPhase.IN -> 0f to 1f - s.progress
        BreathPhase.HOLD_FULL -> s.progress to 0f
        BreathPhase.OUT -> 1f to s.progress
        BreathPhase.HOLD_EMPTY -> 1f - s.progress to 1f
    }

    /** Reduced motion: no moving dot; the square's fill fades up breathing in and down breathing out. */
    fun fill(s: BreathState): Float = when (s.phase) {
        BreathPhase.IN -> s.progress
        BreathPhase.HOLD_FULL -> 1f
        BreathPhase.OUT -> 1f - s.progress
        BreathPhase.HOLD_EMPTY -> 0f
    }
}

/**
 * The full-screen box: runs the clock, keeps the screen on while it runs, and ends on a tap anywhere
 * or Back. With the system's animations off (animator duration scale 0) it draws the fading square
 * instead of the travelling dot.
 */
@Composable
fun BoxBreathingScreen(onEnd: () -> Unit) {
    val context = LocalContext.current
    val reducedMotion = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { now -> elapsedMs = (now - start) / NANOS_PER_MS }
    }
    val view = LocalView.current
    DisposableEffect(view) {
        val before = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = before }
    }
    BackHandler(onBack = onEnd)
    BoxBreathingContent(BoxBreath.at(elapsedMs), reducedMotion, onEnd)
}

/** Stateless: one moment of the box, for the screen and for renders. */
@Composable
fun BoxBreathingContent(
    state: BreathState,
    reducedMotion: Boolean,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = SoftColors.text
    val rule = SoftColors.barRule
    Box(
        modifier
            .fillMaxSize()
            .background(SoftColors.ground)
            .clickable(role = Role.Button, onClickLabel = "End breathing", onClick = onEnd),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(
                state.phase.words,
                style = TypesetType.lead,
                color = SoftColors.text,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Canvas(Modifier.size(BoxSide)) {
                val side = size.minDimension
                val stroke = 1.5.dp.toPx()
                if (reducedMotion) {
                    drawRect(ink.copy(alpha = BoxBreath.fill(state) * MAX_FILL), size = Size(side, side))
                    drawRect(ink, size = Size(side, side), style = Stroke(stroke))
                } else {
                    drawRect(rule, size = Size(side, side), style = Stroke(stroke))
                    val (fx, fy) = BoxBreath.dot(state)
                    drawCircle(ink, radius = DotRadius.toPx(), center = Offset(fx * side, fy * side))
                }
            }
            Text(state.count.toString(), style = TypesetType.hero, color = SoftColors.text)
            Text("Tap anywhere to end", style = TypesetType.smallItalic, color = SoftColors.text2)
        }
    }
}

private const val NANOS_PER_MS = 1_000_000L
private const val MAX_FILL = 0.85f
private val BoxSide = 220.dp
private val DotRadius = 7.dp
