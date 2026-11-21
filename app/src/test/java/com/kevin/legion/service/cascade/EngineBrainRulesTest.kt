package com.kevin.legion.service.cascade

import com.kevin.legion.service.cascade.EngineBrainRules.AfterPost
import com.kevin.legion.service.cascade.EngineBrainRules.Facts
import com.kevin.legion.service.cascade.FakeEngineWire.Companion.json
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The engine brain's pure decisions. Invented words only. Robolectric only for `org.json`. */
@RunWith(RobolectricTestRunner::class)
class EngineBrainRulesTest {
    private val his = Facts(test = false, fromUser = true)

    @Test
    fun `K3 as written, the engine unreachable at open says so and answers nothing`() {
        assertEquals(EngineBrainRules.WhenUnreachable.SAY_AND_CLOSE, EngineBrainRules.UNREACHABLE_AT_OPEN)
    }

    @Test
    fun `his plain words go to the engine`() {
        for (words in listOf("set a timer for ten minutes", "what's on today", "pause the music")) {
            assertNull(words, EngineBrainRules.phoneKeeps(words, his))
        }
    }

    @Test
    fun `mail words stay on the phone loop until ticket 05`() {
        for (words in listOf("any new email", "read my Gmail", "check the inbox", "where's my package",
            "is my flight on time", "brief me", "what's in my mailbox", "any e-mails from work")) {
            assertEquals(words, "mail", EngineBrainRules.phoneKeeps(words, his))
        }
        // A word that only contains one is not one.
        assertNull(EngineBrainRules.phoneKeeps("add milk to the shopping list", his))
        assertNull(EngineBrainRules.phoneKeeps("what's the chatter about the game", his))
    }

    @Test
    fun `a test conversation and a phone prompt stay on the phone`() {
        assertEquals("test", EngineBrainRules.phoneKeeps("hello", his.copy(test = true)))
        assertEquals("prompt", EngineBrainRules.phoneKeeps("hello", his.copy(fromUser = false)))
        // A reply to a phone line goes to the engine now, with the line as `prior_line` (Contract v2).
        assertNull(EngineBrainRules.phoneKeeps("yes", his))
    }

    @Test
    fun `a call's needs are refused at once when the phone cannot meet them`() {
        val lockedDark = EngineBrainRules.DeviceState(locked = true, screenOn = false)
        val lockedLit = EngineBrainRules.DeviceState(locked = true, screenOn = true)
        val open = EngineBrainRules.DeviceState(locked = false, screenOn = true)
        val dark = EngineBrainRules.needsRefusal(listOf("screen"), lockedDark)
        assertEquals("Not done: the phone's screen is off.", dark)
        assertEquals("Not done: the phone is locked.", EngineBrainRules.needsRefusal(listOf("unlock"), lockedLit))
        assertNull(EngineBrainRules.needsRefusal(listOf("screen", "unlock"), open))
        assertNull(EngineBrainRules.needsRefusal(emptyList(), lockedDark))
    }

    @Test
    fun `a tool's response becomes the posted outcome`() {
        val ok = EngineBrainRules.outcomeOf(JSONObject().put("success", true).put("message", "Timer set."), 12)
        assertEquals(CallOutcome.OK, ok.outcome)
        assertEquals("Timer set.", ok.words)
        val guarded = JSONObject().put("success", false).put("read_through", true).put("message", "Nothing was done.")
        assertEquals(CallOutcome.REFUSED, EngineBrainRules.outcomeOf(guarded, 1).outcome)
        val test = JSONObject().put("success", false).put("test_mode", true)
        assertEquals(CallOutcome.REFUSED, EngineBrainRules.outcomeOf(test, 1).outcome)
        assertEquals(CallOutcome.FAILED, EngineBrainRules.outcomeOf(JSONObject().put("success", false), 1).outcome)
    }

    @Test
    fun `a result over the engine's 16 KiB cap is cut to its message`() {
        val big = JSONObject().put("success", true).put("message", "short").put("rows", "x".repeat(20_000))
        val sent = EngineBrainRules.boundedResult(big)
        assertTrue(sent.toString().length < EngineBrainRules.MAX_RESULT_BYTES)
        assertTrue(sent.getBoolean("trimmed"))
        assertEquals("short", sent.getString("message"))
    }

    @Test
    fun `each answer to a result post means what the contract says`() {
        assertEquals(AfterPost.STREAM, EngineBrainRules.afterPost(WireReply.Events(Channel()) {}))
        assertEquals(AfterPost.WAITING, EngineBrainRules.afterPost(json(202, """{"waiting_for": ["a"]}""")))
        assertEquals(AfterPost.RECORDED, EngineBrainRules.afterPost(json(200, """{"recorded": true}""")))
        assertEquals(AfterPost.NO_KEY, EngineBrainRules.afterPost(json(503, """{"recorded": true}""")))
        assertEquals(AfterPost.MEMBER_CAP, EngineBrainRules.afterPost(json(429, """{"recorded": true}""")))
        assertEquals(AfterPost.THROTTLED, EngineBrainRules.afterPost(json(429, """{"code": "throttled"}""")))
        assertEquals(AfterPost.REVOKED, EngineBrainRules.afterPost(json(401, "{}")))
        assertEquals(AfterPost.NOT_RECORDED, EngineBrainRules.afterPost(json(409, """{"code": "unknown_call"}""")))
        assertEquals(AfterPost.NOT_RECORDED, EngineBrainRules.afterPost(json(413, "{}")))
        assertEquals(AfterPost.UNREACHABLE, EngineBrainRules.afterPost(WireReply.Unreachable("x")))
    }

    @Test
    fun `retries, three after Retry-After when throttled, one after 5 s for no_key, none else`() {
        val throttled = json(429, """{"code": "throttled"}""", retryAfterS = 2)
        assertEquals(2_000L, EngineBrainRules.retryWaitMs(throttled, 0))
        assertEquals(2_000L, EngineBrainRules.retryWaitMs(throttled, 2))
        assertNull(EngineBrainRules.retryWaitMs(throttled, 3))
        val noKey = json(503, """{"recorded": true, "code": "no_key"}""")
        assertEquals(5_000L, EngineBrainRules.retryWaitMs(noKey, 0))
        assertNull(EngineBrainRules.retryWaitMs(noKey, 1))
        assertNull(EngineBrainRules.retryWaitMs(json(409, "{}"), 0))
    }

    @Test
    fun `the stream route's switch-off and audio gap send the clip to the batch route`() {
        assertTrue(EngineBrainRules.streamFallsBack(json(404, """{"detail": "switched off"}""")))
        assertTrue(EngineBrainRules.streamFallsBack(json(400, """{"code": "audio_gap"}""")))
        assertTrue(EngineBrainRules.streamFallsBack(WireReply.Unreachable("IOException")))
        assertEquals(false, EngineBrainRules.streamFallsBack(json(429, """{"code": "member_cap"}""")))
    }
}
