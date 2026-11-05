package com.kevin.legion.finance

import android.content.Context
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttp
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.brief.BriefWords
import java.time.YearMonth
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What a finance read gave: the engine's answer, the phone's saved copy said in words, the engine's
 * own refusal, or why there is nothing. **Never a blank and never a zero standing in for a failure**
 * (ADR 0051: a failed read never renders as an empty one).
 */
sealed interface FinanceRead<out T> {
    data class Fresh<T>(val value: T) : FinanceRead<T>

    /** The last answer this phone saved. [note] says how old and why, in words. */
    data class Saved<T>(val value: T, val savedAtMs: Long, val note: String) : FinanceRead<T>

    /** The engine was reached and said no (a 4xx with its own sentence, e.g. cash flow's 409). */
    data class Refused(val sentence: String) : FinanceRead<Nothing>

    /** Nothing fresh and nothing saved. Why, in words. */
    data class Unavailable(val sentence: String) : FinanceRead<Nothing>
}

/** The value a read carries, fresh or saved; null for a refusal or nothing. */
val <T> FinanceRead<T>.valueOrNull: T?
    get() = when (this) {
        is FinanceRead.Fresh -> value
        is FinanceRead.Saved -> value
        else -> null
    }

/** The saved-copy note, or the refusal / unavailable sentence; null for a fresh read. */
val FinanceRead<*>.noteOrNull: String?
    get() = when (this) {
        is FinanceRead.Fresh -> null
        is FinanceRead.Saved -> note
        is FinanceRead.Refused -> sentence
        is FinanceRead.Unavailable -> sentence
    }

/** One `GET` against the engine. A seam so the controller is tested with a fake. */
fun interface FinanceBackend {
    suspend fun get(path: String, query: Map<String, String>): Result<String>
}

class DjangoFinanceBackend(private val http: EngineHttp) : FinanceBackend {
    override suspend fun get(path: String, query: Map<String, String>): Result<String> =
        http.get(path, query).map { it.body }
}

/** Raw engine bodies per key, with when each was saved. Only ever the engine's own body. */
interface FinanceCache {
    fun read(key: String): Pair<String, Long>?

    fun write(key: String, body: String, savedAtMs: Long)
}

/** SharedPreferences, like [com.kevin.legion.brief.PrefsBriefCache]: one body and one time per key. */
class PrefsFinanceCache(context: Context) : FinanceCache {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun read(key: String): Pair<String, Long>? =
        prefs.getString("body:$key", null)?.let { it to prefs.getLong("at:$key", 0L) }

    override fun write(key: String, body: String, savedAtMs: Long) {
        prefs.edit().putString("body:$key", body).putLong("at:$key", savedAtMs).apply()
    }

    private companion object {
        const val PREFS = "finance_cache"
    }
}

/**
 * The household's money figures on the phone (household-finance tickets 13 and 14). **One
 * controller for the Home panel, the More card and the Money view**, reading the same engine
 * endpoints Alfred's `safe_to_spend`, `budgets` and `net_worth` tools read (ADR 0035): a figure he
 * says is the figure a screen shows.
 *
 * A fresh answer is saved and shown. When the engine cannot be read, the last saved answer is shown
 * with its age in words. A 4xx is the engine's own sentence and is never covered by an older copy:
 * the engine was reached and is the authority (the same rule [com.kevin.legion.brief.BriefController]
 * keeps for a 404).
 */
class FinanceController(
    /** Null when this device has no engine address or token. Asked per call. */
    private val backend: () -> FinanceBackend?,
    private val cache: FinanceCache,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun safeToSpend(): FinanceRead<SafeToSpendWire> =
        read("safe", "$ROOT/safe-to-spend", emptyMap(), SafeToSpendWire.serializer())

    suspend fun budgets(month: YearMonth): FinanceRead<BudgetsWire> =
        read("budgets:$month", "$ROOT/budgets", mapOf("month" to month.toString()), BudgetsWire.serializer())

    suspend fun netWorth(currency: String = "USD"): FinanceRead<NetWorthWire> =
        read("networth:$currency", "$ROOT/networth", mapOf("ccy" to currency), NetWorthWire.serializer())

    suspend fun cashFlow(month: YearMonth): FinanceRead<CashFlowWire> =
        read("cashflow:$month", "$ROOT/cashflow", mapOf("month" to month.toString()), CashFlowWire.serializer())

    suspend fun accounts(): FinanceRead<List<FinanceAccountWire>> =
        read("accounts", "$ROOT/accounts", emptyMap(), ListSerializer(FinanceAccountWire.serializer()))

    private suspend fun <T> read(
        key: String,
        path: String,
        query: Map<String, String>,
        serializer: KSerializer<T>,
    ): FinanceRead<T> {
        val engine = backend() ?: return saved(key, serializer, FinanceWords.NO_ENGINE)
        val reply = engine.get(path, query)
        val body = reply.getOrNull()
        if (body != null) {
            val parsed = runCatching { financeJson.decodeFromString(serializer, body) }.getOrNull()
                ?: return saved(key, serializer, FinanceWords.UNREADABLE)
            cache.write(key, body, clock())
            return FinanceRead.Fresh(parsed)
        }
        val failure = (reply.exceptionOrNull() as? EngineHttpException)?.failure
        if (failure is EngineFailure.Refused && failure.status in CLIENT_ERRORS) {
            return FinanceRead.Refused(detailOf(failure.body) ?: FinanceWords.refused(failure.status))
        }
        return saved(key, serializer, FinanceWords.why(failure))
    }

    private fun <T> saved(key: String, serializer: KSerializer<T>, why: String): FinanceRead<T> {
        val (body, at) = cache.read(key) ?: return FinanceRead.Unavailable(why)
        val value = runCatching { financeJson.decodeFromString(serializer, body) }.getOrNull()
            ?: return FinanceRead.Unavailable(why)
        return FinanceRead.Saved(value, at, FinanceWords.savedCopy(at, clock(), why))
    }

    companion object {
        private const val ROOT = "/api/finance"
        private val CLIENT_ERRORS = 400..499

        /** This device's engine and SharedPreferences. */
        fun forApp(context: Context): FinanceController {
            val app = context.applicationContext
            return FinanceController(
                backend = {
                    val http = EngineHttp(EngineConfig(app))
                    if (http.isUsable()) DjangoFinanceBackend(http) else null
                },
                cache = PrefsFinanceCache(app),
            )
        }

        internal fun detailOf(body: String): String? = runCatching {
            financeJson.parseToJsonElement(body).jsonObject["detail"]?.jsonPrimitive?.content
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}

/** The phone's own sentences about a finance read. Never a sentence about a figure. */
object FinanceWords {
    const val NO_ENGINE = "This phone is not signed in to an engine."
    const val UNREADABLE = "The engine's answer could not be read by this build."

    fun refused(status: Int) = "The engine refused this read (HTTP $status)."

    /** Why the engine could not be read, short enough for one line. */
    fun why(failure: EngineFailure?): String = when (failure) {
        is EngineFailure.Unreachable -> "The engine could not be reached."
        is EngineFailure.Unauthorized -> "The engine refused this phone's sign-in."
        is EngineFailure.Refused -> "The engine failed on its side (HTTP ${failure.status})."
        is EngineFailure.Malformed -> UNREADABLE
        null -> "The engine could not be read."
    }

    /** "Saved 3 hours ago. The engine could not be reached." */
    fun savedCopy(savedAtMs: Long, nowMs: Long, why: String): String =
        "Saved ${BriefWords.ago(savedAtMs, nowMs)}. $why"
}
