package com.kevin.midnightai.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import com.kevin.midnightai.ui.theme.AriaColors
import kotlin.math.sin

private const val PI2 = 6.2831855f

/**
 * One drifting light speck (a distant city light / dust mote). [x] is the base
 * horizontal position (0..1 of width); [phase] staggers it in the drift cycle;
 * [radiusFrac] scales the dot to the surface height; [swayPx] is its gentle
 * horizontal wander as it rises.
 */
private class Speck(
    val x: Float,
    val phase: Float,
    val radiusFrac: Float,
    val alpha: Float,
    val swayPx: Float,
)

// A fixed, deterministic scatter so the field is stable across recompositions
// (kept small — ~14 dots — so the per-frame Canvas stays trivially cheap on a
// bargain head unit).
private val SPECKS = listOf(
    Speck(0.08f, 0.00f, 0.006f, 0.90f, 12f),
    Speck(0.19f, 0.37f, 0.004f, 0.70f, 8f),
    Speck(0.28f, 0.72f, 0.008f, 1.00f, 16f),
    Speck(0.41f, 0.15f, 0.005f, 0.60f, 10f),
    Speck(0.52f, 0.55f, 0.007f, 0.85f, 14f),
    Speck(0.63f, 0.90f, 0.004f, 0.70f, 9f),
    Speck(0.71f, 0.30f, 0.006f, 0.80f, 12f),
    Speck(0.80f, 0.63f, 0.005f, 0.65f, 8f),
    Speck(0.88f, 0.10f, 0.008f, 1.00f, 15f),
    Speck(0.94f, 0.48f, 0.004f, 0.60f, 7f),
    Speck(0.14f, 0.83f, 0.005f, 0.70f, 11f),
    Speck(0.35f, 0.95f, 0.006f, 0.80f, 13f),
    Speck(0.58f, 0.22f, 0.004f, 0.60f, 9f),
    Speck(0.76f, 0.68f, 0.007f, 0.90f, 14f),
)

/**
 * The shared "lo-fi city-pop playlist" backdrop: the driver's wallpaper with a
 * slow Ken Burns drift, a soft diagonal light sheen sweeping across like sun
 * glinting off the frame, and a field of slowly rising light specks. All motion
 * runs on the frame clock ([animPhase]) so it keeps moving even on head units
 * that pin the system animator scale to zero (where tween/infiniteTransition
 * silently freeze). Draw cost per frame is one Ken Burns transform, one gradient
 * rect, and ~14 circles — cheap enough to leave running on the Cruise screen.
 *
 * [scrimAlpha] darkens the art for legibility of whatever is layered on top
 * (0.55 behind the Cruise HUD, ~0.12 for the near-bare screen saver).
 */
@Composable
internal fun AnimatedWallpaper(
    wallpaper: Bitmap,
    modifier: Modifier = Modifier,
    scrimAlpha: Float = 0.55f,
) {
    val t = triangle(animPhase(80_000))   // ~80s out-and-back Ken Burns
    val sheen = animPhase(14_000)         // light band sweep
    val drift = animPhase(30_000)         // speck rise

    Box(modifier) {
        Image(
            bitmap = wallpaper.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.06f + 0.05f * t
                    scaleY = 1.06f + 0.05f * t
                    translationX = (t - 0.5f) * 36f
                    translationY = (0.5f - t) * 20f
                },
        )
        if (scrimAlpha > 0f) {
            Box(Modifier.fillMaxSize().background(AriaColors.Background.copy(alpha = scrimAlpha)))
        }
        // Sheen + specks ride above the scrim so the glints stay visible.
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Diagonal light sheen: a soft highlight band that sweeps L→R and off.
            val band = w * 0.5f
            val cx = sheen * (w + band) - band
            drawRect(
                brush = Brush.linearGradient(
                    0f to Color.Transparent,
                    0.5f to Color.White.copy(alpha = 0.05f),
                    1f to Color.Transparent,
                    start = Offset(cx, 0f),
                    end = Offset(cx + band, h),
                ),
            )

            // Rising light specks, each fading in and back out over its climb.
            for (s in SPECKS) {
                val p = (drift + s.phase) % 1f
                val y = (1f - p) * h
                val x = s.x * w + sin((p * 2f + s.phase) * PI2) * s.swayPx
                val a = s.alpha * (0.12f + 0.42f * triangle(p))
                drawCircle(
                    color = Color.White.copy(alpha = a),
                    radius = s.radiusFrac * h,
                    center = Offset(x, y),
                )
            }
        }
    }
}
