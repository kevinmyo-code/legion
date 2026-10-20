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
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.unit.IntSize
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
        val zoom = remember { ZoomState() }
        LaunchedEffect(zoom.scale) { onZoomed(zoom.scale > 1f) }
        // A page scrolled away from must not stay zoomed when it comes back.
        LaunchedEffect(index) { zoom.reset() }
        Box(
            Modifier
                .fillMaxSize()
                .zoomable(zoom, onTapEdge)
                .graphicsLayer(
                    scaleX = zoom.scale,
                    scaleY = zoom.scale,
                    translationX = zoom.offset.x,
                    translationY = zoom.offset.y,
                ),
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

/** One page's pinch state: scale 1 to [MAX_ZOOM], and a pan that never leaves the page. */
@Stable
private class ZoomState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    fun toggle() {
        if (scale > 1f) reset() else scale = DOUBLE_TAP_ZOOM
    }

    fun apply(zoom: Float, pan: Offset, size: IntSize) {
        scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
        val maxX = size.width * (scale - 1f) / 2f
        val maxY = size.height * (scale - 1f) / 2f
        val next = offset + pan
        offset = if (scale == 1f) Offset.Zero else Offset(next.x.coerceIn(-maxX, maxX), next.y.coerceIn(-maxY, maxY))
    }
}

private fun Modifier.zoomable(zoom: ZoomState, onTapEdge: (forward: Boolean) -> Unit): Modifier = this
    .pointerInput(Unit) {
        detectTapGestures(
            onDoubleTap = { zoom.toggle() },
            onTap = { at ->
                if (zoom.scale == 1f) {
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
                if (event.changes.size >= 2 || zoom.scale > 1f) {
                    zoom.apply(event.calculateZoom(), event.calculatePan(), size)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }
