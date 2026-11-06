package com.kevin.legion.bodycoach

import android.content.Context
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttp
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.finance.DjangoFinanceBackend
import com.kevin.legion.finance.FinanceBackend
import com.kevin.legion.finance.FinanceCache
import com.kevin.legion.finance.FinanceController
import com.kevin.legion.finance.FinanceRead
import com.kevin.legion.finance.FinanceWords
import kotlinx.serialization.KSerializer

/** SharedPreferences, one engine body and one time per key, like `PrefsFinanceCache`. */
class PrefsBodyCoachCache(context: Context) : FinanceCache {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun read(key: String): Pair<String, Long>? =
        prefs.getString("body:$key", null)?.let { it to prefs.getLong("at:$key", 0L) }

    override fun write(key: String, body: String, savedAtMs: Long) {
        prefs.edit().putString("body:$key", body).putLong("at:$key", savedAtMs).apply()
    }

    private companion object {
        const val PREFS = "body_coach_cache"
    }
}

/**
 * The fat-loss loop's figures on the phone (`.scratch/fat-loss/` ticket 10). **The engine computes
 * every one of them once** (`server/body_coach/`, ADR 0044): the energy estimate, the weight trend,
 * the two-weekly proposal and the Monday line's facts. This controller only reads them, for
 * `ask_body`'s tools, the BIO digest, the plan generator and the Body screen, so a figure Alfred says
 * is the figure the screen shows (ADR 0035).
 *
 * Same contract as [FinanceController], and it reuses its vocabulary on purpose ([FinanceRead],
 * [FinanceWords]): a fresh answer is saved and shown; with the engine unreachable the saved copy is
 * shown with its age in words; a 4xx is the engine's own sentence; nothing at all is said as such.
 * **Never a blank or a zero standing in for a failure.**
 */
class BodyCoachController(
    /** Null when this device has no engine address or token. Asked per call. */
    private val backend: () -> FinanceBackend?,
    private val cache: FinanceCache,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun energy(): FinanceRead<EnergyWire> = read(KEY_ENERGY, "$ROOT/energy/", EnergyWire.serializer())

    suspend fun trend(days: Int = DEFAULT_TREND_DAYS): FinanceRead<TrendWire> =
        read("trend:$days", "$ROOT/trend/", TrendWire.serializer(), mapOf("days" to days.toString()))

    suspend fun savedMeals(): FinanceRead<SavedMealsWire> =
        read(KEY_SAVED_MEALS, "$ROOT/saved-meals/", SavedMealsWire.serializer())

    suspend fun profile(): FinanceRead<ProfileWire> = read("profile", "$ROOT/profile/", ProfileWire.serializer())

    suspend fun weeklyLine(): FinanceRead<WeeklyLineWire> =
        read("weekly", "$ROOT/weekly-line/", WeeklyLineWire.serializer())

    /** The last saved energy answer, without touching the network (the digest never blocks). */
    fun savedEnergy(): FinanceRead<EnergyWire> = saved(KEY_ENERGY, EnergyWire.serializer(), NOT_READ_YET)

    /** The last saved saved-meals list, without touching the network. */
    fun savedSavedMeals(): FinanceRead<SavedMealsWire> =
        saved(KEY_SAVED_MEALS, SavedMealsWire.serializer(), NOT_READ_YET)

    // One return per outcome (no engine, fresh, unreadable, refused, saved), as FinanceController.
    @Suppress("ReturnCount")
    private suspend fun <T> read(
        key: String,
        path: String,
        serializer: KSerializer<T>,
        query: Map<String, String> = emptyMap(),
    ): FinanceRead<T> {
        val engine = backend() ?: return saved(key, serializer, FinanceWords.NO_ENGINE)
        val reply = engine.get(path, query)
        val body = reply.getOrNull()
        if (body != null) {
            val parsed = runCatching { bodyCoachJson.decodeFromString(serializer, body) }.getOrNull()
                ?: return saved(key, serializer, FinanceWords.UNREADABLE)
            cache.write(key, body, clock())
            return FinanceRead.Fresh(parsed)
        }
        val failure = (reply.exceptionOrNull() as? EngineHttpException)?.failure
        if (failure is EngineFailure.Refused && failure.status in CLIENT_ERRORS) {
            return FinanceRead.Refused(
                FinanceController.detailOf(failure.body) ?: FinanceWords.refused(failure.status),
            )
        }
        return saved(key, serializer, FinanceWords.why(failure))
    }

    @Suppress("ReturnCount") // nothing saved, saved but unreadable, saved
    private fun <T> saved(key: String, serializer: KSerializer<T>, why: String): FinanceRead<T> {
        val (body, at) = cache.read(key) ?: return FinanceRead.Unavailable(why)
        val value = runCatching { bodyCoachJson.decodeFromString(serializer, body) }.getOrNull()
            ?: return FinanceRead.Unavailable(why)
        return FinanceRead.Saved(value, at, FinanceWords.savedCopy(at, clock(), why))
    }

    companion object {
        private const val ROOT = "/api/body/coach"
        private const val KEY_ENERGY = "energy"
        private const val KEY_SAVED_MEALS = "saved_meals"
        private val CLIENT_ERRORS = 400..499
        const val DEFAULT_TREND_DAYS = 90
        const val NOT_READ_YET = "This phone has not read the engine's body figures yet."

        /** This device's engine and SharedPreferences. */
        fun forApp(context: Context): BodyCoachController {
            val app = context.applicationContext
            return BodyCoachController(
                backend = {
                    val http = EngineHttp(EngineConfig(app))
                    if (http.isUsable()) DjangoFinanceBackend(http) else null
                },
                cache = PrefsBodyCoachCache(app),
            )
        }
    }
}
