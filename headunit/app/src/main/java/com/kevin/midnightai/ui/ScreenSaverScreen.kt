package com.kevin.midnightai.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaType
import kotlinx.coroutines.delay
import java.util.Locale

// The lower-third caption-bar convention: on-media captions use a cream bar with
// dark ink (matching the city-pop stream-frame reference), NOT floating neon text
// over imagery. Reused for the polaroid card border so the frame reads as one set.
private val LowerThirdCream = Color(0xFFF0E6CC)
private val LowerThirdInk = Color(0xFF1A1410)

/**
 * Ambient screen saver: after a (configurable) idle stretch the Cruise screen
 * dissolves into the driver's own city-pop wallpaper, full-bleed like a lo-fi
 * night-drive stream frame — a slow Ken Burns drift over the art, the companion
 * propped in the corner like a photo card, gently breathing, and the current
 * track on a cream title bar (per the "Velvet Night Drive" reference). Tap
 * anywhere to come back.
 */
@Composable
internal fun ScreenSaver(
    context: Context,
    wallpaper: Bitmap?,
    avatar: Bitmap?,
    trackLine: String?,
    caption: String,
    onExit: () -> Unit,
) {
    // A gentle breathing bob for the companion card, on the frame clock so it
    // runs even on head units that disable system animations. The wallpaper's
    // Ken Burns drift, light sheen, and drifting specks live in AnimatedWallpaper.
    val bob = triangle(animPhase(6_400)) * 6f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AriaColors.Background)
            .clickable { onExit() },
    ) {
        if (wallpaper != null) {
            // Near-bare scrim: this is a parked/idle surface, let the art breathe.
            AnimatedWallpaper(wallpaper, Modifier.fillMaxSize(), scrimAlpha = 0.12f)
        }

        // Clock, small and quiet, top-right.
        // The app's own clock and zone, never the head unit's (trusted clock, 2026-10-13).
        var now by remember { mutableStateOf(com.kevin.midnightai.time.TrustedClock.nowMs()) }
        LaunchedEffect(Unit) {
            while (true) { now = com.kevin.midnightai.time.TrustedClock.nowMs(); delay(10_000) }
        }
        val time = remember(now / 60_000) { com.kevin.midnightai.time.TrustedClock.formatClock(context, now) }
        Text(
            time,
            style = AriaType.readout.copy(fontSize = 22.sp),
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(18.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )

        // The companion, propped in the corner like a photo card, breathing.
        if (avatar != null) {
            Image(
                bitmap = avatar.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 22.dp, bottom = 34.dp)
                    .offset(y = (-bob).dp)
                    .rotate(-2.5f)
                    .width(150.dp)
                    .height(186.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(3.dp, LowerThirdCream, RoundedCornerShape(10.dp)),
            )
        }

        // Track title bar, bottom-left, cream strip like the reference frame.
        // A live caption (Zero speaking proactively) takes over the bar.
        // COMPACT (phone portrait): the avatar card sits bottom-END and the
        // caption bar sits bottom-START, both near the bottom edge - on a
        // narrow phone a wide (460dp) bar runs under the card. Capping it to
        // half the screen keeps the two from overlapping; EXPANDED (head
        // unit, plenty of width) keeps today's 460dp cap unchanged.
        val barText = caption.ifBlank { trackLine.orEmpty() }
        if (barText.isNotBlank()) {
            val compact = LocalSizeClass.current == SizeClass.COMPACT
            Column(modifier = Modifier.align(Alignment.BottomStart).padding(start = 0.dp, bottom = 40.dp)) {
                Text(
                    barText,
                    style = AriaType.readout.copy(fontSize = 16.sp),
                    color = LowerThirdInk,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = (if (compact) Modifier.fillMaxWidth(0.55f) else Modifier.widthIn(max = 460.dp))
                        .clip(RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                        .background(LowerThirdCream.copy(alpha = 0.92f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}
