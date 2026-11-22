package com.kevin.legion.service.turn

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One voice agent (Kevin, 2026-11-22: "all my voice agents should be the same"): the phone's turn
 * numbers against `shared/turn/spec.json`, and every case in `shared/turn/cases.json` through
 * [EndOfTurnPolicy] and [BargeInGate]. The web's `turn-spec.node.test.ts` reads the same two files,
 * so a number or a rule that moves on one client and not the other fails a build. Found by walking
 * up from the test's working directory, as the Tetris fixtures are.
 */
class SharedTurnSpecTest {

    private fun shared(name: String): JsonObject {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "shared/turn/$name")
            if (candidate.isFile) return Json.parseToJsonElement(candidate.readText()).jsonObject
            dir = dir.parentFile
        }
        error("shared/turn/$name not found above ${System.getProperty("user.dir")}")
    }

    private val spec = shared("spec.json")
    private val cases = shared("cases.json")

    private fun JsonObject.num(key: String): Double = getValue(key).jsonPrimitive.double

    @Test
    fun `end of turn numbers match the shared spec`() {
        val e = spec.getValue("end_of_turn").jsonObject
        val mine = mapOf(
            "window_ms" to EndOfTurnPolicy.WINDOW_MS.toDouble(),
            "start_prob" to EndOfTurnPolicy.START_PROB.toDouble(),
            "end_prob" to EndOfTurnPolicy.END_PROB.toDouble(),
            "confirm_ms" to EndOfTurnPolicy.CONFIRM_MS.toDouble(),
            "gate_ms" to EndOfTurnPolicy.GATE_MS.toDouble(),
            "base_ms" to EndOfTurnPolicy.BASE_MS.toDouble(),
            "finished_ms" to EndOfTurnPolicy.FINISHED_MS.toDouble(),
            "cap_ms" to EndOfTurnPolicy.CAP_MS.toDouble(),
            "threshold" to EndOfTurnPolicy.THRESHOLD.toDouble(),
            "early_threshold" to EndOfTurnPolicy.EARLY_THRESHOLD.toDouble(),
            "early_ms" to EndOfTurnPolicy.EARLY_MS.toDouble(),
        )
        assertEquals(e.keys, mine.keys)
        for ((key, value) in mine) assertEquals(key, e.num(key), value, 1e-6)
    }

    @Test
    fun `barge-in numbers match the shared spec`() {
        val b = spec.getValue("barge_in").jsonObject
        val mine = mapOf(
            "window_ms" to BargeInGate.WINDOW_MS.toDouble(),
            "prob" to BargeInGate.PROB.toDouble(),
            "speech_prob" to BargeInGate.SPEECH_PROB.toDouble(),
            "sustain_ms" to BargeInGate.SUSTAIN_MS.toDouble(),
            "span_windows" to BargeInGate.SPAN_WINDOWS.toDouble(),
            "echo_margin" to BargeInGate.ECHO_MARGIN.toDouble(),
            "echo_percentile" to BargeInGate.ECHO_PERCENTILE.toDouble(),
            "default_echo_floor" to BargeInGate.DEFAULT_ECHO_FLOOR.toDouble(),
            "min_level" to BargeInGate.MIN_LEVEL.toDouble(),
            "learn_windows" to BargeInGate.LEARN_WINDOWS.toDouble(),
            "user_guard_ms" to BargeInGate.USER_GUARD_MS.toDouble(),
            "verdict_ms" to BargeInGate.VERDICT_MS.toDouble(),
            "self_speech_ms" to BargeInGate.SELF_SPEECH_MS.toDouble(),
            "max_self_interrupts" to BargeInGate.MAX_SELF_INTERRUPTS.toDouble(),
            "clearly_user_factor" to BargeInGate.CLEARLY_USER_FACTOR.toDouble(),
            "warmup_ms" to BargeInGate.WARMUP_MS.toDouble(),
            "warmup_windows" to BargeInGate.WARMUP_WINDOWS.toDouble(),
            // ADR 0064 amendment 2026-10-28: a barge-in seeds the turn with the span plus one window.
            "pre_roll_windows" to (BargeInGate.SPAN_WINDOWS + 1).toDouble(),
        )
        assertEquals(b.keys, mine.keys)
        for ((key, value) in mine) assertEquals(key, b.num(key), value, 1e-6)
    }

    @Test
    fun `every shared end of turn case`() {
        for (c in cases.getValue("policy").jsonArray) {
            val case = c.jsonObject
            assertEquals(case.name(), case.expected(), runPolicy(case))
        }
    }

    @Test
    fun `every shared barge-in case`() {
        for (c in cases.getValue("gate").jsonArray) {
            val case = c.jsonObject
            assertEquals(case.name(), case.expected(), runGate(case))
        }
    }

    private fun JsonObject.name() = getValue("name").jsonPrimitive.content

    private fun JsonObject.expected() = getValue("expect").jsonArray.map { it.jsonPrimitive.content }

    private fun JsonObject.intOr(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    private fun runPolicy(case: JsonObject): List<String> {
        val config = case.getValue("config").jsonObject
        val finished = config.getValue("finished").jsonPrimitive.boolean
        val run = PolicyRun(
            EndOfTurnPolicy(
                smartAvailable = config.getValue("smart").jsonPrimitive.boolean,
                baseMs = EndOfTurnPolicy.BASE_MS,
                finishedMs = if (finished) EndOfTurnPolicy.FINISHED_MS else EndOfTurnPolicy.BASE_MS,
                earlyEnd = config.getValue("early_end").jsonPrimitive.boolean,
            ),
        )
        for (element: JsonElement in case.getValue("steps").jsonArray) run.step(element.jsonObject)
        return run.out
    }

    /** One case through the policy: each step's actions, written as cases.json writes them. */
    private inner class PolicyRun(private val policy: EndOfTurnPolicy) {
        val out = mutableListOf<String>()
        private var lastCheck = 0

        fun step(s: JsonObject) {
            when {
                "speech" in s -> repeat(s.intOr("speech")!!) { take(policy.onWindow(SPEECH)) }
                "quiet" in s -> repeat(s.intOr("quiet")!!) { take(policy.onWindow(QUIET)) }
                "prob" in s -> repeat(s.intOr("n") ?: 1) { take(policy.onWindow(s.float("prob"))) }
                "score" in s -> take(policy.onCheckResult(lastCheck, s.float("score")))
                "fail" in s -> take(policy.onCheckFailed(lastCheck))
            }
        }

        private fun take(actions: List<EndOfTurnPolicy.Action>) {
            for (a in actions) {
                out += when (a) {
                    is EndOfTurnPolicy.Action.StartActivity -> "start"
                    is EndOfTurnPolicy.Action.RequestCheck -> {
                        lastCheck = a.checkId
                        "check@${a.silenceMs}"
                    }
                    is EndOfTurnPolicy.Action.SpeechResumed -> "resumed"
                    is EndOfTurnPolicy.Action.EndActivity -> "end:${a.reason.wire}@${a.silenceMs}"
                }
            }
        }
    }

    private fun JsonObject.float(key: String): Float = getValue(key).jsonPrimitive.double.toFloat()

    private fun runGate(case: JsonObject): List<String> {
        val gate = BargeInGate()
        val out = mutableListOf<String>()
        for (element in case.getValue("steps").jsonArray) {
            val s = element.jsonObject
            when {
                "reply" in s -> if (s.getValue("reply").jsonPrimitive.contentOrNull == "start") {
                    gate.onReplyStart()
                } else {
                    gate.onReplyEnd()
                }
                "warm_up" in s -> gate.startWarmUp()
                "user_turn_end" in s -> gate.onUserTurnEnd()
                else -> {
                    val prob = s.float("prob")
                    val level = s.intOr("level")!!
                    val playing = s["playing"]?.jsonPrimitive?.booleanOrNull ?: true
                    repeat(s.intOr("n") ?: 1) { gateLine(gate.onWindow(prob, level, playing))?.let { out += it } }
                }
            }
        }
        return out
    }

    private fun gateLine(e: BargeInGate.Event?): String? = when (e) {
        is BargeInGate.Event.Fire -> "fire level=${e.level} threshold=${e.threshold}"
        is BargeInGate.Event.Verdict ->
            "verdict self=${e.selfInterrupt} count=${e.selfInterrupts} disabled=${e.nowDisabled}"
        is BargeInGate.Event.Ignored -> "ignored ${e.reason}"
        null -> null
    }

    private companion object {
        const val SPEECH = 0.9f
        const val QUIET = 0.05f
    }
}
