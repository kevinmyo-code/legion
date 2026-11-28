package com.kevin.legion.memory

import com.kevin.legion.backend.engine.decodeSummaries
import com.kevin.legion.backend.engine.decodeTurns
import java.time.ZoneOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #452: a new chat opens with the last summaries and how the newest chat ended. */
class RecentChatsTest {

    private val now = 1_764_300_000_000L // 2025-11-28T03:20:00Z
    private val min = 60_000L
    private val hour = 60 * min
    private val zone = ZoneOffset.UTC

    private fun summary(session: String, agoMs: Long, text: String) = ChatSummary(session, now - agoMs, text)

    private fun turn(session: String, n: Int, agoMs: Long, user: String, reply: String, tools: Boolean = false) =
        ChatTurn(session, n, user, reply, tools, now - agoMs)

    @Test fun `nothing on the engine means no note`() {
        assertNull(RecentChats.note(RecentChats.pick(emptyList(), emptyList(), now), zone))
    }

    @Test fun `a chat minutes ago is carried, with its summary and its last turns`() {
        val read = RecentChats.pick(
            listOf(summary("a", 5 * min, "They talked about the F-150's brake light and agreed to check the fuse.")),
            listOf(
                turn("a", 0, 9 * min, "my brake light is out", "Could be the fuse or the bulb."),
                turn("a", 1, 6 * min, "ok I'll check the fuse tonight", "Good plan."),
            ),
            now,
        )
        val note = RecentChats.note(read, zone)!!
        assertTrue(note.contains("brake light"))
        assertTrue(note.contains("User: ok I'll check the fuse tonight"))
        assertTrue(note.contains("You: Good plan."))
        assertTrue(note.contains("The last conversation ended at Nov 28, 3:14 AM"))
    }

    @Test fun `only the newest chat's turns are carried, oldest first, the last three`() {
        val read = RecentChats.pick(
            emptyList(),
            listOf(
                turn("old", 0, 30 * min, "about the old chat", "old reply"),
                turn("new", 0, 20 * min, "one", "r1"),
                turn("new", 1, 15 * min, "two", "r2"),
                turn("new", 2, 12 * min, "three", "r3"),
                turn("new", 3, 10 * min, "four", "r4"),
            ),
            now,
        )
        assertEquals(
            listOf("User: two", "You: r2", "User: three", "You: r3", "User: four", "You: r4"),
            read.lastChatLines,
        )
        assertFalse(read.lastChatLines.any { it.contains("old") })
    }

    @Test fun `a tool turn keeps the person's words and leaves the reply out`() {
        val read = RecentChats.pick(
            emptyList(),
            listOf(turn("a", 0, 5 * min, "what's on today", "You have a dentist at 3.", tools = true)),
            now,
        )
        assertEquals(listOf("User: what's on today"), read.lastChatLines)
    }

    @Test fun `a chat that ended hours ago gives its summary but not its turns`() {
        val read = RecentChats.pick(
            listOf(summary("a", 3 * hour, "They planned the cookout.")),
            listOf(turn("a", 0, 3 * hour, "cookout on saturday", "Sounds fun.")),
            now,
        )
        assertTrue(read.lastChatLines.isEmpty())
        assertNull(read.lastChatEndedAtMs)
        assertTrue(RecentChats.note(read, zone)!!.contains("They planned the cookout."))
    }

    @Test fun `two summaries at most, newest first, none older than a week`() {
        val read = RecentChats.pick(
            listOf(
                summary("c", 2 * hour, "third"),
                summary("a", 8 * 24 * hour, "too old"),
                summary("b", 1 * hour, "newest"),
                summary("d", 26 * hour, "fourth"),
            ),
            emptyList(),
            now,
        )
        assertEquals(listOf("newest", "third"), read.summaries.map { it.text })
    }

    @Test fun `the note is labelled as past conversations and never states a gap`() {
        val note = RecentChats.note(RecentChats.pick(listOf(summary("a", 30 * min, "x")), emptyList(), now), zone)!!
        assertTrue(note.contains("not facts from the record"))
        assertTrue(note.contains("still call the tool"))
        assertTrue(note.contains("Never comment on how long it has been since you talked."))
        for (banned in listOf("days ago", "a day ago", "earlier today", "week(s)", "over a month", "missed")) {
            assertFalse(banned, note.contains(banned))
        }
        assertFalse("the phone's prompt says the user, never the driver", note.contains("driver"))
    }

    @Test fun `the note stays small however long the chats were`() {
        val long = "word ".repeat(2_000)
        val read = RecentChats.pick(
            listOf(summary("a", 5 * min, long), summary("b", 50 * min, long)),
            (0 until 10).map { turn("a", it, (20 - it) * min, long, long) },
            now,
        )
        assertTrue(RecentChats.note(read, zone)!!.length <= RecentChats.NOTE_MAX_CHARS)
        assertTrue(read.lastChatLines.all { it.length <= RecentChats.LINE_MAX_CHARS + "User: ".length })
    }

    @Test fun `a slow or failing engine gives no note and never holds the chat`() = runBlocking {
        val slow = object : RecentChatsSource {
            override suspend fun summaries(limit: Int): List<ChatSummary> {
                delay(5_000); return listOf(summary("a", min, "late"))
            }
            override suspend fun turns(limit: Int): List<ChatTurn>? = null
        }
        val started = System.currentTimeMillis()
        val read = RecentChats.read(slow, now, waitMs = 100)
        assertTrue(System.currentTimeMillis() - started < 2_000)
        assertNull(RecentChats.note(read, zone))
    }

    @Test fun `one read failing still carries the other`() = runBlocking {
        val half = object : RecentChatsSource {
            override suspend fun summaries(limit: Int): List<ChatSummary>? = null
            override suspend fun turns(limit: Int) = listOf(turn("a", 0, 2 * min, "hello", "Hi."))
        }
        assertEquals(listOf("User: hello", "You: Hi."), RecentChats.read(half, now).lastChatLines)
    }

    @Test fun `the engine's lists decode`() {
        val summaries = decodeSummaries(
            """{"results":[{"id":"6f1c","session":"s1","source":"phone","text":"They talked.",""" +
                """"ended_by":"closed","turns_read":3,"started_at":"2026-11-28T01:00:00+00:00",""" +
                """"ended_at":"2026-11-28T01:05:00.123456+00:00"}]}""",
        )
        assertEquals("They talked.", summaries.single().text)
        assertEquals(1_795_827_900_123L, summaries.single().endedAtMs)

        val turns = decodeTurns(
            """{"results":[{"id":"x","session":"s1","turn":2,"source":"phone","user_text":"hi",""" +
                """"assistant_text":"Hello.","tools":["manage_item"],"spoken_at":"2026-11-28T01:04:00+00:00"}]}""",
        )
        assertEquals(ChatTurn("s1", 2, "hi", "Hello.", true, 1_795_827_840_000L), turns.single())
    }
}
