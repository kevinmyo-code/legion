package com.kevin.legion.service

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.MusicPlayHistoryEntry
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Ticket 09 (`.scratch/spotify-voice/issues/09-history-uri.md`): `legion_history` rows must say,
 * in words, which ones can be replayed and which can only be named - ADR 0031's outcome-asserting
 * clause applied to a READ path. Same Robolectric-plus-Room shape as [LiveToolboxCurrencyTest].
 */
@RunWith(RobolectricTestRunner::class)
class LiveToolboxHistoryUriTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    private suspend fun seed(title: String, artist: String, uri: String?, startedAt: Long) {
        CarDatabase.getDatabase(context).musicPlayHistoryDao().insert(
            MusicPlayHistoryEntry(
                title = title,
                artist = artist,
                album = "Some Album",
                spotifyUri = uri,
                startedAt = startedAt,
                startedByLegion = false,
            ),
        )
    }

    @After
    fun drainRoomInvalidationTracker() {
        // A DAO write anywhere in this test can schedule a Room InvalidationTracker refresh
        // on ArchTaskExecutor's disk-IO pool. If that refresh is still queued or running when
        // this test method returns, it races Robolectric's own per-@Test-METHOD native SQLite
        // reset and throws "Illegal connection pointer" on a background thread - uncaught, and
        // blamed by kotlinx-coroutines-test on whatever runTest starts next, not on this class.
        // Draining here, before Robolectric ever gets a chance to reset, is the fix - see
        // RoomTestReset's own class doc comment
        // (.scratch/hardening/issues/13-the-suite-is-green-by-luck.md) for the full account.
        RoomTestReset.drainArchDiskIoPool()
    }


    @Test
    fun `a row with a resolved URI is reported replayable and carries it`() = runBlocking {
        seed("Song A", "Artist One", "spotify:track:abc123", startedAt = 1_000L)

        val result = LiveToolbox.dispatch(
            context, "browse_my_music",
            JSONObject().put("source", "legion_history"),
        )!!

        assertTrue(result.getBoolean("success"))
        val item = result.getJSONArray("items").getJSONObject(0)
        assertTrue(item.getBoolean("replayable"))
        assertEquals("spotify:track:abc123", item.getString("spotifyUri"))
    }

    @Test
    fun `a row with no resolved URI is reported unreplayable, never silently dropped`() = runBlocking {
        seed("Song B", "Artist Two", uri = null, startedAt = 1_000L)

        val result = LiveToolbox.dispatch(
            context, "browse_my_music",
            JSONObject().put("source", "legion_history"),
        )!!

        assertTrue(result.getBoolean("success"))
        val items = result.getJSONArray("items")
        assertEquals(1, items.length()) // present, not skipped
        val item = items.getJSONObject(0)
        assertFalse(item.getBoolean("replayable"))
        assertTrue(item.isNull("spotifyUri"))
    }

    @Test
    fun `the spoken message names the replay caveat when any row lacks a URI`() = runBlocking {
        seed("Song B", "Artist Two", uri = null, startedAt = 1_000L)

        val result = LiveToolbox.dispatch(
            context, "browse_my_music",
            JSONObject().put("source", "legion_history"),
        )!!

        val message = result.getString("message")
        assertTrue(message.contains("can't play them again", ignoreCase = false) || message.contains("replayed"))
        assertTrue(message.contains("Never search for a title to invent a URI"))
    }

    @Test
    fun `the spoken message carries no replay caveat when every row has a URI`() = runBlocking {
        seed("Song A", "Artist One", "spotify:track:abc123", startedAt = 1_000L)

        val result = LiveToolbox.dispatch(
            context, "browse_my_music",
            JSONObject().put("source", "legion_history"),
        )!!

        assertFalse(result.getString("message").contains("Never search for a title to invent a URI"))
    }

    // --- replayabilityNote, direct - the pure function this behaviour is built on ---------------

    private fun entry(uri: String?) = MusicPlayHistoryEntry(
        title = "T", artist = "A", album = "AL", spotifyUri = uri, startedAt = 0L,
    )

    @Test
    fun `replayabilityNote is null when nothing in the list is missing a URI`() {
        assertNull(LiveToolbox.replayabilityNote(listOf(entry("spotify:track:1"), entry("spotify:track:2"))))
    }

    @Test
    fun `replayabilityNote fires when at least one row has no URI`() {
        assertTrue(LiveToolbox.replayabilityNote(listOf(entry("spotify:track:1"), entry(null))) != null)
    }
}
