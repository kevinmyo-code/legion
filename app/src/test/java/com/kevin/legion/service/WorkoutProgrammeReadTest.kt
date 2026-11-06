package com.kevin.legion.service

import com.kevin.legion.bodycoach.CoachWriteBackend
import com.kevin.legion.bodycoach.PendingOp
import com.kevin.legion.bodycoach.SessionWire
import com.kevin.legion.bodycoach.WorkoutSessionController
import com.kevin.legion.bodycoach.WorkoutStore
import com.kevin.legion.finance.FinanceCache
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Workout reads (2026-11-06). Kevin's first voice session with workouts, on a Friday (phone A25,
 * 10:38), asked four things behind `ask_body`; the only read was today's, so Alfred said the next
 * day was Day A and that there was no Day B. This replays the four questions against the engine's
 * own body for that Friday (`engine_fixtures/workout_today_friday_calibration.json`, written by the
 * engine's `GET /api/body/coach/workout/` with today pinned to 2026-11-06; calibration week, so no
 * weights at all) and asserts each answer's facts are in the tool results.
 *
 * Under Robolectric only for `org.json`, which is stubbed in a plain JVM test.
 */
@RunWith(RobolectricTestRunner::class)
class WorkoutProgrammeReadTest {

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

    private val friday = LocalDate.of(2026, 11, 6)
    private var engineBody: String = fixture()
    private var online = true

    private fun fixture(): String =
        javaClass.classLoader!!.getResourceAsStream("engine_fixtures/workout_today_friday_calibration.json")!!
            .bufferedReader().use { it.readText() }

    @Before
    fun setUp() {
        val cache = MemCache()
        val store = MemStore()
        BodyCoachToolbox.workoutFactory = {
            WorkoutSessionController(
                backend = {
                    CoachWriteBackend { _, _, _ ->
                        if (online) {
                            Result.success(engineBody)
                        } else {
                            Result.failure(
                                com.kevin.legion.backend.engine.EngineHttpException(
                                    com.kevin.legion.backend.engine.EngineFailure.Unreachable("http://engine", "offline"),
                                ),
                            )
                        }
                    }
                },
                store = store,
                cache = cache,
                clock = { 1_000L },
            )
        }
        BodyCoachToolbox.phoneDate = { friday }
    }

    @After
    fun tearDown() {
        BodyCoachToolbox.workoutFactory = WorkoutSessionController::forApp
        BodyCoachToolbox.phoneDate = LocalDate::now
    }

    private fun call(name: String): JSONObject = runBlocking {
        BodyCoachToolbox.dispatch(RuntimeEnvironment.getApplication(), name)!!
    }

    private fun assertHas(result: JSONObject, vararg facts: String) {
        val text = result.toString()
        for (fact in facts) assertTrue("missing \"$fact\" in $text", text.contains(fact))
    }

    @Test
    fun `Q1 how about my workouts - today is Day A, its five exercises, none done this week`() {
        val today = call("get_todays_workout")
        assertTrue(today.getBoolean("success"))
        assertEquals("A", today.getString("next_day"))
        assertEquals(0, today.getInt("sessions_done_this_week"))
        assertHas(today, "Back squat 3 x 6-8 @ your pick", "Bench press 3 x 6-8 @ your pick", "Barbell row", "Lateral raise", "Plank 2 x 30-45 s")
        // and "the one after" is in today's read too
        assertEquals("Day B, Monday 2026-11-09", today.getString("next_session_after_today"))
    }

    @Test
    fun `Q2 how many sessions a week and what's the next day - three, and Day B on Monday`() {
        val p = call("get_workout_programme")
        assertTrue(p.getBoolean("success"))
        assertEquals(3, p.getInt("sessions_per_week"))
        // Body coach 2 (Kevin, 2026-11-06): days are flexible, so no weekday list is said as a schedule.
        assertFalse(p.has("training_days"))
        // today is a training day and Day A is not done yet, so the next session counts today...
        assertEquals("Day A, today, Friday 2026-11-06", p.getString("next_session"))
        assertEquals("a session is due", p.getJSONObject("today").getString("status"))
        // ...and the next day after today is Day B on Monday, which is what Kevin asked
        assertEquals("Day B, Monday 2026-11-09", p.getString("next_session_after_today"))
    }

    @Test
    fun `Q3 the workouts on all three sessions - A Friday, B Monday, A Wednesday, both days in full`() {
        val p = call("get_workout_programme")
        val upcoming = p.getJSONArray("upcoming")
        assertEquals(3, upcoming.length())
        assertEquals("Day A, today, Friday 2026-11-06", upcoming.getString(0))
        assertEquals("Day B, Monday 2026-11-09", upcoming.getString(1))
        assertEquals("Day A, Wednesday 2026-11-11", upcoming.getString(2))
        val days = p.getJSONArray("days")
        assertEquals(2, days.length())
        assertEquals("Day A", days.getJSONObject(0).getString("day"))
        assertEquals("Day B", days.getJSONObject(1).getString("day"))
        assertHas(
            p,
            "Back squat 3 x 6-8 @ your pick", "Bench press 3 x 6-8 @ your pick", "Barbell row 3 x 8-10 @ your pick",
            "Lateral raise 2 x 12-15 @ your pick", "Plank 2 x 30-45 s",
            "Romanian deadlift 3 x 8-10 @ your pick", "Overhead press 3 x 6-8 @ your pick",
            "Lat pulldown 3 x 8-10 @ your pick", "Dumbbell lunge 2 x 10-12 @ your pick", "Hanging knee raise 2 x 10-15",
        )
    }

    @Test
    fun `Q4 Day B - it exists, with its exercises and the Monday it next comes round`() {
        val p = call("get_workout_programme")
        val b = p.getJSONArray("days").getJSONObject(1)
        assertEquals("Day B", b.getString("day"))
        assertEquals("Monday 2026-11-09", b.getString("next_on"))
        assertEquals(5, b.getJSONArray("exercises").length())
        assertEquals("Romanian deadlift 3 x 8-10 @ your pick", b.getJSONArray("exercises").getString(0))
        assertTrue(b.getBoolean("calibrating"))
        assertHas(p, "calibration")
    }

    @Test
    fun `the router's text sends the four questions to the programme read`() {
        val decls = BodyCoachToolbox.declarations()
        val programme = (0 until decls.length()).map { decls.getJSONObject(it) }
            .single { it.getString("name") == "get_workout_programme" }.getString("description")
        for (phrase in listOf("Day B", "next workout", "sessions a week", "what are my workouts", "all three sessions", "next day")) {
            assertTrue("get_workout_programme's description lacks \"$phrase\"", programme.contains(phrase))
        }
        val today = (0 until decls.length()).map { decls.getJSONObject(it) }
            .single { it.getString("name") == "get_todays_workout" }.getString("description")
        assertTrue(today.contains("get_workout_programme"))
        assertTrue(LiveToolbox.DISPATCHED.getValue("body").contains("get_workout_programme"))
    }

    @Test
    fun `an engine that sends no programme days says unknown, never that Day B does not exist`() {
        val old = JSONObject(engineBody)
        old.remove("days")
        old.remove("upcoming")
        engineBody = old.toString()
        val p = call("get_workout_programme")
        assertFalse(p.getBoolean("success"))
        val message = p.getString("message")
        assertTrue(message, message.contains("unknown here, not missing"))
        assertTrue(message, message.contains("Never say a day does not exist"))
        assertFalse(p.has("next_session"))
    }

    @Test
    fun `offline the saved copy is said as saved, and a date already past is flagged`() {
        call("get_workout_programme")
        online = false
        BodyCoachToolbox.phoneDate = { friday.plusDays(1) }
        val p = call("get_workout_programme")
        assertTrue(p.getBoolean("success"))
        assertTrue(p.has("saved_copy"))
        assertEquals("Day A, a date already past (this copy is out of date), Friday 2026-11-06", p.getString("next_session"))
        assertEquals("Day B, Monday 2026-11-09", p.getString("next_session_after_today"))
    }

    @Test
    fun `tomorrow is said as tomorrow`() {
        BodyCoachToolbox.phoneDate = { LocalDate.of(2026, 11, 8) }
        val p = call("get_workout_programme")
        assertEquals("Day B, tomorrow, Monday 2026-11-09", p.getString("next_session_after_today"))
    }
}
