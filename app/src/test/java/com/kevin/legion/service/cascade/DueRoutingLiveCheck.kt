@file:Suppress("MaxLineLength", "MagicNumber") // a by-hand measurement: one-line prints

package com.kevin.legion.service.cascade

import com.kevin.legion.ai.ALFRED
import com.kevin.legion.ai.DUE_ROUTING
import com.kevin.legion.ai.SHARED_INSTRUCTIONS
import com.kevin.legion.ai.openItemsParagraph
import com.kevin.legion.calendar.DueInput
import com.kevin.legion.calendar.DueList
import com.kevin.legion.calendar.DueReader
import com.kevin.legion.calendar.DueSource
import com.kevin.legion.service.ChatTurn
import com.kevin.legion.service.LiveToolbox
import com.kevin.legion.service.TextChatEngine
import com.kevin.legion.service.TextToolExecutor
import com.kevin.legion.service.tools.LiveSurface
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * NOT part of the suite: a by-hand check of "what's due" routing on real Gemini (2026-11-15), on a
 * key read from the file named by `LEGION_GEMINI_KEY_FILE` (never printed). Skipped unless set.
 *
 * Sends the three utterances below through the cascade's own text loop with the real tool surface
 * ([LiveToolbox.declarations] through [CascadeRules.declarations]) and the shared instructions plus
 * [DUE_ROUTING] and the open-list paragraph his prompt carries (the one that names read_list), on
 * both models, and prints which tool each call chose and the answer. The tools are
 * fakes answering from invented rows shaped like the 11-15 audit (two open assignments, one handed
 * in, a to-do due tomorrow, an overdue one): nothing on a phone is read. Costs a few cents.
 *
 * Read the output for: the tool is `day` with `read_calendar` (not `manage_item read_list`), and the
 * answer names the open items and the overdue one, never "quiet" or "clean".
 */
@RunWith(RobolectricTestRunner::class)
class DueRoutingLiveCheck {

    @Test
    fun `what's due routes to read_calendar and is read right, on both models`() = runBlocking<Unit> {
        val keyFile = System.getenv("LEGION_GEMINI_KEY_FILE").orEmpty()
        assumeTrue("LEGION_GEMINI_KEY_FILE not set: skipped", keyFile.isNotBlank())
        val key = File(keyFile).readText().trim()
        val instruction = ALFRED.clause.trimIndent() + " " + ALFRED.delivery + " " + SHARED_INSTRUCTIONS +
            "\n\n" + DUE_ROUTING + "\n\n" + openItemsParagraph(4) + "\n\n" +
            CascadePrompt.VOICE_CLAUSE
        val declarations = CascadeRules.declarations(LiveToolbox.declarations())
        for (model in MODELS) {
            for (utterance in UTTERANCES) {
                val calls = mutableListOf<String>()
                val loop = GeminiTextLoop(
                    transport = OkHttpGemini(key = { key }),
                    cache = null,
                    isMutating = { _, _ -> false },
                    isReadThrough = { _, _ -> false },
                )
                val contents = TextChatEngine.historyContents(listOf(ChatTurn(fromUser = true, text = PREAMBLE + utterance)))
                val t0 = System.currentTimeMillis()
                val r = loop.answer(
                    CascadePrefix(model, instruction, declarations),
                    contents,
                    TextToolExecutor { name, args, _ ->
                        val resolved = LiveSurface.resolve(name, args)
                        calls += "$name $args -> ${resolved.name}"
                        fakeTool(resolved.name)
                    },
                    object : GeminiTextLoop.Listener {},
                )
                println("LIVECHECK model=$model utterance=\"$utterance\" ms=${System.currentTimeMillis() - t0}")
                calls.forEach { println("LIVECHECK   tool $it") }
                println("LIVECHECK   answer: ${r.text.replace('\n', ' ')}${r.failureWords?.let { " failure=$it" } ?: ""}")
            }
        }
    }

    private fun fakeTool(name: String): JSONObject = when (name) {
        "read_calendar" -> calendar()
        "read_list" -> JSONObject().put("success", true).put("count", 3).put("open_count", 1).put(
            "items",
            JSONArray()
                .put(JSONObject().put("text", "Pay the phone bill").put("done", true).put("due", "Nov 1"))
                .put(JSONObject().put("text", "Book a haircut").put("done", true).put("due", "Nov 8"))
                .put(JSONObject().put("text", "Return the library book").put("done", false).put("due", "Nov 16")),
        )
        "get_current_time" -> JSONObject().put("success", true).put("now", "Sunday, November 15, 2026, 5:10 PM")
        else -> JSONObject().put("success", false).put("message", "Not available in this check; nothing was done.")
    }

    /** `read_calendar` for Nov 15-16 as the phone now answers it, from invented rows. */
    private fun calendar(): JSONObject {
        val today = LocalDate.of(2026, 11, 15)
        val now = today.atTime(17, 10).toInstant(ZoneOffset.UTC).toEpochMilli()
        fun task(title: String, done: Boolean) = DueInput(
            title, today.atTime(23, 59).toInstant(ZoneOffset.UTC).toEpochMilli(), false, today, done,
            DueSource.CALENDAR, whenLabel = "Nov 15, 2026 23:59",
        )
        val inputs = listOf(
            task("HIST 1301 Module 6 Quiz and Discussion", false),
            task("ALG 2401 Homework 11.1 to 11.6", false),
            task("ALG 2401 Chapter 11 Quiz", true),
            DueInput("Return the library book", 0L, true, today.plusDays(1), false, DueSource.TODO, whenLabel = "Nov 16"),
            DueInput("Gym waiver form", 1L, true, today.minusDays(5), false, DueSource.TODO, whenLabel = "Nov 10"),
        )
        val due = DueList.summarize(inputs, today, today.plusDays(1), today, now)
        val events = JSONArray()
        for (t in inputs.take(3)) {
            events.put(
                JSONObject().put("title", t.title).put("start", t.whenLabel).put("end", t.whenLabel)
                    .put("all_day", false).put("kind", "task").put("done", false),
            )
        }
        return JSONObject().put("success", true).put("due", DueReader.toJson(due)).put("events_count", 0)
            .put("count", 3).put("events", events)
            .put("rule", LiveSurface.RESULT_RULES["read_calendar"])
    }

    private companion object {
        val MODELS = listOf("gemini-3.5-flash-lite", "gemini-3.5-flash")
        val UTTERANCES = listOf(
            "What do I have due?",
            "What events are due or on the calendar today and tomorrow?",
            "What do I have due this week?",
        )
        const val PREAMBLE = "(Current context, use naturally if relevant:\nToday's date is Sunday, " +
            "November 15, 2026. It is 5:10 PM.)\n\n"
    }
}
