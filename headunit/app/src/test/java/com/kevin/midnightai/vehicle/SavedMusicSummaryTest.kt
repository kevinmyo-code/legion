package com.kevin.midnightai.vehicle

import com.kevin.midnightai.data.local.LibraryTrack
import com.kevin.midnightai.data.local.Mixtape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CarToolbelt.formatSavedMusic] - the pure formatting half of
 * `savedMusicSummary`, split out so it doesn't need a Room database to verify.
 */
class SavedMusicSummaryTest {

    private fun track(title: String, artist: String, album: String = "") = LibraryTrack(
        filePath = "/library/$title.mp3",
        title = title,
        artist = artist,
        album = album,
        durationMs = 180_000,
        sourceName = "$title.mp3",
        addedAt = 0L,
    )

    private fun tape(name: String) = Mixtape(name = name, createdAt = 0L)

    @Test fun empty_library_and_no_tapes() {
        assertEquals(
            "Nothing saved on the head unit yet.",
            CarToolbelt.formatSavedMusic(emptyList(), emptyList()),
        )
    }

    @Test fun tracks_and_tapes_present() {
        val tracks = listOf(
            track("Plastic Love", "Mariya Takeuchi", "Variety"),
            track("Stay With Me", "Miki Matsubara", "Pocket Park"),
            track("Sparkle", "Mariya Takeuchi", "Variety"),
        )
        val tapes = listOf(tape("CITY POP NIGHTS"), tape("Cruise Mix"))

        val summary = CarToolbelt.formatSavedMusic(tracks, tapes)

        assertTrue(summary.contains("3 track(s) saved"))
        assertTrue(summary.contains("2 artist(s)"))
        assertTrue(summary.contains("Mariya Takeuchi"))
        assertTrue(summary.contains("Miki Matsubara"))
        assertTrue(summary.contains("Variety"))
        assertTrue(summary.contains("Pocket Park"))
        assertTrue(summary.contains("CITY POP NIGHTS"))
        assertTrue(summary.contains("Cruise Mix"))
    }

    @Test fun tracks_with_no_tapes() {
        val summary = CarToolbelt.formatSavedMusic(listOf(track("Solo Track", "Some Artist")), emptyList())
        assertTrue(summary.contains("No mixtapes assembled yet."))
    }

    @Test fun tapes_with_no_tracks() {
        val summary = CarToolbelt.formatSavedMusic(emptyList(), listOf(tape("Empty Shelf")))
        assertTrue(summary.contains("No individual tracks saved yet."))
        assertTrue(summary.contains("Empty Shelf"))
    }

    @Test fun blank_artist_falls_back_to_unknown() {
        val summary = CarToolbelt.formatSavedMusic(listOf(track("Mystery Track", "")), emptyList())
        assertTrue(summary.contains("Unknown artist"))
    }
}
