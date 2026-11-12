package com.kevin.legion.watches

import android.content.Context
import com.kevin.legion.backend.engine.DjangoWatchesBackend
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineHttp
import java.math.RoundingMode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The one controller the `watch` voice tool AND the Watches screen call (ADR 0035: two doors onto
 * one implementation). The engine is the store and the evaluator (ADR 0083); this phone only asks
 * it. **Writes are never queued offline**: a watch set while the engine is unreachable is refused
 * in words ([WatchWording.unreachable]), because nothing may claim a watch is set unless the engine
 * wrote it.
 *
 * [params] turns what a person says or types (an amount in dollars, one line of text) into the
 * kind's typed params. Money becomes integer cents through `BigDecimal`, never a float
 * (CLAUDE.md section 4 rule 3). The engine validates again and has the last word.
 */
class WatchesController(private val backend: WatchesBackend) {

    suspend fun list(): WatchOutcome<WatchList> = backend.list()

    suspend fun set(
        kind: String,
        amount: String?,
        text: String?,
        label: String?,
        source: String,
    ): WatchOutcome<WatchSet> {
        val clean = kind.trim().lowercase()
        val params = params(clean, amount, text)
            ?: return WatchOutcome.Refused(WatchWording.amountNeeded(clean))
        return backend.create(clean, params, label, source)
    }

    suspend fun pause(id: String, paused: Boolean): WatchOutcome<Watch> =
        backend.update(id.trim(), JsonObject(mapOf("paused" to JsonPrimitive(paused))))

    /** Ok(null): removed. Ok(watch): a built-in, which the engine pauses instead (never removed). */
    suspend fun remove(id: String): WatchOutcome<Watch?> = backend.delete(id.trim())

    suspend fun pending(): WatchOutcome<List<WatchFiring>> = backend.pending()

    suspend fun recent(): WatchOutcome<List<WatchFiring>> = backend.recent()

    suspend fun ack(ids: List<String>, outcome: String): WatchOutcome<Int> =
        if (ids.isEmpty()) WatchOutcome.Ok(0) else backend.ack(ids, outcome)

    companion object {
        /** Which param the one line of text fills, per kind (null: the kind takes no text). */
        internal fun textParam(kind: String): String? = when (kind) {
            KIND_CHARGE_OVER -> "merchant"
            KIND_DEPOSIT_OVER -> "payer"
            KIND_BALANCE_UNDER -> "account"
            KIND_GRADE_POSTED -> "course"
            KIND_MAIL_REPLY -> "companies"
            KIND_BUDGET_PACE -> "category"
            else -> null
        }

        private val MONEY_KINDS = setOf(KIND_CHARGE_OVER, KIND_DEPOSIT_OVER, KIND_BALANCE_UNDER)

        /**
         * The typed params for [kind], or null when a money kind has no readable amount. An unknown
         * kind passes through with no params: the engine's refusal names the kinds there are.
         */
        internal fun params(kind: String, amount: String?, text: String?): JsonObject? {
            val out = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
            if (kind in MONEY_KINDS) {
                val cents = cents(amount) ?: return null
                out["amount_cents"] = JsonPrimitive(cents)
            }
            val words = text?.trim().orEmpty()
            val param = textParam(kind)
            if (words.isNotEmpty() && param != null) {
                out[param] = when (kind) {
                    KIND_MAIL_REPLY -> JsonArray(
                        words.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.map(::JsonPrimitive),
                    )
                    KIND_BALANCE_UNDER -> JsonPrimitive(words.filter(Char::isDigit).takeLast(LAST_FOUR))
                    else -> JsonPrimitive(words)
                }
            }
            return JsonObject(out)
        }

        private const val LAST_FOUR = 4

        /**
         * "250", "250.5", "$1,200" to integer cents, through `BigDecimal` and never a float; null when
         * it is not a positive amount.
         */
        @Suppress("ReturnCount") // blank, not a number, not positive: each is "no amount"
        internal fun cents(amount: String?): Long? {
            val raw = amount?.replace("$", "")?.replace(",", "")?.trim().orEmpty()
            if (raw.isEmpty()) return null
            val value = raw.toBigDecimalOrNull() ?: return null
            if (value.signum() <= 0) return null
            return runCatching { value.movePointRight(2).setScale(0, RoundingMode.HALF_EVEN).longValueExact() }
                .getOrNull()
        }

        fun forContext(context: Context): WatchesController =
            WatchesController(DjangoWatchesBackend(EngineHttp(EngineConfig(context.applicationContext))))
    }
}
