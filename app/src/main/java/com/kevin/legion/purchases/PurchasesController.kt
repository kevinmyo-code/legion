package com.kevin.legion.purchases

import android.content.Context
import com.kevin.legion.backend.engine.DjangoPurchasesBackend
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineHttp
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * The one controller the bought log's voice tools AND its screens call (ADR 0035: the hands path
 * and the voice path are two doors onto one implementation, never two implementations).
 *
 * **Online only, by Kevin's ruling (purchase-log ticket 04): there is no Room table and no sync.**
 * Every call goes to the engine, and every result is a [PurchaseOutcome] whose four states each
 * name what did and did not happen. Nothing here ever turns "could not read" into an empty list.
 *
 * A plain class with its backend and clock injected (CLAUDE.md section 8: no `object` controllers
 * for new code), so a test substitutes a fake [PurchasesBackend] and a fixed day.
 */
class PurchasesController(
    private val backend: PurchasesBackend,
    private val todayProvider: () -> Int = { LocalDate.now().toEpochDay().toInt() },
    private val newSyncId: () -> String = { UUID.randomUUID().toString() },
) {
    /** Today as a local epoch day, the zone being the device's own. */
    fun today(): Int = todayProvider()

    /**
     * Logs a bought entry. [boughtOn] null means today. **A blank [item] or a negative price is
     * refused here, before anything is sent**, in words that say nothing was logged.
     */
    suspend fun log(
        item: String,
        boughtOn: Int? = null,
        store: String? = null,
        priceCents: Long? = null,
        note: String? = null,
        isPrivate: Boolean = false,
    ): PurchaseOutcome<Purchase> {
        val trimmed = item.trim()
        val refusal = when {
            trimmed.isEmpty() -> "Which item did you buy? Nothing was logged."
            priceCents != null && priceCents < 0 -> "A price can't be negative. Nothing was logged."
            else -> null
        }
        return if (refusal != null) {
            PurchaseOutcome.Refused(refusal)
        } else {
            backend.create(
                PurchaseDraft(
                    item = trimmed,
                    boughtOn = boughtOn ?: today(),
                    store = store?.trim()?.ifBlank { null },
                    priceCents = priceCents,
                    note = note?.trim()?.ifBlank { null },
                    isPrivate = isPrivate,
                    syncId = newSyncId(),
                ),
            )
        }
    }

    /** "When did we last buy [query]?" - every distinct matching item text, newest first. */
    suspend fun lastBought(query: String): PurchaseOutcome<List<PurchaseMatch>> {
        val q = query.trim()
        if (q.isEmpty()) return PurchaseOutcome.Refused("Which item should I look for?")
        return backend.lastBought(q)
    }

    /** The newest [limit] entries, optionally narrowed by [query]. */
    suspend fun recent(limit: Int = DEFAULT_RECENT, query: String? = null): PurchaseOutcome<PurchaseListing> =
        backend.list(query?.trim()?.ifBlank { null }, limit.coerceIn(1, MAX_RECENT))

    companion object {
        const val DEFAULT_RECENT = 10
        private const val MAX_RECENT = 50

        /** The production wiring: the engine over the saved address and device token. */
        fun forContext(context: Context): PurchasesController =
            PurchasesController(DjangoPurchasesBackend(EngineHttp(EngineConfig(context.applicationContext))))

        /**
         * Reads what a person typed into the price box as whole cents: "4.99" is 499, "5" is 500,
         * "$4.99" is 499, blank is no price. Anything else (letters, a third decimal place,
         * negative) is `null` with [valid] false - never rounded into a guess. Money is `Long`
         * cents everywhere (CLAUDE.md section 4 rule 3).
         */
        fun parsePrice(text: String): ParsedPrice {
            val t = text.trim().removePrefix("$").trim()
            if (t.isEmpty()) return ParsedPrice(null, valid = true)
            val value = t.toBigDecimalOrNull()
            val ok = value != null && value.signum() >= 0 && value.scale() <= 2 && value < MAX_PRICE
            return if (ok) {
                ParsedPrice(value!!.movePointRight(2).longValueExact(), valid = true)
            } else {
                ParsedPrice(null, valid = false)
            }
        }

        private val MAX_PRICE = BigDecimal(1_000_000)
    }

    /** [cents] null with [valid] true means "no price given"; [valid] false means the text was unreadable. */
    data class ParsedPrice(val cents: Long?, val valid: Boolean)
}
