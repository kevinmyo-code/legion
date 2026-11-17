@file:Suppress("MaxLineLength") // invented log lines read best on one line

package com.kevin.legion.service

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.ConversationAudit
import com.kevin.legion.data.local.READ_THROUGH_REDACTED
import com.kevin.legion.memory.MemberMemory
import com.kevin.legion.service.conversationlog.ConversationSpool
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The one-off ADR 0090 cleanup (2026-11-17, Kevin: "Blank them", counts first). Dry run counts and
 * changes nothing; the real run blanks only a folded mail, brief, parcel or flight result and the
 * reply of that turn, with the marker new rows get. Invented rows only.
 */
@RunWith(RobolectricTestRunner::class)
class ReadThroughCleanupTest {

    @get:Rule val tmp = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()
    private val t0 = ReadThroughCleanup.SINCE_MS + 86_400_000L

    @Before
    fun reset() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    private fun row(id: Long, seq: Long, kind: String, at: Long, tool: String = "", args: String = "", content: String = "invented", redacted: Boolean = false) =
        ConversationAudit(id = id, turnSeq = seq, kind = kind, toolName = tool, args = args, content = content, redacted = redacted, at = at)

    private val mail = """{"domain":"ask_mail","question":"newest"}"""

    // --- which Room rows -------------------------------------------------------------------------

    @Test
    fun `a folded mail, brief, parcel or flight result and its turn's reply are found, nothing else`() {
        val rows = listOf(
            row(1, 0, ConversationAudit.Kind.USER, t0, content = "read my newest email"),
            row(2, 0, ConversationAudit.Kind.TOOL_RESULT, t0 + 1_000, "look_up", mail, "An invented mail body"),
            row(3, 0, ConversationAudit.Kind.COMPANION, t0 + 5_000, content = "The invented mail says hello"),
            row(4, 1, ConversationAudit.Kind.TOOL_RESULT, t0 + 60_000, "day", """{"action":"read_calendar"}""", "An invented event"),
            row(5, 1, ConversationAudit.Kind.COMPANION, t0 + 62_000, content = "You have an invented event"),
            row(6, 2, ConversationAudit.Kind.TOOL_RESULT, t0 + 120_000, "day", """{"action":"get_sitrep"}"""),
            row(7, 2, ConversationAudit.Kind.COMPANION, t0 + 125_000),
            row(8, 3, ConversationAudit.Kind.TOOL_RESULT, t0 + 180_000, "day", """{"action":"track_package"}"""),
            row(9, 4, ConversationAudit.Kind.TOOL_RESULT, t0 + 240_000, "day", """{"action":"flight_status"}"""),
            // Already redacted: left alone.
            row(10, 5, ConversationAudit.Kind.TOOL_RESULT, t0 + 300_000, "ask_mail", mail, READ_THROUGH_REDACTED, redacted = true),
        )
        assertEquals(listOf(2L, 3L, 6L, 7L, 8L, 9L), ReadThroughCleanup.auditTargets(rows))
    }

    @Test
    fun `turn_seq restarts per conversation, so the reply is matched by order and time too`() {
        val rows = listOf(
            // Conversation A, turn 0: a calendar read and its reply.
            row(1, 0, ConversationAudit.Kind.TOOL_RESULT, t0, "day", """{"action":"read_calendar"}"""),
            row(2, 0, ConversationAudit.Kind.COMPANION, t0 + 2_000),
            // Conversation B an hour later, turn 0 again: a mail read and its reply.
            row(3, 0, ConversationAudit.Kind.TOOL_RESULT, t0 + 3_600_000, "look_up", mail),
            row(4, 0, ConversationAudit.Kind.COMPANION, t0 + 3_604_000),
            // Conversation C, turn 0, long after: not B's reply.
            row(5, 0, ConversationAudit.Kind.COMPANION, t0 + 7_200_000),
        )
        assertEquals(listOf(3L, 4L), ReadThroughCleanup.auditTargets(rows))
    }

    @Test
    fun `rows from before the window are left alone`() {
        val old = ReadThroughCleanup.SINCE_MS - 1_000
        val rows = listOf(row(1, 0, ConversationAudit.Kind.TOOL_RESULT, old, "look_up", mail))
        assertTrue(ReadThroughCleanup.auditTargets(rows).isEmpty())
    }

    // --- the spool -------------------------------------------------------------------------------

    private fun l(msg: String) = "11-15 08:00:00.000 100 200 I LIVE_AUDIT: $msg"

    private val spool = listOf(
        l("session_start model=invented async_set=none"),
        l("user final=true text=\"read my newest email\""),
        l("tool_call id=c1 name=look_up async=false args={\"domain\":\"ask_mail\",\"question\":\"newest\"}"),
        l("tool_result id=c1 name=look_up success=true ms=900 scheduling=none sent=true response={\"text\":\"An invented mail body\"}"),
        l("assistant text=\"The invented mail says hello\""),
        l("turn_complete"),
        l("user final=true text=\"what's on today\""),
        l("tool_call id=c2 name=day async=false args={\"action\":\"read_calendar\"}"),
        l("tool_result id=c2 name=day success=true ms=90 scheduling=none sent=true response={\"events\":\"An invented event\"}"),
        l("assistant text=\"You have an invented event\""),
        l("turn_complete"),
        "11-15 08:00:01.000 100 200 I LIVE_LATENCY: tool_ms=900 tool=look_up",
    )

    @Test
    fun `the spool blanker blanks the mail result and that turn's reply only`() {
        val b = ReadThroughCleanup.SpoolBlanker()
        val out = spool.map(b::line)
        assertEquals(2, b.changed)
        assertFalse(out.joinToString("\n").contains("invented mail"))
        assertTrue(out[3].endsWith("response=\"${LiveAudit.REDACTED}\""))
        assertTrue(out[4].endsWith("assistant text=\"${LiveAudit.REDACTED}\""))
        assertEquals("his own words stay", spool[1], out[1])
        assertEquals("a calendar result stays", spool[8], out[8])
        assertEquals("the next turn's reply stays", spool[9], out[9])
        assertEquals(spool.last(), out.last())
        // Idempotent: a second pass changes nothing.
        val again = ReadThroughCleanup.SpoolBlanker()
        out.map(again::line)
        assertEquals(0, again.changed)
    }

    @Test
    fun `the spool is rewritten only when asked`() {
        val dir = tmp.newFolder("conversationlog")
        val s = ConversationSpool(dir)
        s.appendAll(spool, t0)
        val before = java.io.File(dir, "current.log").readText()
        assertEquals(2, s.rewrite(write = false, line = ReadThroughCleanup.SpoolBlanker()::line))
        assertEquals("a dry run changes nothing", before, java.io.File(dir, "current.log").readText())
        assertEquals(2, s.rewrite(write = true, line = ReadThroughCleanup.SpoolBlanker()::line))
        val after = java.io.File(dir, "current.log").readText()
        assertFalse(after.contains("invented mail"))
        assertTrue(after.contains("An invented event"))
        assertEquals(spool.size, after.lines().count { it.isNotEmpty() })
    }

    // --- memories --------------------------------------------------------------------------------

    private fun memory(id: String, source: String, at: Long, retired: String? = null) = MemberMemory(
        id = id, text = "invented", kind = "fact", importance = 1, decayClass = "durable", source = source,
        shared = false, aboutYou = true, createdAtMs = at, retiredReason = retired,
    )

    @Test
    fun `memories to review are consolidated ones made in the window, by id`() {
        val ids = ReadThroughCleanup.memoryCandidates(
            listOf(
                memory("m3", "consolidation", t0),
                memory("m1", "consolidation", t0 + 1),
                memory("m2", "phone", t0),
                memory("m4", "consolidation", ReadThroughCleanup.SINCE_MS - 1),
                memory("m5", "consolidation", t0, retired = "merged"),
            ),
        )
        assertEquals(listOf("m1", "m3"), ids)
    }

    // --- both modes on the real table ------------------------------------------------------------

    @Test
    fun `dry run counts and changes nothing, the real run blanks with the redaction marker`() = runBlocking {
        val dao = CarDatabase.getDatabase(context).conversationAuditDao()
        dao.insert(row(0, 0, ConversationAudit.Kind.USER, t0, content = "read my newest email"))
        dao.insert(row(0, 0, ConversationAudit.Kind.TOOL_RESULT, t0 + 1_000, "look_up", mail, "An invented mail body"))
        dao.insert(row(0, 0, ConversationAudit.Kind.COMPANION, t0 + 5_000, content = "The invented mail says hello"))
        dao.insert(row(0, 1, ConversationAudit.Kind.TOOL_RESULT, t0 + 60_000, "day", """{"action":"read_calendar"}""", "An invented event"))

        val dry = ReadThroughCleanup.run(context, real = false)
        assertEquals(2, dry.auditRows)
        assertEquals("readthrough_cleanup dry=true table=conversation_audit rows=2", dry.lines().first())
        assertTrue(dao.since(0).none { it.redacted })
        assertTrue(dao.since(0).any { it.content.contains("invented mail") })

        val real = ReadThroughCleanup.run(context, real = true)
        assertEquals(2, real.auditRows)
        val rows = dao.since(0).sortedBy { it.id }
        assertEquals(listOf(false, true, true, false), rows.map { it.redacted })
        assertEquals(READ_THROUGH_REDACTED, rows[1].content)
        assertEquals(READ_THROUGH_REDACTED, rows[2].content)
        assertEquals("his words stay", "read my newest email", rows[0].content)
        assertEquals("the calendar result stays", "An invented event", rows[3].content)
        assertEquals(4, rows.size)
        assertEquals("nothing left to blank", 0, ReadThroughCleanup.run(context, real = false).auditRows)
    }
}
