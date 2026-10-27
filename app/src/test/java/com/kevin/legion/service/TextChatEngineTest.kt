package com.kevin.legion.service

import com.kevin.legion.ai.AgentResult
import com.kevin.legion.ai.RestOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [TextChatEngine] against a fake transport and a fake tool runner: the request shape, the tool
 * loop, read-through, write tracking, the round cap and every failure's wording. No network.
 * Robolectric only for `org.json`, which is stubbed in a plain JVM test.
 */
@RunWith(RobolectricTestRunner::class)
class TextChatEngineTest {

    private val sent = mutableListOf<JSONObject>()

    private fun text(t: String) = RestOutcome.Ok(
        JSONObject().put(
            "candidates",
            JSONArray().put(
                JSONObject().put(
                    "content",
                    JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", t))),
                ),
            ),
        ).toString(),
    )

    private fun call(name: String, args: JSONObject = JSONObject()) = RestOutcome.Ok(
        JSONObject().put(
            "candidates",
            JSONArray().put(
                JSONObject().put(
                    "content",
                    JSONObject().put("role", "model").put(
                        "parts",
                        JSONArray().put(
                            JSONObject().put("functionCall", JSONObject().put("name", name).put("args", args)),
                        ),
                    ),
                ),
            ),
        ).toString(),
    )

    private fun engine(
        vararg replies: RestOutcome,
        maxRounds: Int = TextChatEngine.MAX_ROUNDS,
        toolTimeoutMs: Long = 10_000,
        classify: (RestOutcome.HttpError) -> AgentResult = { AgentResult.Failed },
    ): TextChatEngine {
        val queue = ArrayDeque(replies.toList())
        return TextChatEngine(
            transport = { body ->
                sent.add(JSONObject(body.toString()))
                queue.removeFirstOrNull() ?: queue.lastOrNull() ?: error("no reply queued")
            },
            classify = classify,
            isMutating = { name, _ -> name.startsWith("log_") || name == "remember" },
            isReadThrough = { it == "ask_mail" },
            toolTimeoutMs = { toolTimeoutMs },
            maxRounds = maxRounds,
        )
    }

    /** An engine whose transport always calls a tool (for the round cap). */
    private fun alwaysCalling(maxRounds: Int): TextChatEngine = TextChatEngine(
        transport = { body ->
            sent.add(JSONObject(body.toString()))
            if (body.has("toolConfig")) text("Here is what I have.") else call("get_current_time")
        },
        classify = { AgentResult.Failed },
        isMutating = { _, _ -> false },
        isReadThrough = { false },
        maxRounds = maxRounds,
    )

    private val decls = JSONArray().put(JSONObject().put("name", "get_current_time"))
    private val calls = mutableListOf<Triple<String, JSONObject, Boolean>>()
    private fun executor(result: (String) -> JSONObject = { JSONObject().put("success", true) }) =
        TextToolExecutor { name, args, readThrough ->
            calls.add(Triple(name, args, readThrough))
            result(name)
        }

    @Test
    fun `a plain answer sends history then the message, with no knobs 3_8 Flash rejects and no search`() = runTest {
        val r = engine(text("It is ten past four.")).answer(
            systemParts = listOf("BASE", "LIVE"),
            history = listOf(ChatTurn(true, "hi"), ChatTurn(false, "Good evening.")),
            message = "what time is it",
            declarations = decls,
            executor = executor(),
        )
        assertEquals(TextTurnResult.Reply("It is ten past four.", emptyList(), false), r)
        val body = sent.single()
        val sys = body.getJSONObject("systemInstruction").getJSONArray("parts")
        assertEquals("BASE", sys.getJSONObject(0).getString("text"))
        assertEquals("LIVE", sys.getJSONObject(1).getString("text"))
        val contents = body.getJSONArray("contents")
        val roles = (0 until contents.length()).map { contents.getJSONObject(it).getString("role") }
        assertEquals(listOf("user", "model", "user"), roles)
        assertEquals(
            "what time is it",
            contents.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getString("text"),
        )
        assertFalse(body.has("generationConfig"))
        assertFalse(body.toString().contains("thinkingConfig"))
        assertFalse(body.toString().contains("googleSearch"))
        assertFalse(body.toString().contains("google_search"))
        assertFalse("tools must not be forced off on a normal round", body.has("toolConfig"))
        assertEquals(
            "get_current_time",
            body.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations")
                .getJSONObject(0).getString("name"),
        )
    }

    @Test
    fun `a tool call runs through the executor and its result goes back to the model`() = runTest {
        val r = engine(call("log_meal", JSONObject().put("food", "eggs")), text("Logged two eggs.")).answer(
            listOf("BASE"), emptyList(), "I had two eggs", decls, executor(),
        )
        assertEquals(TextTurnResult.Reply("Logged two eggs.", listOf("log_meal"), false), r)
        assertEquals("log_meal", calls.single().first)
        assertEquals("eggs", calls.single().second.getString("food"))
        val second = sent[1].getJSONArray("contents")
        assertEquals("model", second.getJSONObject(1).getString("role")) // the call echoed verbatim
        val fr = second.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse")
        assertEquals("log_meal", fr.getString("name"))
        assertTrue(fr.getJSONObject("response").getBoolean("success"))
    }

    @Test
    fun `a write is counted done only when its own result said success`() = runTest {
        val r = engine(call("log_meal"), text("I couldn't log that.")).answer(
            listOf("BASE"), emptyList(), "log eggs", decls,
            executor { JSONObject().put("success", false).put("message", "No meal named.") },
        ) as TextTurnResult.Reply
        assertTrue(r.writesDone.isEmpty())
    }

    @Test
    fun `a read-through tool raises the flag before the next tool runs, as on the voice path`() = runTest {
        val r = engine(call("ask_mail"), call("remember"), text("I can't keep mail.")).answer(
            listOf("BASE"), emptyList(), "remember what that email said", decls, executor(),
        )
        assertTrue(r.readThroughTouched)
        assertEquals(listOf("ask_mail" to true, "remember" to true), calls.map { it.first to it.third })
    }

    @Test
    fun `a turn with no read-through tool passes false`() = runTest {
        engine(call("remember"), text("Noted.")).answer(listOf("B"), emptyList(), "remember x", decls, executor())
        assertFalse(calls.single().third)
    }

    @Test
    fun `tools stay enabled up to the cap, then one tool-free backstop round forces an answer`() = runTest {
        val r = alwaysCalling(maxRounds = 3).answer(listOf("B"), emptyList(), "q", decls, executor())
        assertEquals("Here is what I have.", (r as TextTurnResult.Reply).text)
        assertEquals(4, sent.size)
        assertTrue(sent.take(3).none { it.has("toolConfig") })
        assertEquals(
            "NONE",
            sent[3].getJSONObject("toolConfig").getJSONObject("functionCallingConfig").getString("mode"),
        )
        // The nudge rides the function response that feeds the last tool-enabled round.
        val lastResponse = sent[2].getJSONArray("contents").let { it.getJSONObject(it.length() - 1) }
        assertTrue(lastResponse.toString().contains("Answer now with what you have."))
    }

    @Test
    fun `rate limit says so and that nothing was changed`() = runTest {
        val r = engine(RestOutcome.HttpError(429, "RESOURCE_EXHAUSTED"), classify = { AgentResult.RateLimited })
            .answer(listOf("B"), emptyList(), "q", decls, executor()) as TextTurnResult.Failed
        assertEquals(TextFailure.RATE_LIMITED, r.failure)
        assertEquals(
            "Not answered: Gemini's rate limit or quota was reached. Try again in a minute, or see Setup. " +
                "Nothing was changed.",
            r.words,
        )
    }

    @Test
    fun `each transport failure has its own words`() = runTest {
        val cases = listOf(
            RestOutcome.Network to TextFailure.OFFLINE,
            RestOutcome.HttpError(403, "") to TextFailure.KEY_INVALID,
            RestOutcome.HttpError(503, "") to TextFailure.OVERLOADED,
            RestOutcome.HttpError(400, "bad") to TextFailure.MODEL_ERROR,
        )
        for ((outcome, expected) in cases) {
            val classify: (RestOutcome.HttpError) -> AgentResult = {
                when (it.code) {
                    403 -> AgentResult.KeyInvalid
                    503 -> AgentResult.Overloaded
                    else -> AgentResult.Failed
                }
            }
            val r = engine(outcome, classify = classify)
                .answer(listOf("B"), emptyList(), "q", decls, executor()) as TextTurnResult.Failed
            assertEquals(expected, r.failure)
            assertTrue(r.words, r.words.startsWith(expected.lead))
        }
    }

    @Test
    fun `a failure after a successful write names the write as completed, never as undone`() = runTest {
        val r = engine(call("log_meal"), RestOutcome.Network)
            .answer(listOf("B"), emptyList(), "log eggs", decls, executor()) as TextTurnResult.Failed
        assertEquals(listOf("log_meal"), r.writesDone)
        assertEquals(
            "Not answered: couldn't reach Gemini. Check the connection and try again. " +
                "Completed before it stopped: Log meal.",
            r.words,
        )
    }

    @Test
    fun `a write that timed out is reported as not confirmed, not done`() = runTest {
        val slow = TextToolExecutor { _, _, _ ->
            delay(60_000)
            JSONObject().put("success", true)
        }
        val r = engine(call("log_meal"), RestOutcome.Network, toolTimeoutMs = 1_000)
            .answer(listOf("B"), emptyList(), "log eggs", decls, slow) as TextTurnResult.Failed
        assertTrue(r.writesDone.isEmpty())
        assertTrue(r.words, r.words.endsWith("Not confirmed: Log meal. Check before trying again."))
        // And the model was told it timed out, in words.
        assertTrue(sent[1].toString().contains("That took too long and timed out."))
    }

    @Test
    fun `a tool that throws comes back as a worded failure, not a crash`() = runTest {
        val r = engine(call("get_current_time"), text("I couldn't read the clock.")).answer(
            listOf("B"), emptyList(), "time?", decls, TextToolExecutor { _, _, _ -> error("boom") },
        )
        assertTrue(r is TextTurnResult.Reply)
        assertTrue(sent[1].toString().contains("Something went wrong running that."))
    }

    @Test
    fun `a reply with no text says no written reply came back`() = runTest {
        val empty = RestOutcome.Ok(JSONObject().put("candidates", JSONArray()).toString())
        val r = engine(empty).answer(listOf("B"), emptyList(), "q", decls, executor()) as TextTurnResult.Failed
        assertEquals(TextFailure.NO_TEXT, r.failure)
        assertEquals("No written reply came back for that. Nothing was changed.", r.words)
    }

    @Test
    fun `a blocked prompt says Gemini declined`() = runTest {
        val blocked = RestOutcome.Ok(
            JSONObject().put("promptFeedback", JSONObject().put("blockReason", "SAFETY")).toString(),
        )
        val r = engine(blocked).answer(listOf("B"), emptyList(), "q", decls, executor()) as TextTurnResult.Failed
        assertEquals(TextFailure.BLOCKED, r.failure)
    }

    @Test
    fun `the whole turn is bounded by the budget`() = runTest {
        val e = TextChatEngine(
            transport = { delay(10_000); text("late") },
            classify = { AgentResult.Failed },
            isMutating = { _, _ -> false },
            isReadThrough = { false },
            budgetMs = 5_000,
        )
        val r = e.answer(listOf("B"), emptyList(), "q", decls, executor()) as TextTurnResult.Failed
        assertEquals(TextFailure.TIMED_OUT, r.failure)
        assertEquals("Not answered: that took too long and was stopped. Nothing was changed.", r.words)
    }

    // --- history cap -------------------------------------------------------------------------

    @Test
    fun `history keeps the newest lines that fit the char cap, oldest first`() {
        val turns = (1..10).flatMap {
            listOf(ChatTurn(true, "q$it".padEnd(100, '.')), ChatTurn(false, "a$it".padEnd(100, '.')))
        }
        val capped = TextChatEngine.capHistory(turns, maxChars = 450, maxTurns = 30)
        assertEquals(4, capped.size)
        assertTrue(capped.first().fromUser)
        assertTrue(capped.first().text.startsWith("q9"))
        assertTrue(capped.last().text.startsWith("a10"))
    }

    @Test
    fun `history is also capped by line count`() {
        val turns = (1..50).flatMap { listOf(ChatTurn(true, "q$it"), ChatTurn(false, "a$it")) }
        val capped = TextChatEngine.capHistory(turns)
        assertEquals(TextChatEngine.HISTORY_MAX_TURNS, capped.size)
        assertEquals("a50", capped.last().text)
    }

    @Test
    fun `history starts on a user line and merges consecutive same-role lines`() {
        val capped = TextChatEngine.capHistory(
            listOf(
                ChatTurn(false, "Good evening."),
                ChatTurn(true, "what's on"),
                ChatTurn(true, "tomorrow"),
                ChatTurn(false, "Dentist at nine."),
                ChatTurn(false, ""),
            ),
        )
        assertEquals(listOf(ChatTurn(true, "what's on\ntomorrow"), ChatTurn(false, "Dentist at nine.")), capped)
    }

    @Test
    fun `the defaults are the stated cap`() {
        assertEquals(8_000, TextChatEngine.HISTORY_MAX_CHARS)
        assertEquals(30, TextChatEngine.HISTORY_MAX_TURNS)
        assertEquals("gemini-3.8-flash", TextChatEngine.MODEL)
    }
}
