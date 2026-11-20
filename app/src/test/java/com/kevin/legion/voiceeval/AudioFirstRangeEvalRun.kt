@file:Suppress("MagicNumber", "MaxLineLength", "LongMethod") // a by-hand run: one-line logs

package com.kevin.legion.voiceeval

import com.kevin.legion.ai.AriaBrain
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.service.LiveToolbox
import com.kevin.legion.service.TextToolExecutor
import com.kevin.legion.service.cascade.AudioFirstGate
import com.kevin.legion.service.cascade.AudioFirstRound
import com.kevin.legion.service.cascade.CascadePrefix
import com.kevin.legion.service.cascade.CascadePrompt
import com.kevin.legion.service.cascade.CascadeRequests
import com.kevin.legion.service.cascade.CascadeRules
import com.kevin.legion.service.cascade.CascadeSession
import com.kevin.legion.service.cascade.GeminiStt
import com.kevin.legion.service.cascade.GeminiTextLoop
import com.kevin.legion.service.cascade.OkHttpGemini
import com.kevin.legion.service.cascade.SentenceSplitter
import com.kevin.legion.service.cascade.StreamedTurn
import com.kevin.legion.service.cascade.SttResult
import com.kevin.legion.service.tools.LiveSurface
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * NOT part of the suite (needs `LEGION_GEMINI_KEY_FILE` and `LEGION_AUDIO_FIRST_RANGE=1`): two
 * audio-first measurements on made-up clips (stock voice).
 *
 * 1. Range reads, repeated: five reads whose answer depends on a date range or a filter, each with the
 *    range its words call for written down first ([RANGE_CASES]); round 1 runs [REPEATS] times on the
 *    text path (one transcript, reused) and [REPEATS] times on the audio path. Hits out of [REPEATS].
 * 2. Time to the first speakable sentence on the 10 read/plain-reply cases, both paths, through the real
 *    [GeminiTextLoop] (a fake tool result, so round 2 runs): end of speech -> the first whole sentence
 *    the loop hands on ([SentenceSplitter]). Text path: transcript, then the loop. Audio path: the loop
 *    at once, the transcript beside it (round 2 waits for it), plus the session's first-audio hold
 *    (up to [AudioFirstGate.GRACE_MS] for the transcript) as a second number.
 */
@RunWith(RobolectricTestRunner::class)
class AudioFirstRangeEvalRun {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun reset() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @After
    fun drain() {
        CompanionProfile.saveGeminiKey(context, "")
        GeminiKeyProvider.init(context)
        RoomTestReset.drainArchDiskIoPool()
    }

    /** One range read and the range its words ask for, judged on round 1's calls (resolved names). */
    private class RangeCase(val id: String, val say: String, val expected: String, val hit: (List<Pair<String, JSONObject>>, LocalDate) -> Boolean)

    private fun window(calls: List<Pair<String, JSONObject>>): Pair<LocalDate, LocalDate>? {
        val a = calls.firstOrNull { it.first == "read_calendar" }?.second ?: return null
        val from = runCatching { LocalDate.parse(a.optString("from")) }.getOrNull() ?: return null
        val to = runCatching { LocalDate.parse(a.optString("to").ifBlank { a.optString("from") }) }.getOrNull() ?: from
        return from to to
    }

    private val rangeCases = listOf(
        RangeCase("due_open", "what do I have due?", "from <= today, to >= today+6 (the coming week)") { c, d ->
            window(c)?.let { (f, t) -> !f.isAfter(d) && !t.isBefore(d.plusDays(6)) } == true
        },
        RangeCase("overdue", "is anything overdue?", "from <= today, today-1 <= to <= today (the past, not ahead)") { c, d ->
            window(c)?.let { (f, t) -> !f.isAfter(d) && !t.isBefore(d.minusDays(1)) && !t.isAfter(d) } == true
        },
        RangeCase("due_this_week", "what's due this week", "from <= today, to >= this Sunday") { c, d ->
            val sunday = d.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
            window(c)?.let { (f, t) -> !f.isAfter(d) && !t.isBefore(sunday) } == true
        },
        RangeCase("spend_last_month", "what did I spend last month", "a money read naming last month (or its month name)") { c, d ->
            val month = d.minusMonths(1).month.name.lowercase()
            c.any { (n, a) -> n.contains("money") || n.contains("transaction") || n == "records" } &&
                c.any { (_, a) -> a.toString().lowercase().let { it.contains("last month") || it.contains(month) || it.contains(d.minusMonths(1).toString().take(7)) } }
        },
        RangeCase("appointments_tomorrow", "any appointments tomorrow", "from and to both tomorrow (from >= today, to <= tomorrow)") { c, d ->
            val tm = d.plusDays(1)
            window(c)?.let { (f, t) -> !f.isAfter(tm) && !t.isBefore(tm) && !f.isBefore(d) && !t.isAfter(tm) } == true
        },
    )

    private val speakCases = listOf(
        "what do I have due?", "is anything overdue?", "what's on my calendar tomorrow", "what reminders do I have",
        "what's on my to-do list", "which cars do I have on file", "what time is it", "when is the Ranger due for an oil change",
        "how is the Ranger's battery doing", "what's my checking balance",
    )

    @Test
    fun `range reads repeated and time to the first speakable sentence`() = runBlocking<Unit> {
        val keyFile = System.getenv("LEGION_GEMINI_KEY_FILE")
        assumeTrue("LEGION_GEMINI_KEY_FILE and LEGION_AUDIO_FIRST_RANGE not both set: skipped", keyFile != null && System.getenv("LEGION_AUDIO_FIRST_RANGE") == "1")
        check(!EngineConfig(context).isConfigured()) { "an engine is configured in this JVM: refusing to run" }
        val key = File(keyFile!!).readText().trim()
        val model = System.getenv("LEGION_VOICE_EVAL_MODEL") ?: CascadeSession.MODEL
        CompanionProfile.saveGeminiKey(context, key)
        GeminiKeyProvider.init(context)
        VoiceEvalFixture.seed(context)
        val brain = AriaBrain.get(context)
        val split = CascadePrompt.split(brain.buildBaseInstruction())
        val prefix = CascadePrefix(model, split.cacheable, CascadeRules.declarations(LiveToolbox.declarations()))
        val preamble = CascadePrompt.preamble(split.volatile, brain.buildLiveContext())
        val transport = OkHttpGemini(key = { key })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val harness = VoiceEvalHarness(context, transport, null, prefix, preamble, out = { _, _ -> })
        val stt = GeminiStt(transport, scope)
        val today = LocalDate.now()
        val lines = mutableListOf<String>()
        fun say(l: String) {
            lines += l
            println("AF_RANGE $l")
        }
        try {
            // 1. Range reads, repeated.
            for (c in rangeCases) {
                val pcm = harness.spokenClip(c.say)
                val heard = (stt.transcribe(pcm) as? SttResult.Text)?.text.orEmpty()
                var textHits = 0
                var audioHits = 0
                val textWins = mutableListOf<String>()
                val audioWins = mutableListOf<String>()
                repeat(REPEATS) {
                    val t = calls(transport, prefix, CascadePrompt.contents(emptyList(), heard, preamble))
                    val a = calls(transport, prefix, AudioFirstRound.contents(emptyList(), pcm, preamble))
                    if (c.hit(t, today)) textHits++
                    if (c.hit(a, today)) audioHits++
                    textWins += show(t)
                    audioWins += show(a)
                }
                say("range case=${c.id} expected=\"${c.expected}\" today=$today text_hits=$textHits/$REPEATS audio_hits=$audioHits/$REPEATS heard_words=${heard.split(' ').size}")
                say("range case=${c.id} text=$textWins")
                say("range case=${c.id} audio=$audioWins")
            }
            // 2. First speakable sentence, both paths.
            val textMs = mutableListOf<Long>()
            val audioMs = mutableListOf<Long>()
            val audioHeldMs = mutableListOf<Long>()
            for (line in speakCases) {
                val pcm = harness.spokenClip(line)
                // Text path: end of speech -> transcript -> loop -> first sentence.
                val t0 = System.currentTimeMillis()
                val heard = (stt.transcribe(pcm) as? SttResult.Text)?.text.orEmpty()
                val sttText = System.currentTimeMillis() - t0
                val textTotal = sttText + firstSentence(transport, prefix, CascadePrompt.contents(emptyList(), heard, preamble), null)
                // Audio path: the loop at once, the transcript beside it.
                val a0 = System.currentTimeMillis()
                val words = CompletableDeferred<String?>()
                var sttAt = 0L
                val sttJob = scope.async {
                    val r = (stt.transcribe(pcm) as? SttResult.Text)?.text
                    sttAt = System.currentTimeMillis() - a0
                    words.complete(r)
                }
                val tAudio = firstSentence(transport, prefix, AudioFirstRound.contents(emptyList(), pcm, preamble), words)
                sttJob.await()
                val held = maxOf(tAudio, minOf(sttAt, tAudio + AudioFirstGate.GRACE_MS))
                textMs += textTotal
                audioMs += tAudio
                audioHeldMs += held
                say("speak line_words=${line.split(' ').size} text_ms=$textTotal audio_ms=$tAudio audio_with_hold_ms=$held stt_ms=$sttAt")
            }
            say(
                "speak summary text_median=${median(textMs)} text_worst=${textMs.max()} audio_median=${median(audioMs)} " +
                    "audio_worst=${audioMs.max()} audio_hold_median=${median(audioHeldMs)} audio_hold_worst=${audioHeldMs.max()} " +
                    "gain_median=${median(textMs) - median(audioHeldMs)}",
            )
        } finally {
            scope.cancel()
            val out = File("build/voice-eval/audio-first-range-$model.txt")
            out.parentFile?.mkdirs()
            out.writeText(lines.joinToString("\n", postfix = "\n"))
        }
    }

    /** From the loop's start to the first whole sentence it hands on (the caller adds the transcript on the text path). */
    private suspend fun firstSentence(
        transport: OkHttpGemini,
        prefix: CascadePrefix,
        contents: org.json.JSONArray,
        words: CompletableDeferred<String?>?,
    ): Long {
        val t0 = System.currentTimeMillis()
        val splitter = SentenceSplitter()
        var first = -1L
        val loop = GeminiTextLoop(transport, null, isMutating = CascadeRules::isWrite, isReadThrough = CascadeRules::isReadThrough)
        val tool = TextToolExecutor { _, _, _ -> JSONObject().put("success", true).put("note", "invented result for the eval: nothing found") }
        loop.answer(
            prefix, contents, tool,
            object : GeminiTextLoop.Listener {
                override fun onText(delta: String) {
                    if (first < 0 && splitter.feed(delta).isNotEmpty()) first = System.currentTimeMillis() - t0
                }
            },
            userWords = words?.let { w -> suspend { w.await() } },
        )
        if (first < 0) first = System.currentTimeMillis() - t0
        return first
    }

    private suspend fun calls(transport: OkHttpGemini, prefix: CascadePrefix, contents: org.json.JSONArray): List<Pair<String, JSONObject>> {
        val turn = StreamedTurn()
        transport.stream(prefix.model, CascadeRequests.inline(prefix, contents, false)) { turn.onEvent(it) {} }
        return turn.calls.map { LiveSurface.resolve(it.name, it.args).let { r -> r.name to r.args } }
    }

    private fun show(c: List<Pair<String, JSONObject>>): String =
        c.joinToString("+") { (n, a) -> if (n == "read_calendar") "$n(${a.optString("from")}..${a.optString("to")})" else n }

    private fun median(xs: List<Long>): Long = xs.sorted().let { if (it.isEmpty()) -1 else it[it.size / 2] }

    private companion object {
        const val REPEATS = 6
    }
}
