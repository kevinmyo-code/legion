package com.kevin.legion.books

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Ticket books/06's Compose test: a tap in the middle third shows or hides the reader's bars, and
 * the outer quarters still turn pages. Drives the real [PagedViewer] with a page source that cannot
 * render, so each page draws its "Page N" label instead of a bitmap.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PagedViewerTapTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private class NoRender(override val pageCount: Int) : PageSource {
        override suspend fun render(index: Int, widthPx: Int): Bitmap = error("not rendered in this test")

        override fun close() = Unit
    }

    private fun show(start: Int, onPage: (Int) -> Unit, onTapCenter: () -> Unit) {
        composeTestRule.setContent {
            PagedViewer(
                source = NoRender(3),
                spread = SpreadMap.of(emptyList(), split = false, pageCount = 3),
                landscape = false,
                startPage = start,
                palette = ReaderPalette.of(ReaderTheme.DAY),
                pageRequest = null,
                onPage = onPage,
                onPageRequestHandled = {},
                onTapCenter = onTapCenter,
            )
        }
    }

    private fun tapAt(fraction: Float) {
        composeTestRule.onRoot().performTouchInput { click(Offset(width * fraction, height * 0.5f)) }
        composeTestRule.mainClock.advanceTimeBy(DOUBLE_TAP_WAIT_MS)
        composeTestRule.waitForIdle()
    }

    @Test fun `a tap in the middle third asks for the bars and turns no page`() {
        val pages = mutableListOf<Int>()
        var centre = 0
        show(start = 1, onPage = { pages += it }, onTapCenter = { centre++ })
        tapAt(0.5f)
        assertEquals(1, centre)
        assertEquals("the page did not change", listOf(1), pages.distinct())
    }

    @Test fun `a tap on the left edge still turns to the previous page and leaves the bars alone`() {
        val pages = mutableListOf<Int>()
        var centre = 0
        show(start = 1, onPage = { pages += it }, onTapCenter = { centre++ })
        tapAt(0.08f)
        assertEquals(0, centre)
        assertEquals(0, pages.last())
    }

    @Test fun `a tap on the right edge still turns to the next page`() {
        val pages = mutableListOf<Int>()
        var centre = 0
        show(start = 1, onPage = { pages += it }, onTapCenter = { centre++ })
        tapAt(0.92f)
        assertEquals(0, centre)
        assertEquals(2, pages.last())
        assertTrue(pages.contains(1))
    }

    private companion object {
        const val DOUBLE_TAP_WAIT_MS = 600L
    }
}
