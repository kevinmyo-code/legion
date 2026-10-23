package com.kevin.legion.books

import android.graphics.Bitmap
import com.kevin.legion.data.local.BookRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two-page spreads: the display-to-source mapping, the fit choice, and the reader's use of both. */
class SpreadsTest {
    // 842x595 landscape scans and one portrait cover: pages 1..3 are spreads, page 0 is not.
    private val aspects = listOf(0.7f, 1.41f, 1.41f, 1.41f)

    private fun map(split: Boolean) = SpreadMap.of(aspects, split, aspects.size)

    @Test fun `split doubles only the landscape pages`() {
        assertEquals(7, map(true).count)
        assertEquals(4, map(false).count)
        assertTrue(map(true).hasSpreads)
        assertFalse(SpreadMap.of(listOf(0.77f, 0.77f), true, 2).hasSpreads)
    }

    @Test fun `display index and spot round trip`() {
        val m = map(true)
        val spots = listOf(Spot(0), Spot(1, 0), Spot(1, 1), Spot(2, 0), Spot(2, 1), Spot(3, 0), Spot(3, 1))
        spots.forEachIndexed { i, spot ->
            assertEquals(spot, m.spotOf(i))
            assertEquals(i, m.indexOf(spot))
        }
        assertEquals(Spot(3, 1), m.spotOf(99))
    }

    @Test fun `whole mode ignores the half`() {
        val m = map(false)
        assertEquals(Spot(2, 0), m.spotOf(2))
        assertEquals(2, m.indexOf(Spot(2, 1)))
        assertFalse(m.isHalf(2))
    }

    @Test fun `a square-ish page is not a spread`() {
        assertFalse(SpreadMap.of(listOf(1.1f), true, 1).hasSpreads)
        assertTrue(SpreadMap.of(listOf(1.3f), true, 1).hasSpreads)
    }

    @Test fun `a source that reports no sizes is never split`() {
        val m = SpreadMap.of(emptyList(), true, 5)
        assertEquals(5, m.count)
        assertFalse(m.hasSpreads)
    }

    @Test fun `fit mode per orientation and page shape`() {
        assertEquals(FitMode.PAGE, Fit.mode(landscapeScreen = false, half = false))
        assertEquals(FitMode.WIDTH, Fit.mode(landscapeScreen = false, half = true))
        assertEquals(FitMode.WIDTH, Fit.mode(landscapeScreen = true, half = false))
        assertEquals(FitMode.WIDTH, Fit.mode(landscapeScreen = true, half = true))
    }

    @Test fun `a saved right half carries a locator and an old position does not`() {
        assertEquals("half=1", Spot(4, 1).locator)
        assertNull(Spot(4, 0).locator)
        assertEquals(Spot(4, 0), Spot.saved(4, null))
        assertEquals(Spot(4, 1), Spot.saved(4, "half=1"))
    }

    // ---- the ViewModel over a spread book -------------------------------------------------

    private class Store(var row: BookRow?) : ReaderStore {
        override suspend fun row(documentId: String) = row

        override suspend fun savePosition(
            documentId: String, progress: Double, chapterLabel: String?, pageIndex: Int, pageCount: Int,
            locatorJson: String?, atMs: Long,
        ) {
            row = row?.copy(
                progress = progress, pageIndex = pageIndex, pageCount = pageCount, locatorJson = locatorJson,
            )
        }

        override suspend fun ensureRow(row: BookRow) = Unit

        override suspend fun bookmarks(documentId: String) = emptyList<com.kevin.legion.data.local.BookBookmark>()

        override suspend fun addBookmark(bookmark: com.kevin.legion.data.local.BookBookmark) = Unit

        override suspend fun deleteBookmark(id: Long) = Unit
    }

    private class Pages(override val pageCount: Int) : PageSource {
        override suspend fun render(index: Int, widthPx: Int): Bitmap = error("not rendered")

        override suspend fun aspects(): List<Float> = List(pageCount) { 1.41f }

        override fun close() = Unit
    }

    private fun row(pageIndex: Int = 0, locator: String? = null) = BookRow(
        documentId = "doc", fileName = "wwz.pdf", format = BookFormat.PDF.name, title = "WWZ", author = null,
        metadataRead = true, progress = 0.0, chapterLabel = null, pageIndex = pageIndex, pageCount = 10,
        locatorJson = locator, lastReadAtMs = null, firstSeenAtMs = 0L,
    )

    private fun model(store: Store, saved: MutableMap<String, Boolean> = mutableMapOf()) = ReaderViewModel(
        documentId = "doc", listenRequested = false, store = store,
        loader = { LoadedBook.Paged(Pages(10)) },
        readAloudFor = { _, _, _ -> error("unused") },
        scope = CoroutineScope(Dispatchers.Unconfined),
        readSpread = { saved[it] },
        writeSpread = { id, v -> saved[id] = v },
    )

    @Test fun `ten spreads count as twenty pages and percent follows`() {
        val vm = model(Store(row()))
        vm.setLandscape(false)
        assertEquals(20, vm.state.value.spread.count)
        vm.onPage(19)
        assertEquals("Page 20 of 20", vm.state.value.positionLabel)
        assertEquals("100%", vm.state.value.percentLabel)
        vm.onPage(10)
        assertEquals("52%", vm.state.value.percentLabel)
    }

    @Test fun `a position saved before splitting resumes at the left half of that page`() {
        val vm = model(Store(row(pageIndex = 4)))
        assertEquals(8, vm.state.value.pageIndex)
        assertEquals(Spot(4, 0), vm.state.value.spread.spotOf(vm.state.value.pageIndex))
    }

    @Test fun `a turn saves the source page and the half`() {
        val store = Store(row())
        val vm = model(store)
        vm.onPage(9)
        assertEquals(4, store.row!!.pageIndex)
        assertEquals(10, store.row!!.pageCount)
        assertEquals("half=1", store.row!!.locatorJson)
        vm.onPage(8)
        assertNull(store.row!!.locatorJson)
    }

    @Test fun `reopening resumes at the right half`() {
        val reopened = model(Store(row(pageIndex = 4, locator = "half=1")))
        assertEquals(9, reopened.state.value.pageIndex)
    }

    @Test fun `turning the phone keeps the reading spot`() {
        val vm = model(Store(row()))
        vm.onPage(9)
        vm.setLandscape(true)
        assertEquals(4, vm.state.value.pageIndex)
        assertEquals(10, vm.state.value.spread.count)
        vm.setLandscape(false)
        // The right half it was on, not the left: the spot survives the round trip.
        assertEquals(9, vm.state.value.pageIndex)
    }

    @Test fun `the setting is remembered per book and flips the mapping`() {
        val saved = mutableMapOf<String, Boolean>()
        val vm = model(Store(row()), saved)
        assertTrue(vm.state.value.spreadSplit)
        vm.onPage(7)
        vm.setSpreadSplit(false)
        assertEquals(false, saved["doc"])
        assertEquals(10, vm.state.value.spread.count)
        assertEquals(3, vm.state.value.pageIndex)
        assertFalse(model(Store(row()), saved).state.value.spreadSplit)
    }
}
