package com.kevin.midnightai.ai

import com.kevin.midnightai.data.local.EpisodicTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** Issue #452 on the head unit: what a new chat is told about the last ones. */
class RecentChatsNoteTest {

    @Test
    fun `nothing stored and nothing pending gives no note`() {
        assertNull(RecentChatsNote.note(emptyList(), null, CAR, NOW, UTC))
    }

    @Test
    fun `a chat minutes ago carries its summary and the driver's last words`() {
        val chat = RecentChat(CAR, NOW - 6 * MIN, "They talked about the brake noise.", listOf("book it", "thanks"))
        val note = RecentChatsNote.note(listOf(chat), null, CAR, NOW, UTC)!!
        assertTrue(note.contains("(Nov 28, 3:14 AM) They talked about the brake noise."))
        assertTrue(note.contains("ended at Nov 28, 3:14 AM; the driver's last words in it: "))
        assertTrue(note.endsWith("Driver: book it / Driver: thanks"))
    }

    @Test
    fun `a chat not yet consolidated carries its driver lines with no summary`() {
        val pending = RecentChat(CAR, NOW - 2 * MIN, null, listOf("what's my oil life"))
        val note = RecentChatsNote.note(emptyList(), pending, CAR, NOW, UTC)!!
        assertTrue(note.contains("Driver: what's my oil life"))
        assertFalse(note.contains("Earlier conversations"))
    }

    @Test
    fun `hours ago gives the summary but no last words`() {
        val chat = RecentChat(CAR, NOW - 3 * HOUR, "They planned the trip north.", listOf("leave at six"))
        val note = RecentChatsNote.note(listOf(chat), null, CAR, NOW, UTC)!!
        assertTrue(note.contains("They planned the trip north."))
        assertFalse(note.contains("leave at six"))
    }

    @Test
    fun `at most two summaries, none older than a week, only this car's`() {
        val chats = listOf(
            RecentChat(CAR, NOW - 1 * HOUR * 24, "one", emptyList()),
            RecentChat(CAR, NOW - 2 * HOUR * 24, "two", emptyList()),
            RecentChat(CAR, NOW - 3 * HOUR * 24, "three", emptyList()),
            RecentChat(CAR, NOW - 8 * HOUR * 24, "old", emptyList()),
            RecentChat("car:other", NOW - MIN, "elsewhere", listOf("other car")),
        )
        val note = RecentChatsNote.note(chats, null, CAR, NOW, UTC)!!
        assertTrue(note.contains(") one") && note.contains(") two"))
        assertFalse(note.contains(") three") || note.contains(") old") || note.contains("elsewhere"))
    }

    @Test
    fun `it is labelled as past talk and never states a gap`() {
        val chat = RecentChat(CAR, NOW - MIN, "They talked.", listOf("hi"))
        val note = RecentChatsNote.note(listOf(chat), null, CAR, NOW, UTC)!!.lowercase()
        assertTrue(note.contains("not facts from the record"))
        assertTrue(note.contains("never comment on how long it has been"))
        for (banned in listOf("ago", "earlier today", "yesterday", "last time", "since we", "the user")) {
            assertFalse(banned, note.contains(banned))
        }
    }

    @Test
    fun `the note is capped`() {
        val long = "word ".repeat(500)
        val chats = listOf(
            RecentChat(CAR, NOW - MIN, long, List(3) { long }),
            RecentChat(CAR, NOW - HOUR, long, emptyList()),
        )
        assertTrue(RecentChatsNote.note(chats, null, CAR, NOW, UTC)!!.length <= RecentChatsNote.NOTE_MAX_CHARS)
    }

    @Test
    fun `driver lines are the last three of the driver's, in order`() {
        val turns = listOf(
            turn(EpisodicTurn.Role.DRIVER, "one", 1),
            turn(EpisodicTurn.Role.COMPANION, "reply", 2),
            turn(EpisodicTurn.Role.DRIVER, "two", 3),
            turn(EpisodicTurn.Role.DRIVER, "  three \n  ", 4),
            turn(EpisodicTurn.Role.DRIVER, "four", 5),
            turn(EpisodicTurn.Role.DRIVER, " ", 6),
        )
        assertEquals(listOf("two", "three", "four"), RecentChatsNote.driverLines(turns))
    }

    @Test
    fun `the store round-trips and drops what is past the lookback`() {
        val kept = RecentChat(CAR, NOW - MIN, null, listOf("a", "b"))
        val summed = RecentChat(CAR, NOW - HOUR, "sum", emptyList())
        val stale = RecentChat(CAR, NOW - 8 * HOUR * 24, "stale", emptyList())
        val raw = RecentChatStore.encode(listOf(kept, summed, stale))
        assertEquals(listOf(kept, summed), RecentChatStore.decode(raw, NOW))
        assertEquals(emptyList<RecentChat>(), RecentChatStore.decode("not json", NOW))
        assertEquals(emptyList<RecentChat>(), RecentChatStore.decode(null, NOW))
    }

    private fun turn(role: String, text: String, ts: Long) =
        EpisodicTurn(sessionId = "s", vehicleId = CAR, role = role, text = text, timestamp = ts)

    private companion object {
        const val CAR = "car:test"
        const val MIN = 60_000L
        const val HOUR = 60 * MIN
        const val NOW = 1_764_300_000_000L // 2025-11-28T03:20Z
        val UTC: ZoneOffset = ZoneOffset.UTC
    }
}
