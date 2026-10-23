package com.kevin.legion.ui.dumb

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kevin.legion.dumb.rain.CodeRainScene
import com.kevin.legion.dumb.rain.RainTuning
import com.kevin.legion.dumb.rain.animationsOff

private const val NANOS_PER_SECOND = 1_000_000_000f
private const val NANOS_PER_MS = 1_000_000L
private const val MAX_STEP_SECONDS = 0.1f

/**
 * The code rain behind dumb mode's home list (ADR 0062), drawn on a Compose Canvas by the shared
 * [CodeRainScene]. **It runs only while the screen is RESUMED**: `repeatOnLifecycle` cancels the
 * frame loop the moment the app is paused or the screen turns off and restarts it on return, so it
 * costs no battery in the background. With system animations off it draws one still frame.
 */
@Composable
fun CodeRainBackground(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scene = remember { CodeRainScene() }
    var frame by remember { mutableLongStateOf(0L) }
    val owner = LocalLifecycleOwner.current

    LaunchedEffect(owner) {
        if (animationsOff(context)) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var last = 0L
            while (true) {
                androidx.compose.runtime.withFrameNanos { now ->
                    if (last == 0L) last = now
                    if (now - last >= RainTuning.FRAME_MS * NANOS_PER_MS) {
                        scene.advance(((now - last) / NANOS_PER_SECOND).coerceAtMost(MAX_STEP_SECONDS))
                        last = now
                        frame++
                    }
                }
            }
        }
    }

    Canvas(modifier) {
        // Reading [frame] here is what makes each advance redraw this Canvas and nothing else.
        check(frame >= 0L)
        scene.resize(size.width.toInt(), size.height.toInt(), density)
        drawIntoCanvas { scene.draw(it.nativeCanvas) }
    }
}
