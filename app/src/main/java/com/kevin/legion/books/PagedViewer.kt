@file:Suppress("FunctionNaming") // @Composable convention is PascalCase; detekt's rule does not know it.

package com.kevin.legion.books

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val EDGE_TAP_FRACTION = 0.25f

/**
 * The PDF and comic page view (ADR 0061): a pager of pages, each pinch-zoomable. At 1x a swipe or a
 * tap on the left or right quarter turns the page; zoomed in, a drag pans the page instead and the
 * pager stays put, so a page can be read at the edges without turning it by accident.
 */
@Composable
fun PagedViewer(
    source: PageSource,
    startPage: Int,
    palette: ReaderPalette,
    pageRequest: Int?,
    onPage: (Int) -> Unit,
    onPageRequestHandled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pager = rememberPagerState(initialPage = startPage.coerceIn(0, (source.pageCount - 1).coerceAtLeast(0))) {
        source.pageCount
    }
    var zoomed by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { onPage(it) } }
    LaunchedEffect(pageRequest) {
        if (pageRequest != null) {
            pager.scrollToPage(pageRequest.coerceIn(0, source.pageCount - 1))
            onPageRequestHandled()
        }
    }

    HorizontalPager(
        state = pager,
        userScrollEnabled = !zoomed,
        beyondViewportPageCount = 1,
        modifier = modifier.fillMaxSize().background(palette.page),
    ) { index ->
        ZoomablePage(
            source = source,
            index = index,
            palette = palette,
            onZoomed = { zoomed = it },
            onTapEdge = { forward ->
                scope.launch {
                    val target = (pager.currentPage + if (forward) 1 else -1).coerceIn(0, source.pageCount - 1)
                    pager.animateScrollToPage(target)
                }
            },
        )
    }
}

@Composable
private fun ZoomablePage(
    source: PageSource,
    index: Int,
    palette: ReaderPalette,
    onZoomed: (Boolean) -> Unit,
    onTapEdge: (forward: Boolean) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        val bitmap by produceState<Bitmap?>(null, index, widthPx) {
            value = runCatching { source.render(index, widthPx) }.getOrNull()
        }
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val maxPanX = (widthPx * (scale - 1f)) / 2f
        val maxPanY = (with(LocalDensity.current) { maxHeight.toPx() } * (scale - 1f)) / 2f
        fun clamp(o: Offset) = Offset(o.x.coerceIn(-maxPanX, maxPanX), o.y.coerceIn(-maxPanY, maxPanY))
        LaunchedEffect(scale) { onZoomed(scale > 1f) }
        // A page scrolled away from must not stay zoomed when it comes back.
        LaunchedEffect(index) { scale = 1f; offset = Offset.Zero }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = DOUBLE_TAP_ZOOM
                            }
                        },
                        onTap = { at ->
                            if (scale == 1f) {
                                when {
                                    at.x < size.width * EDGE_TAP_FRACTION -> onTapEdge(false)
                                    at.x > size.width * (1f - EDGE_TAP_FRACTION) -> onTapEdge(true)
                                }
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            // Two fingers always pinch; one finger pans only once zoomed in, else the pager keeps it.
                            if (event.changes.size >= 2 || scale > 1f) {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                                offset = if (scale == 1f) Offset.Zero else clamp(offset + pan)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
        ) {
            val shown = bitmap
            if (shown != null) {
                Image(
                    bitmap = shown.asImageBitmap(),
                    contentDescription = "Page ${index + 1}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    "Page ${index + 1}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            }
        }
    }
}
