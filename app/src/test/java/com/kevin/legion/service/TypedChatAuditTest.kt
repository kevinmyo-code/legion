package com.kevin.legion.service

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Typed chat's LIVE_AUDIT lines (conversation-records ticket 03): the same format as voice, the
 * typed flag on the user line, and sec 7 redaction for a mail tool. Robolectric for org.json.
 */
@RunWith(RobolectricTestRunner::class)
class TypedChatAuditTest {
    private val lines = mutableListOf<String>()
    private var clock = 1_000L
    private var on = true
    private val typed = TypedChatAudit(
        enabled = { on },
        audit = LiveAudit(sink = { lines += it }, isReadThroughTool = { it == "read_mail" }, onSession = {}),
        now = { clock },
    )

    @Test fun `a typed turn logs user, tool and reply in the voice format`() {
        typed.user("what's in the pantry?")
        val id = typed.toolCall("ask_pantry", JSONObject().put("question", "pantry"))
        clock += 250
        typed.toolResult(id, "ask_pantry", JSONObject().put("success", true).put("answer", "eggs"))
        typed.turn(TypedTurnRecord(1, "what's in the pantry?", "Eggs.", readThrough = false))
        assertEquals(
            listOf(
                "user final=true typed=true text=\"what's in the pantry?\"",
                "tool_call id=typed-1 name=ask_pantry async=false args={\"question\":\"pantry\"}",
                "tool_result id=typed-1 name=ask_pantry success=true ms=250 scheduling=none sent=true " +
                    "response={\"success\":true,\"answer\":\"eggs\"}",
                "assistant text=\"Eggs.\"",
                "turn_complete",
            ),
            lines,
        )
    }

    @Test fun `a mail turn is redacted and a failed turn logs no reply`() {
        typed.user("read my mail")
        val id = typed.toolCall("read_mail", JSONObject())
        typed.toolResult(id, "read_mail", JSONObject().put("body", "secret"))
        typed.turn(TypedTurnRecord(1, "read my mail", "Your bank says...", readThrough = true))
        typed.user("again")
        typed.turn(TypedTurnRecord(2, "again", "", readThrough = false))
        assertTrue(lines.none { "secret" in it || "Your bank" in it })
        assertTrue(lines.contains("assistant text=\"${LiveAudit.REDACTED}\""))
        assertEquals("turn_complete", lines.last())
        assertTrue(lines[lines.size - 2].startsWith("user "))
    }

    @Test fun `off writes nothing`() {
        on = false
        typed.user("hi")
        typed.turn(TypedTurnRecord(1, "hi", "Hello.", readThrough = false))
        assertTrue(lines.isEmpty())
    }
}
