package com.kevin.legion.update

import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttpException
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

    private fun sentenceFor(failure: Throwable): String {
        val state = runBlocking { WhatsNew.load(FakeSource(Result.failure(failure)), installedCode = 10) }
        assertTrue(state is WhatsNewState.Unavailable)
        val sentence = (state as WhatsNewState.Unavailable).sentence
        assertTrue(sentence, sentence.startsWith("Could not load the release notes"))
        assertTrue(sentence, sentence.contains("does not mean there are no releases"))
        return sentence
    }

    @Test
    fun `an unreachable engine says it could not be reached`() {
        val sentence = sentenceFor(EngineHttpException(EngineFailure.Unreachable("engine", "timeout")))
        assertTrue(sentence, sentence.contains("could not be reached"))
    }

    @Test
    fun `a device that is not signed in says so and never blames the engine`() {
        val sentence = sentenceFor(ReleaseHistoryException(ReleaseHistoryFailure.NOT_SIGNED_IN))
        assertTrue(sentence, sentence.contains("not signed in"))
        assertFalse(sentence, sentence.contains("reach"))
    }

    @Test
    fun `a refused sign-in says the sign-in was refused, not that the engine is away`() {
        val sentence = sentenceFor(EngineHttpException(EngineFailure.Unauthorized("no", status = 401)))
        assertTrue(sentence, sentence.contains("sign-in was not accepted"))
        assertFalse(sentence, sentence.contains("reach"))
    }

    @Test
    fun `a server error says the engine answered with an error and its status`() {
        val sentence = sentenceFor(EngineHttpException(EngineFailure.Refused(503, "down for work")))
        assertTrue(sentence, sentence.contains("answered with an error"))
        assertTrue(sentence, sentence.contains("503"))
        assertFalse(sentence, sentence.contains("reach"))
    }

    @Test
    fun `an unreadable reply says it could not be read`() {
        val sentence = sentenceFor(ReleaseHistoryException(ReleaseHistoryFailure.UNREADABLE))
        assertTrue(sentence, sentence.contains("could not be read"))
        assertFalse(sentence, sentence.contains("reach"))
    }

    @Test
    fun `a failure of unknown kind names no cause it did not see`() {
        val sentence = sentenceFor(IOException("boom"))
        assertTrue(sentence, sentence.contains("boom"))
        assertFalse(sentence, sentence.contains("reach"))
    }

    @Test
    fun `only a load that showed the notes is recorded as seen`() {
        val loaded = WhatsNewState.Loaded(emptyList(), installedCode = 12)
        assertEquals(12L, WhatsNew.seenToRecord(loaded))
        assertNull(WhatsNew.seenToRecord(WhatsNewState.Loading))
        assertNull(WhatsNew.seenToRecord(WhatsNewState.Unavailable("Could not load")))
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
        assertTrue(WhatsNew.due(installedCode = 12, seenCode = 10, signedIn = true))
        assertTrue(WhatsNew.due(installedCode = 12, seenCode = 0, signedIn = true))
        assertFalse(WhatsNew.due(installedCode = 12, seenCode = 12, signedIn = true))
        assertFalse(WhatsNew.due(installedCode = 1, seenCode = 0, signedIn = true))
        assertFalse(WhatsNew.due(installedCode = 0, seenCode = 0, signedIn = true))
        assertFalse(WhatsNew.due(installedCode = 12, seenCode = 0, signedIn = false))
    }
}
