package com.kevin.legion.ui.typeset

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.theme.soft.SoftColors
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** What the orb is showing. Same five phases as the prototype's orb. */
enum class OrbPhase { IDLE, LISTENING, HEARD, THINKING, SPEAKING }

/**
 * The ink orb: a sphere of points, ported from the approved prototype (`typeset.js`, itself from
 * the recruiter demo's orb dock) to a Compose [Canvas]. Pure drawing: it owns no voice state. The
 * caller passes the [phase] and, optionally, a live input or output [level] (0 to 1).
 *
 * It turns slowly at rest, as the prototype does. With animations off in the system settings
 * (animator duration scale 0) it draws one still frame instead.
 */
@Composable
fun InkOrb(
    phase: OrbPhase,
    ink: Color,
    accent: Color,
    boost: Float,
    modifier: Modifier = Modifier,
    level: () -> Float = { 0f },
) {
    val sim = remember { OrbSim() }
    val currentPhase by rememberUpdatedState(phase)
    val currentLevel by rememberUpdatedState(level)
    var frame by remember { mutableLongStateOf(0L) }
    val context = LocalContext.current
    val still = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    LaunchedEffect(still) {
        if (still) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.1f)
                last = now
                sim.step(dt, currentPhase)
                frame = now
            }
        }
    }
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame // read so each frame redraws
        if (still) sim.step(0f, currentPhase)
        sim.draw(this, currentLevel().coerceIn(0f, 1f), ink, accent, boost)
    }
}

/**
 * [InkOrb] in the theme's own ink and accent (ADR 0081). Dots need more weight on paper, so the
 * day edition draws them 1.5 times stronger, as the prototype's `--orb-boost` did.
 */
@Composable
fun TypesetOrb(phase: OrbPhase, modifier: Modifier = Modifier, level: () -> Float = { 0f }) {
    val paper = SoftColors.ground.luminance() > 0.5f
    InkOrb(
        phase = phase,
        ink = SoftColors.text,
        accent = SoftColors.onAlert,
        boost = if (paper) 1.5f else 1f,
        modifier = modifier,
        level = level,
    )
}

/** The orb's simulation: point positions, eased mode parameters, time and spin. */
internal class OrbSim {
    private val pts = FloatArray(N * 4).also { a ->
        for (i in 0 until N) {
            val y = 1f - ((i + 0.5f) / N) * 2f
            val r = sqrt(1f - y * y)
            val th = i * GOLDEN
            val seed = sin(i * 12.9898) * 43758.5453
            a[i * 4] = cos(th) * r
            a[i * 4 + 1] = y
            a[i * 4 + 2] = sin(th) * r
            a[i * 4 + 3] = (seed - floor(seed)).toFloat()
        }
    }
    private val cur = MODES.getValue(OrbPhase.IDLE).copyOf()
    private var t = 0f
    private var rot = 0f

    /** The eased size factor (1 at rest): read by tests. */
    internal val sizeFactor: Float get() = cur[SIZE]

    /** The eased spin rate: read by tests. */
    internal val spin: Float get() = cur[SPIN]

    fun step(dt: Float, phase: OrbPhase) {
        val goal = MODES.getValue(phase)
        val k = if (dt == 0f) 1f else 1f - 0.02f.pow(dt)
        for (j in goal.indices) cur[j] += (goal[j] - cur[j]) * k
        t += dt
        rot += cur[SPIN] * dt
    }

    fun draw(scope: DrawScope, level: Float, ink: Color, accent: Color, boost: Float) = with(scope) {
        val side = min(size.width, size.height)
        val lv = level * cur[REACT]
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = side * 0.36f * cur[SIZE] * (1f + lv * 0.06f)
        val cr = cos(rot)
        val sr = sin(rot)
        val ct = cos(TILT)
        val st = sin(TILT)
        val dot = 1.7.dp.toPx() * (if (side < 130.dp.toPx()) 0.62f else 1f)
        val wobble = cur[WOBBLE]
        val ripple = cur[RIPPLE]
        val band = cur[BAND]
        val scatter = cur[SCATTER]
        val bright = cur[BRIGHT]
        var j = 0
        while (j < pts.size) {
            var x = pts[j]
            val y2 = pts[j + 1]
            var z = pts[j + 2]
            val sd = pts[j + 3]
            val n = sin(x * 3.1f + t * 0.9f) * sin(y2 * 3.7f - t * 0.7f) * sin(z * 2.9f + t * 0.5f)
            val rip = sin(asin(y2) * 7f - t * 7f) * ripple * (0.035f + lv * 0.09f)
            val rr = 1f + n * (wobble + lv * 0.14f * (1f - ripple)) + rip + scatter * (sd - 0.5f) * 1.1f
            val sweep = band * exp(-((y2 - sin(t * 1.6f) * 0.9f) * 4.2f).pow(2))
            x *= rr
            val yy = y2 * rr
            z *= rr
            val x1 = x * cr + z * sr
            val z1 = -x * sr + z * cr
            val y3 = yy * ct - z1 * st
            val z2 = yy * st + z1 * ct
            val depth = (z2 + 1.3f) / 2.6f
            val tw = 0.75f + 0.25f * sin(t * 2f + sd * 40f)
            val alpha = min(1f, ((0.12f + 0.88f * depth * depth) * bright * tw + sweep * 0.6f) * boost)
            val hot = min(1f, sweep + if (rip > 0f) ripple * lv * 0.9f else 0f)
            val color = Color(
                red = ink.red + (accent.red - ink.red) * hot,
                green = ink.green + (accent.green - ink.green) * hot,
                blue = ink.blue + (accent.blue - ink.blue) * hot,
                alpha = alpha.coerceIn(0f, 1f),
            )
            val sq = dot * (0.55f + depth * 0.9f)
            drawRect(color, Offset(cx + x1 * radius - sq / 2f, cy + y3 * radius - sq / 2f), Size(sq, sq))
            j += 4
        }
    }

    companion object {
        const val N = 900
        private const val GOLDEN = 2.399963f
        private const val TILT = 0.38f
        private const val SPIN = 0
        private const val WOBBLE = 1
        private const val RIPPLE = 2
        private const val BAND = 3
        private const val SCATTER = 4
        private const val BRIGHT = 5
        private const val SIZE = 6
        private const val REACT = 7

        //                                         spin  wobble ripple band scatter bright size react
        private val MODES = mapOf(
            OrbPhase.IDLE to floatArrayOf(0.10f, 0.05f, 0f, 0f, 0f, 0.75f, 1.00f, 0f),
            OrbPhase.LISTENING to floatArrayOf(0.16f, 0.06f, 0f, 0f, 0f, 0.85f, 1.00f, 1f),
            OrbPhase.HEARD to floatArrayOf(0.16f, 0.06f, 0f, 0f, 0f, 0.90f, 1.00f, 1f),
            OrbPhase.THINKING to floatArrayOf(0.85f, 0.04f, 0f, 1f, 0f, 0.75f, 0.92f, 0f),
            OrbPhase.SPEAKING to floatArrayOf(0.22f, 0.04f, 1f, 0f, 0f, 1.00f, 1.04f, 1f),
        )
    }
}
