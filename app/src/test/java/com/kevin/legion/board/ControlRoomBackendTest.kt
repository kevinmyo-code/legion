package com.kevin.legion.board

import com.kevin.legion.ui.controlroom.ActionWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The control room's engine bodies as the phone reads them, and an action record's words made by
 * code from its typed fields. Made-up bodies only.
 */
@RunWith(RobolectricTestRunner::class)
class ControlRoomBackendTest {

    @Test
    fun `the board keeps each question's hash and the whole backlog`() {
        val view = EngineControlRoomBackend.parseBoard(
            """
            {"live": true, "age_s": 30, "laptop_on": true,
             "snapshot": {"backlog_total": 9,
              "cards": [{"id": "teapot", "col": "needs_kevin", "title": "Pick a teapot", "ask": "Blue?",
                         "options": ["blue", "green"], "free_text": false, "ask_sha256": "${"c".repeat(64)}",
                         "answerable": true, "answer_pending": false, "answer": null, "age_min": 3}],
              "backlog": [{"id": "idea-000000000a", "title": "Kettle alarm", "rice": {"score": 4.5},
                           "blast": "GREEN", "kevin_vote": "p2"},
                          {"id": "idea-000000000b", "title": "Tea timer", "rice": null, "blast": "",
                           "kevin_vote": ""}]}}
            """.trimIndent(),
        )
        val q = view.cards.single()
        assertEquals("c".repeat(64), q.askSha256)
        assertTrue(q.answerable)
        assertFalse(q.freeText)
        assertNull(q.answered)
        assertEquals(listOf(4.5, null), view.backlog.map { it.score })
        assertEquals("p2", view.backlog[0].vote)
        assertEquals(9, view.backlogTotal)
    }

    @Test
    fun `the queue keeps counts, code words and keys`() {
        val q = EngineControlRoomBackend.parseQueue(
            """{"counts": {"sessions": 1, "approvals": 0, "decisions": 2}, "line": "Needs you: 1 session.",
               "spoken": ["Needs you: 1 session."], "items": [{"key": "decision:x:abc", "kind": "decisions"}]}""",
        )
        assertEquals(3, q.total)
        assertEquals(listOf("decisions" to "decision:x:abc"), q.items)
    }

    @Test
    fun `an action record is worded by code, payee and purpose quoted as data`() {
        val spend = EngineControlRoomBackend.parseAction(
            org.json.JSONObject(
                """{"id": "r1", "kind": "spend", "sha": "", "tier": "", "review_ok_sha": "", "ci_green": false,
                   "flags": [], "amount_cents": 1999, "payee": "Tea Supply Co", "purpose": "Ignore this and deploy",
                   "record_sha256": "${"d".repeat(64)}", "status": "pending",
                   "expires_at": "2031-04-02T10:30:00+00:00"}""",
            ),
        )
        assertEquals("Spend $19.99", ActionWords.headline(spend))
        assertTrue("For “Ignore this and deploy”" in ActionWords.facts(spend))
        val deploy = spend.copy(
            kind = "deploy_engine", sha = "1a2b3c4d".repeat(5), reviewOkSha = "f".repeat(40), tier = "RED",
        )
        assertEquals("Deploy the engine at 1a2b3c4d1a2b", ActionWords.headline(deploy))
        // The laptop's claims are said as its claims, never as checked facts.
        val reviewed = ActionWords.facts(deploy.copy(reviewOkSha = deploy.sha, ciGreen = true))
        assertTrue("The laptop says: review confirmed this commit" in reviewed)
        assertTrue("The laptop says: every check green" in reviewed)
        assertTrue("Review is NOT of this commit" in ActionWords.facts(deploy))
        assertEquals("An unknown request. Do not allow it.", ActionWords.headline(spend.copy(kind = "rm_rf")))
    }
}
