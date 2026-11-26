package com.kevin.midnightai.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** "What's new": the history parse, the load that never turns a failure into an empty list, the show-once rule. */
class WhatsNewTest {

    private val body = """
        [
          {"version_name":"2026-11-26-bbb","version_code":12,"notes":"newer","published_at":"2026-11-26T10:00:00Z"},
          {"version_name":"2026-11-20-aaa","version_code":10,"notes":"","published_at":"2026-11-20T10:00:00Z"}
        ]
    """.trimIndent()

    private class FakeSource(var result: Result<List<ReleaseNote>>) : ReleaseHistorySource {
        override suspend fun history(): Result<List<ReleaseNote>> = result
    }

    @Test
    fun `the engine's list parses in order and a bad element makes the whole answer unreadable`() {
        val notes = ReleaseHistoryJson.parse(body)!!
        assertEquals(listOf(12L, 10L), notes.map { it.versionCode })
        assertEquals("newer", notes[0].notes)
        assertEquals("2026-11-26-bbb", notes[0].versionName)
        assertEquals(emptyList<ReleaseNote>(), ReleaseHistoryJson.parse("[]"))
        assertNull(ReleaseHistoryJson.parse("""[{"version_name":"x","notes":"n","published_at":"t"}]"""))
        assertNull(ReleaseHistoryJson.parse("""{"detail":"nope"}"""))
        assertNull(ReleaseHistoryJson.parse("not json"))
    }

    @Test
    fun `loading marks the installed code and keeps every release`() = runBlocking {
        val source = FakeSource(Result.success(ReleaseHistoryJson.parse(body)!!))
        val state = WhatsNew.load(source, installedCode = 10) as WhatsNewState.Loaded
        assertEquals(2, state.releases.size)
        assertEquals(10L, state.installedCode)
    }

    @Test
    fun `a server that cannot be reached says so in words and is never an empty list`() = runBlocking {
        val source = FakeSource(Result.failure(IOException("timeout")))
        val state = WhatsNew.load(source, installedCode = 10)
        assertTrue(state is WhatsNewState.Unavailable)
        val sentence = (state as WhatsNewState.Unavailable).sentence
        assertTrue(sentence, sentence.contains("Could not load"))
        assertTrue(sentence, sentence.contains("timeout"))
        assertTrue(sentence, sentence.contains("not that there are no releases"))
    }

    @Test
    fun `an empty engine list is its own sentence`() {
        assertTrue(WhatsNew.NO_RELEASES.contains("lists no releases"))
    }

    @Test
    fun `blank notes read as no notes, not as a blank line`() {
        assertEquals("This release has no notes.", WhatsNew.notesLine(ReleaseNote(1, "v", "  ", "t")))
        assertEquals("fixes X", WhatsNew.notesLine(ReleaseNote(1, "v", " fixes X ", "t")))
    }

    @Test
    fun `it is due once per installed version, never on a debug build`() {
        assertTrue(WhatsNew.due(installedCode = 12, seenCode = 10))
        assertTrue(WhatsNew.due(installedCode = 12, seenCode = 0))
        assertFalse(WhatsNew.due(installedCode = 12, seenCode = 12))
        assertFalse(WhatsNew.due(installedCode = 1, seenCode = 0))
        assertFalse(WhatsNew.due(installedCode = 0, seenCode = 0))
    }
}
