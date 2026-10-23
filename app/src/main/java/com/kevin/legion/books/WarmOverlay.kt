@file:Suppress("FunctionNaming") // @Composable convention is PascalCase; detekt's rule does not know it.

package com.kevin.legion.books

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import com.kevin.legion.location.LocationController
import kotlinx.coroutines.delay

private const val CLOCK_TICK_MS = 60_000L

/**
 * The warm filter, drawn over the whole reader (ticket books/06): an amber multiply layer, whatever
 * the format or theme. It only draws, it never takes a touch, so every tap reaches the page below.
 * This is LEGION's own overlay; it does not tint any other app. Auto reads the last location fix
 * [LocationController] already holds and re-checks the clock once a minute.
 */
@Composable
fun WarmOverlay(settings: ReaderSettings, modifier: Modifier = Modifier) {
    val fix by LocationController.state.collectAsState()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(CLOCK_TICK_MS)
            value = System.currentTimeMillis()
        }
    }
    val alpha = WarmFilter.effectiveAlpha(settings, now, fix?.latitude, fix?.longitude)
    if (alpha > 0f) {
        Box(
            modifier.fillMaxSize().drawBehind {
                drawRect(WarmFilter.TINT.copy(alpha = alpha), blendMode = BlendMode.Multiply)
            },
        )
    }
}
