package com.kevin.legion.bodycoach

import android.content.Context
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttp
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.finance.FinanceCache
import com.kevin.legion.finance.FinanceRead
import com.kevin.legion.finance.FinanceWords
import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.Serializable

/** What a workout action did, in words. [ok] means the engine has it; [queued] means it is saved
 * on this phone and goes to the engine when it can be reached. Never both false and claimed. */
data class WorkoutStep(
    val ok: Boolean,
    val sentence: String,
    val session: SessionWire? = null,
    val queued: Boolean = false,
)

/** The running workout and the writes the engine has not got yet. A seam for a fake in tests. */
interface WorkoutStore {
    fun session(): SessionWire?
    fun saveSession(session: SessionWire?)
    fun pending(): List<PendingOp>
    fun savePending(ops: List<PendingOp>)
}

/** One queued engine write, replayed in order. Every route it names is idempotent. */
@Serializable
data class PendingOp(val method: String, val path: String, val body: String)

/**
 * The workout on the phone (workouts, 2026-11-06): the Body screen's session view and the voice
 * tools call this one controller (ADR 0035), and it calls the engine's routes
 * (`server/body_coach/workout_views.py`) - **the engine owns every target and every log.**
 *
 * The gym may have no signal, so a tick never waits on the engine (CLAUDE.md section 7: queue the
 * write and say so). The phone mints the session id, every write is an idempotent route, and the
 * writes the engine has not acknowledged wait in [WorkoutStore] and replay in order on the next
 * action. A session started offline is built from the saved copy of the engine's own plan; the
 * engine recomputes its targets from the same rules when the start arrives. Finding an exercise by
 * name, swapping and setting up the programme are engine rules, so those need the engine and say so
 * when it cannot be reached.
 */
class WorkoutSessionController(
    private val backend: () -> CoachWriteBackend?,
    private val store: WorkoutStore,
    private val cache: FinanceCache,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The engine's view of today, with this phone's unsent ticks laid over it. */
    suspend fun today(): FinanceRead<WorkoutTodayWire> {
        flush()
        val engine = backend() ?: return withLocal(saved(FinanceWords.NO_ENGINE))
        val reply = engine.send("GET", "$ROOT/", null)
        val body = reply.getOrNull()
        if (body != null) {
            val parsed = runCatching { bodyCoachJson.decodeFromString(WorkoutTodayWire.serializer(), body) }.getOrNull()
                ?: return withLocal(saved(FinanceWords.UNREADABLE))
            cache.write(KEY, body, clock())
            if (store.pending().isEmpty()) {
                store.saveSession(parsed.session?.takeIf { it.inProgress })
            }
            return withLocal(FinanceRead.Fresh(parsed))
        }
        val failure = (reply.exceptionOrNull() as? EngineHttpException)?.failure
        if (failure is EngineFailure.Refused && failure.status in CLIENT_ERRORS) {
            return FinanceRead.Refused(BodyCoachWrites.detailOf(failure.body) ?: FinanceWords.refused(failure.status))
        }
        return withLocal(saved(FinanceWords.why(failure)))
    }

    /** The running workout as this phone holds it, without touching the network. */
    fun current(): SessionWire? = store.session()

    suspend fun start(): WorkoutStep {
        store.session()?.takeIf { it.inProgress }?.let {
            return WorkoutStep(true, "Day ${it.dayKey} is already in progress.", it, queued = store.pending().isNotEmpty())
        }
        val id = UUID.randomUUID().toString()
        val body = buildJsonObject { put("id", id) }.toString()
        enqueue(PendingOp("POST", "$ROOT/start/", body))
        val out = flush()
        out.refusal?.let { return WorkoutStep(false, it) }
        if (out.done) {
            val session = store.session()
            return WorkoutStep(true, out.words ?: "Workout started.", session)
        }
        val plan = savedToday()?.next
        if (plan == null) {
            store.savePending(store.pending().filterNot { it.body == body })
            return WorkoutStep(
                false,
                "Nothing was started. This phone has not read the workout plan yet. ${out.why}",
            )
        }
        val local = localSession(plan, id, LocalDate.now().toString())
        store.saveSession(local)
        return WorkoutStep(
            true,
            "Started Day ${plan.dayKey} on this phone. ${out.why} It saves to the engine when it can.",
            local,
            queued = true,
        )
    }

    /** Ticks (or unticks) one set with what was done. Applied here first, then sent. */
    suspend fun tick(exerciseKey: String, setNumber: Int, done: Boolean, reps: Int?, weightLbs: Double?): WorkoutStep {
        val session = store.session()?.takeIf { it.inProgress }
            ?: return WorkoutStep(false, "Nothing was saved. No workout is in progress.")
        if (session.sets.none { it.exerciseKey == exerciseKey && it.setNumber == setNumber }) {
            return WorkoutStep(false, "Nothing was saved. This workout has no set $setNumber of that exercise.", session)
        }
        val updated = applyTick(session, exerciseKey, setNumber, done, reps, weightLbs)
        store.saveSession(updated)
        val change = buildJsonObject {
            put("exercise_key", exerciseKey)
            put("set_number", setNumber)
            put("done", done)
            if (reps != null) put("reps", reps)
            if (weightLbs != null) put("weight_lbs", weightLbs)
        }
        val body = buildJsonObject {
            put("session_id", session.id)
            put("sets", buildJsonArray { add(change) })
        }.toString()
        enqueue(PendingOp("PATCH", "$ROOT/sets/", body))
        val out = flush()
        out.refusal?.let { return WorkoutStep(false, it, store.session()) }
        return if (out.done) {
            WorkoutStep(true, "Saved.", store.session())
        } else {
            WorkoutStep(true, "Saved on this phone. ${out.why}", updated, queued = true)
        }
    }

    /** "Log bench 135 for 8": the engine finds the exercise and ticks its next open set. */
    suspend fun logByName(exercise: String, reps: Int, weightLbs: Double?): WorkoutStep {
        val body = buildJsonObject {
            put("exercise", exercise)
            put("reps", reps)
            if (weightLbs != null) put("weight_lbs", weightLbs)
        }.toString()
        return online("POST", "$ROOT/log/", body, "logged")
    }

    suspend fun swap(exercise: String, to: String? = null): WorkoutStep {
        val body = buildJsonObject {
            put("exercise", exercise)
            if (!to.isNullOrBlank()) put("to", to)
        }.toString()
        return online("POST", "$ROOT/swap/", body, "swapped")
    }

    suspend fun finish(): WorkoutStep = close("finish", "finished", "Finished on this phone.")

    suspend fun cancel(): WorkoutStep = close("cancel", "cancelled", "Cancelled on this phone.")

    /** Creates the engine's programme (3 full-body days) if there is none, and says what it is. */
    suspend fun setUpProgramme(): CoachWrite {
        val engine = backend() ?: return CoachWrite(false, "Nothing was set up. " + FinanceWords.NO_ENGINE)
        val reply = engine.send("PUT", "$ROOT/programme/", "{}")
        reply.getOrNull()?.let { return CoachWrite(true, BodyCoachWrites.wordsOf(it) ?: "Programme saved.") }
        return CoachWrite(false, "Nothing was set up. " + refusalOrWhy(reply))
    }

    // ------------------------------------------------------------------------------- internals

    private suspend fun close(action: String, verb: String, offline: String): WorkoutStep {
        val session = store.session()?.takeIf { it.inProgress }
            ?: return WorkoutStep(false, "Nothing was $verb. No workout is in progress.")
        val body = buildJsonObject { put("session_id", session.id) }.toString()
        enqueue(PendingOp("POST", "$ROOT/$action/", body))
        val status = if (action == "finish") SessionWire.STATUS_FINISHED else SessionWire.STATUS_CANCELLED
        val closed = session.copy(status = status)
        store.saveSession(closed)
        val out = flush()
        out.refusal?.let { return WorkoutStep(false, it, store.session()) }
        if (out.done) {
            store.saveSession(null)
            return WorkoutStep(true, out.words ?: "Workout $verb.", closed)
        }
        return WorkoutStep(true, "$offline ${out.why} It reaches the engine when it can.", closed, queued = true)
    }

    private suspend fun online(method: String, path: String, body: String, verb: String): WorkoutStep {
        val drained = flush()
        if (!drained.done && store.pending().isNotEmpty()) {
            return WorkoutStep(false, "Nothing was $verb. ${drained.why}", store.session())
        }
        val engine = backend() ?: return WorkoutStep(false, "Nothing was $verb. " + FinanceWords.NO_ENGINE)
        val reply = engine.send(method, path, body)
        val text = reply.getOrNull()
            ?: return WorkoutStep(false, "Nothing was $verb. " + refusalOrWhy(reply), store.session())
        val parsed = parseReply(text)
        adopt(parsed?.session)
        return WorkoutStep(true, parsed?.words?.takeIf { it.isNotBlank() } ?: "Done.", store.session())
    }

    private fun enqueue(op: PendingOp) {
        store.savePending(store.pending() + op)
    }

    private data class Flush(val done: Boolean, val words: String?, val refusal: String?, val why: String)

    /** Replays the queue in order. Stops at the first write the engine could not receive; drops a
     * write it refused (4xx) and reports the engine's own sentence. */
    private suspend fun flush(): Flush {
        var ops = store.pending()
        if (ops.isEmpty()) return Flush(true, null, null, "")
        val engine = backend() ?: return Flush(false, null, null, FinanceWords.NO_ENGINE)
        var words: String? = null
        var refusal: String? = null
        while (ops.isNotEmpty()) {
            val op = ops.first()
            val reply = engine.send(op.method, op.path, op.body)
            val text = reply.getOrNull()
            if (text != null) {
                val parsed = parseReply(text)
                adopt(parsed?.session)
                words = parsed?.words?.takeIf { it.isNotBlank() } ?: words
            } else {
                val failure = (reply.exceptionOrNull() as? EngineHttpException)?.failure
                if (failure is EngineFailure.Refused && failure.status in CLIENT_ERRORS) {
                    refusal = BodyCoachWrites.detailOf(failure.body) ?: FinanceWords.refused(failure.status)
                } else {
                    store.savePending(ops)
                    return Flush(false, words, refusal, FinanceWords.why(failure))
                }
            }
            ops = ops.drop(1)
            store.savePending(ops)
        }
        return Flush(refusal == null, words, refusal, "")
    }

    /** The engine's session replaces this phone's copy; a closed one clears it. */
    private fun adopt(session: SessionWire?) {
        if (session == null) return
        store.saveSession(session.takeIf { it.inProgress })
    }

    private fun parseReply(text: String): SessionReplyWire? =
        runCatching { bodyCoachJson.decodeFromString(SessionReplyWire.serializer(), text) }.getOrNull()

    private fun refusalOrWhy(reply: Result<String>): String {
        val failure = (reply.exceptionOrNull() as? EngineHttpException)?.failure
        if (failure is EngineFailure.Refused && failure.status in CLIENT_ERRORS) {
            return BodyCoachWrites.detailOf(failure.body) ?: FinanceWords.refused(failure.status)
        }
        return FinanceWords.why(failure)
    }

    private fun savedToday(): WorkoutTodayWire? = cache.read(KEY)?.let { (body, _) ->
        runCatching { bodyCoachJson.decodeFromString(WorkoutTodayWire.serializer(), body) }.getOrNull()
    }

    private fun saved(why: String): FinanceRead<WorkoutTodayWire> {
        val (body, at) = cache.read(KEY) ?: return FinanceRead.Unavailable(why)
        val value = runCatching { bodyCoachJson.decodeFromString(WorkoutTodayWire.serializer(), body) }.getOrNull()
            ?: return FinanceRead.Unavailable(why)
        return FinanceRead.Saved(value, at, FinanceWords.savedCopy(at, clock(), why))
    }

    /** This phone's running workout (with unsent ticks) wins over any copy of the engine's. */
    private fun withLocal(read: FinanceRead<WorkoutTodayWire>): FinanceRead<WorkoutTodayWire> {
        val local = store.session() ?: return read
        return when (read) {
            is FinanceRead.Fresh -> FinanceRead.Fresh(read.value.copy(session = local))
            is FinanceRead.Saved -> read.copy(value = read.value.copy(session = local))
            else -> read
        }
    }

    companion object {
        private const val ROOT = "/api/body/coach/workout"
        private const val KEY = "workout_today"
        private val CLIENT_ERRORS = 400..499

        /** This device's engine, its saved copies and its queue. */
        fun forApp(context: Context): WorkoutSessionController {
            val app = context.applicationContext
            return WorkoutSessionController(
                backend = {
                    val http = EngineHttp(EngineConfig(app))
                    if (!http.isUsable()) {
                        null
                    } else {
                        CoachWriteBackend { method, path, json ->
                            when (method) {
                                "GET" -> http.get(path)
                                "PUT" -> http.put(path, json ?: "{}")
                                "PATCH" -> http.patch(path, json ?: "{}")
                                "DELETE" -> http.delete(path)
                                else -> http.post(path, json ?: "{}")
                            }.map { it.body }
                        }
                    }
                },
                store = PrefsWorkoutStore(app),
                cache = PrefsBodyCoachCache(app),
            )
        }

        /** A running session built from the engine's own saved plan, for a start with no signal. */
        fun localSession(plan: PlanWire, id: String, date: String): SessionWire {
            val sets = plan.exercises.flatMap { t ->
                (1..t.sets).map { n ->
                    SetWire(
                        exerciseKey = t.exerciseKey,
                        name = t.name,
                        kind = t.kind,
                        slotKey = t.slotKey,
                        setNumber = n,
                        repsLow = t.repsLow,
                        repsHigh = t.repsHigh,
                        unit = t.unit,
                        targetWeightLbs = t.weightLbs,
                        rule = t.rule,
                        words = t.words,
                    )
                }
            }
            return SessionWire(
                id = id,
                dayKey = plan.dayKey,
                sessionNumber = plan.sessionNumber,
                deload = plan.deload,
                sessionDate = date,
                setCount = sets.size,
                calibrating = plan.calibrating,
                sets = sets,
            )
        }

        /** The engine's tick rule, mirrored for the phone's own copy only: a done set with no reps
         * takes the top of the range, with no weight the target. The engine's answer replaces it. */
        fun applyTick(
            session: SessionWire,
            exerciseKey: String,
            setNumber: Int,
            done: Boolean,
            reps: Int?,
            weightLbs: Double?,
        ): SessionWire {
            val sets = session.sets.map { s ->
                if (s.exerciseKey != exerciseKey || s.setNumber != setNumber) return@map s
                if (done) {
                    s.copy(
                        done = true,
                        reps = reps ?: s.reps ?: s.repsHigh,
                        weightLbs = weightLbs ?: s.weightLbs ?: s.targetWeightLbs,
                    )
                } else {
                    s.copy(done = false, reps = reps ?: s.reps, weightLbs = weightLbs ?: s.weightLbs)
                }
            }
            return session.copy(sets = sets, setsDone = sets.count { it.done })
        }
    }
}

/** SharedPreferences: the running session and the queue, as JSON. */
class PrefsWorkoutStore(context: Context) : WorkoutStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val opsSerializer = ListSerializer(PendingOp.serializer())

    override fun session(): SessionWire? = prefs.getString(SESSION, null)?.let {
        runCatching { bodyCoachJson.decodeFromString(SessionWire.serializer(), it) }.getOrNull()
    }

    override fun saveSession(session: SessionWire?) {
        prefs.edit().apply {
            if (session == null) remove(SESSION) else putString(SESSION, bodyCoachJson.encodeToString(SessionWire.serializer(), session))
        }.apply()
    }

    override fun pending(): List<PendingOp> = prefs.getString(OPS, null)?.let {
        runCatching { bodyCoachJson.decodeFromString(opsSerializer, it) }.getOrNull()
    }.orEmpty()

    override fun savePending(ops: List<PendingOp>) {
        prefs.edit().putString(OPS, bodyCoachJson.encodeToString(opsSerializer, ops)).apply()
    }

    private companion object {
        const val PREFS = "workout_session"
        const val SESSION = "session"
        const val OPS = "pending"
    }
}

