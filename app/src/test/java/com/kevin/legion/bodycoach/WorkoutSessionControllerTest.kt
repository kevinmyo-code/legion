package com.kevin.legion.bodycoach

import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.finance.FinanceCache
import com.kevin.legion.finance.FinanceRead
import com.kevin.legion.finance.valueOrNull
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * workouts, 2026-11-06: the phone's workout controller. Ticks land on the phone first and replay to
 * the engine in order; a start with no signal is built from the engine's own saved plan; name
 * matching and swaps need the engine and say so. Synthetic weights only.
 */
class WorkoutSessionControllerTest {

    private class MemCache : FinanceCache {
        val map = mutableMapOf<String, Pair<String, Long>>()
        override fun read(key: String) = map[key]
        override fun write(key: String, body: String, savedAtMs: Long) {
            map[key] = body to savedAtMs
        }
    }

    private class MemStore : WorkoutStore {
        var s: SessionWire? = null
        var ops: List<PendingOp> = emptyList()
        override fun session() = s
        override fun saveSession(session: SessionWire?) { s = session }
        override fun pending() = ops
        override fun savePending(ops: List<PendingOp>) { this.ops = ops }
    }

    private val cache = MemCache()
    private val store = MemStore()
    private var online = true
    private var refuse: Pair<Int, String>? = null
    private val sent = mutableListOf<PendingOp>()
    private val replies = ArrayDeque<String>()

    private val controller = WorkoutSessionController(
        backend = {
            CoachWriteBackend { method, path, json ->
                when {
                    !online -> Result.failure(EngineHttpException(EngineFailure.Unreachable("http://engine", "offline")))
                    refuse != null -> Result.failure(EngineHttpException(EngineFailure.Refused(refuse!!.first, refuse!!.second)))
                    else -> {
                        sent += PendingOp(method, path, json ?: "")
                        Result.success(replies.removeFirstOrNull() ?: """{"words": "ok"}""")
                    }
                }
            }
        },
        store = store,
        cache = cache,
        clock = { 1_000L },
    )

    private val plan = PlanWire(
        dayKey = "A",
        setCount = 5,
        exercises = listOf(
            TargetWire("bench_press", "Bench press", sets = 3, repsLow = 6, repsHigh = 8, weightLbs = 100.0, rule = "hold"),
            TargetWire("plank", "Plank", kind = "timed", sets = 2, repsLow = 30, repsHigh = 45, unit = "seconds", rule = "reps"),
        ),
    )
    private val todayBody = """{"training_day": true, "week": {"done": 1, "planned": 3},
        "next": ${bodyCoachJson.encodeToString(PlanWire.serializer(), plan)}, "words": "Today's workout is Day A."}"""

    private fun sessionReply(id: String, status: String = "in_progress", words: String = "w"): String {
        val s = WorkoutSessionController.localSession(plan, id, "2026-11-06").copy(status = status)
        return """{"session": ${bodyCoachJson.encodeToString(SessionWire.serializer(), s)}, "words": "$words"}"""
    }

    @Test
    fun `today reads the engine and saves a copy for a start with no signal`() = runBlocking {
        replies += todayBody
        val read = controller.today()
        assertTrue(read is FinanceRead.Fresh)
        assertEquals(1, read.valueOrNull?.week?.done)
        assertEquals("GET", sent.single().method)
        assertTrue(cache.map.containsKey("workout_today"))
    }

    @Test
    fun `a start online adopts the engine's session`() = runBlocking {
        replies += sessionReply("engine-id", words = "Started Day A.")
        val step = controller.start()
        assertTrue(step.ok)
        assertFalse(step.queued)
        assertEquals("Started Day A.", step.sentence)
        assertEquals("engine-id", store.s?.id)
        assertTrue(store.ops.isEmpty())
        assertTrue(sent.single().body.contains("\"id\""))
    }

    @Test
    fun `a start with no signal runs from the saved plan and replays later in order`() = runBlocking {
        replies += todayBody
        controller.today()
        sent.clear()
        online = false
        val step = controller.start()
        assertTrue(step.ok && step.queued)
        assertTrue(step.sentence.contains("on this phone"))
        val local = store.s!!
        assertEquals(5, local.sets.size)
        assertNull(local.sets.first().reps)

        val tick = controller.tick("bench_press", 1, true, null, null)
        assertTrue(tick.queued)
        assertEquals(1, store.s!!.setsDone)
        assertEquals(8, store.s!!.sets.first().reps)
        assertEquals(100.0, store.s!!.sets.first().weightLbs)
        assertEquals(2, store.ops.size)

        online = true
        replies += sessionReply(local.id)
        replies += sessionReply(local.id)
        replies += todayBody
        controller.today()
        assertEquals(listOf("POST", "PATCH", "GET"), sent.map { it.method })
        assertTrue(sent[0].body.contains(local.id))
        assertTrue(store.ops.isEmpty())
    }

    @Test
    fun `a start with no signal and no saved plan starts nothing and says why`() = runBlocking {
        online = false
        val step = controller.start()
        assertFalse(step.ok)
        assertTrue(step.sentence.startsWith("Nothing was started"))
        assertNull(store.s)
        assertTrue(store.ops.isEmpty())
    }

    @Test
    fun `finish offline keeps the session closed on the phone until the engine has it`() = runBlocking {
        replies += sessionReply("s1")
        controller.start()
        online = false
        val step = controller.finish()
        assertTrue(step.ok && step.queued)
        assertEquals(SessionWire.STATUS_FINISHED, store.s?.status)
        online = true
        replies += sessionReply("s1", status = "finished", words = "Logged Day A: 0 of 5 sets.")
        replies += todayBody
        controller.today()
        assertNull(store.s)
        assertTrue(store.ops.isEmpty())
    }

    @Test
    fun `a refused write is dropped and its sentence reported, not retried forever`() = runBlocking {
        replies += sessionReply("s1")
        controller.start()
        refuse = 404 to """{"detail": "Nothing was saved. There is no workout with that id."}"""
        val step = controller.tick("bench_press", 2, true, 7, 95.0)
        assertFalse(step.ok)
        assertEquals("Nothing was saved. There is no workout with that id.", step.sentence)
        assertTrue(store.ops.isEmpty())
    }

    @Test
    fun `log by name and swap need the engine and say nothing happened without it`() = runBlocking {
        online = false
        val log = controller.logByName("bench", 8, 135.0)
        assertFalse(log.ok)
        assertTrue(log.sentence.startsWith("Nothing was logged"))
        val swap = controller.swap("squats")
        assertFalse(swap.ok)
        assertTrue(swap.sentence.startsWith("Nothing was swapped"))
    }

    @Test
    fun `no engine at all says so`() = runBlocking {
        val none = WorkoutSessionController(backend = { null }, store = store, cache = cache)
        val read = none.today()
        assertTrue(read is FinanceRead.Unavailable)
        assertFalse(none.setUpProgramme().ok)
    }

    @Test
    fun `an untick keeps what was done and recounts`() {
        val s = WorkoutSessionController.localSession(plan, "x", "2026-11-06")
        val ticked = WorkoutSessionController.applyTick(s, "bench_press", 2, true, 6, 95.0)
        assertEquals(1, ticked.setsDone)
        val unticked = WorkoutSessionController.applyTick(ticked, "bench_press", 2, false, null, null)
        assertEquals(0, unticked.setsDone)
        assertEquals(6, unticked.sets[1].reps)
    }

    @Test
    fun `targets read as sets x reps at weight, your pick when calibrating`() {
        assertEquals("Bench press 3 x 6-8 @ 100 lb", WorkoutText.target(plan.exercises[0]))
        assertEquals("Plank 2 x 30-45 s", WorkoutText.target(plan.exercises[1]))
        assertEquals(
            "Back squat 3 x 6-8 @ your pick",
            WorkoutText.target(TargetWire("back_squat", "Back squat", sets = 3, repsLow = 6, repsHigh = 8, calibrating = true)),
        )
    }
}
