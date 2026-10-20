package com.kevin.legion.books

import android.graphics.Bitmap

/**
 * A place in a paged book: the source page and which half of it. [half] is 0 for a whole page or
 * the left half, 1 for the right half. This, not a display index, is what a saved position and a
 * bookmark mean, so rotating the phone or flipping the setting never loses the reader's place.
 */
data class Spot(val page: Int, val half: Int = 0) {
    /** The locator text a saved position carries for a right half; null for everything else. */
    val locator: String? get() = if (half == 1) RIGHT_HALF else null

    companion object {
        const val RIGHT_HALF = "half=1"

        /** A position saved before spreads existed has no locator and means the left half. */
        fun saved(page: Int, locator: String?): Spot = Spot(page, if (locator == RIGHT_HALF) 1 else 0)
    }
}

/**
 * Maps display pages (what the pager counts) onto source pages. A scanned two-up book holds two
 * printed pages per landscape PDF page; when [split] is on each such page is two display pages, left
 * then right. Pure, so the arithmetic that decides where a reader resumes is unit tested.
 */
class SpreadMap private constructor(private val wide: BooleanArray, val split: Boolean) {
    private val starts = IntArray(wide.size + 1).also { s ->
        for (p in wide.indices) s[p + 1] = s[p] + if (split && wide[p]) 2 else 1
    }

    val pageCount: Int get() = wide.size

    /** Display pages. */
    val count: Int get() = starts[wide.size]

    /** True when any source page is a landscape spread, so the setting is worth showing. */
    val hasSpreads: Boolean get() = wide.any { it }

    fun spotOf(index: Int): Spot {
        if (wide.isEmpty()) return Spot(0)
        val i = index.coerceIn(0, count - 1)
        val found = java.util.Arrays.binarySearch(starts, 0, wide.size, i)
        val page = if (found >= 0) found else -found - 2
        return Spot(page, i - starts[page])
    }

    fun indexOf(spot: Spot): Int {
        if (wide.isEmpty()) return 0
        val page = spot.page.coerceIn(0, wide.size - 1)
        val half = if (split && wide[page]) spot.half.coerceIn(0, 1) else 0
        return starts[page] + half
    }

    /** True when display page [index] is one half of a spread. */
    fun isHalf(index: Int): Boolean = split && wide.isNotEmpty() && wide[spotOf(index).page]

    companion object {
        /** A page this much wider than tall is two printed pages side by side. */
        const val SPREAD_RATIO = 1.2f

        /** [aspects] is width over height per source page; an empty list (comics) has no spreads. */
        fun of(aspects: List<Float>, split: Boolean, pageCount: Int): SpreadMap {
            val wide = BooleanArray(pageCount) { aspects.getOrNull(it)?.let { a -> a > SPREAD_RATIO } ?: false }
            return SpreadMap(wide, split)
        }
    }
}

/** How a page is fitted to the screen. */
enum class FitMode { PAGE, WIDTH }

object Fit {
    /**
     * Portrait keeps the whole page in view, except a split half, which fills the width. Landscape
     * fits the width and scrolls down the page: fitting the height of a 384dp-tall screen makes the
     * page smaller than it is in portrait, which is the opposite of why anyone turns the phone.
     */
    fun mode(landscapeScreen: Boolean, half: Boolean): FitMode =
        if (landscapeScreen || half) FitMode.WIDTH else FitMode.PAGE
}

/**
 * Page [spot] of this source, [widthPx] wide. A half is cut from a render twice as wide: the
 * platform renderer's matrix semantics cannot be checked off-device, and render-then-crop uses the
 * one call already proven here.
 */
suspend fun PageSource.renderSpot(spot: Spot, widthPx: Int, isHalf: Boolean): Bitmap {
    if (!isHalf) return render(spot.page, widthPx)
    val full = render(spot.page, widthPx * 2)
    val half = Bitmap.createBitmap(full, spot.half * widthPx, 0, widthPx.coerceAtMost(full.width / 2), full.height)
    if (half !== full) full.recycle()
    return half
}
