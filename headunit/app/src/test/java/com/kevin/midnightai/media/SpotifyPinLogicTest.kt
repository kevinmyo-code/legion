package com.kevin.midnightai.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyPinLogicTest {
    private fun p(id: String, name: String = id, cover: String? = null) =
        SpotifyPlaylist(id, name, "spotify:playlist:$id", cover)

    private fun ids(l: List<SpotifyPlaylist>) = l.map { it.id }

    @Test fun pinAppendsInOrder() {
        var pins = emptyList<SpotifyPlaylist>()
        pins = (SpotifyPinLogic.pin(pins, p("a")) as SpotifyPinLogic.PinResult.Ok).pins
        pins = (SpotifyPinLogic.pin(pins, p("b")) as SpotifyPinLogic.PinResult.Ok).pins
        assertEquals(listOf("a", "b"), ids(pins))
    }

    @Test fun repinIsNoDuplicate() {
        val pins = listOf(p("a"))
        assertEquals(pins, (SpotifyPinLogic.pin(pins, p("a")) as SpotifyPinLogic.PinResult.Ok).pins)
    }

    @Test fun capIsTenToFillTheTwoByFiveGrid() {
        assertEquals(10, SpotifyPinLogic.MAX_PINS)
        assertEquals(SpotifyPinLogic.MAX_PINS, SpotifyPlaylistLogic.MAX_PLAYLISTS)
    }

    @Test fun eleventhPinRefused() {
        val nine = (1..9).map { p("p$it") }
        val ten = (SpotifyPinLogic.pin(nine, p("p10")) as SpotifyPinLogic.PinResult.Ok).pins
        assertEquals(10, ten.size)
        assertTrue(SpotifyPinLogic.pin(ten, p("p11")) is SpotifyPinLogic.PinResult.Full)
        // Re-pinning an existing one at the cap is still fine (no-op), not "full".
        assertTrue(SpotifyPinLogic.pin(ten, p("p1")) is SpotifyPinLogic.PinResult.Ok)
    }

    @Test fun unpinRemovesOnlyThatOne() {
        assertEquals(listOf("a", "c"), ids(SpotifyPinLogic.unpin(listOf(p("a"), p("b"), p("c")), "b")))
        assertEquals(listOf("a"), ids(SpotifyPinLogic.unpin(listOf(p("a")), "zzz")))
    }

    @Test fun moveShiftsAndClamps() {
        val l = listOf(p("a"), p("b"), p("c"))
        assertEquals(listOf("b", "a", "c"), ids(SpotifyPinLogic.move(l, "b", -1)))
        assertEquals(listOf("a", "c", "b"), ids(SpotifyPinLogic.move(l, "b", +1)))
        assertEquals(listOf("a", "b", "c"), ids(SpotifyPinLogic.move(l, "a", -1)))
        assertEquals(listOf("a", "b", "c"), ids(SpotifyPinLogic.move(l, "c", +1)))
        assertEquals(listOf("a", "b", "c"), ids(SpotifyPinLogic.move(l, "nope", +1)))
    }

    @Test fun noPinsFallsBackToFirstTenOfLibrary() {
        val lib = (1..13).map { p("l$it") }
        assertEquals(ids(lib.take(10)), ids(SpotifyPinLogic.display(emptyList(), lib)))
    }

    @Test fun pinsWinInPinOrderNotLibraryOrder() {
        val lib = listOf(p("a"), p("b"), p("c"))
        assertEquals(listOf("c", "a"), ids(SpotifyPinLogic.display(listOf(p("c"), p("a")), lib)))
    }

    @Test fun pinFreshenedFromLibraryWhenPresent() {
        val pins = listOf(p("a", "Old", "old.jpg"))
        val lib = listOf(p("a", "Renamed", "new.jpg"))
        val shown = SpotifyPinLogic.display(pins, lib).single()
        assertEquals("Renamed", shown.name)
        assertEquals("new.jpg", shown.coverUrl)
    }

    @Test fun pinnedPlaylistMissingFromLibraryStaysWithCachedData() {
        val pins = listOf(p("gone", "Deleted One", "c.jpg"), p("a"))
        val shown = SpotifyPinLogic.display(pins, listOf(p("a")))
        assertEquals(listOf("gone", "a"), ids(shown))
        assertEquals("Deleted One", shown[0].name)
    }

    @Test fun pinsShownEvenWithEmptyLibraryOffline() {
        assertEquals(listOf("a"), ids(SpotifyPinLogic.display(listOf(p("a")), emptyList())))
    }
}
