package com.kevin.legion.purchases

/**
 * One bought-log entry as the engine serves it (`server/purchases/`, purchase-log ticket 06).
 *
 * **[loggedBy] null means NOT RECORDED**, never "nobody": it is an entry imported from past
 * Groceries ticks, which never stored who ticked. Every surface says "not recorded" for it
 * ([PurchaseWording.who]) and never guesses a name.
 *
 * **[priceCents] is what someone typed.** It is never reconciled against a bank statement and never
 * summed into a ledger figure (map.md's note), so every surface that shows it says it was entered
 * by hand - [priceNote] carries the engine's own words for that.
 */
data class Purchase(
    val id: String,
    val item: String,
    /** The local day it was bought, as an epoch day. */
    val boughtOn: Int,
    val loggedBy: String?,
    val loggedByMe: Boolean,
    val store: String?,
    val priceCents: Long?,
    val priceNote: String?,
    val quantityNote: String?,
    val isPrivate: Boolean,
    val source: String,
)

/** One distinct item text that matched a "when did we last buy" question: its newest entry. */
data class PurchaseMatch(
    val entry: Purchase,
    /** True when the entry's text IS the query (case and spacing aside). A loose match must be
     * spoken with the entry's own text, never the query's (ticket 02). */
    val exact: Boolean,
    val timesLogged: Int,
)

/** What the user typed into the "Log it" form or said to the assistant. */
data class PurchaseDraft(
    val item: String,
    val boughtOn: Int,
    val store: String? = null,
    val priceCents: Long? = null,
    val note: String? = null,
    val isPrivate: Boolean = false,
    /** Idempotency key: a retried POST with the same one answers with the entry already there. */
    val syncId: String,
)

/** A page of entries, newest first. [message] is the engine's sentence for an empty result. */
data class PurchaseListing(val entries: List<Purchase>, val truncated: Boolean, val message: String?)

/**
 * What a call to the bought log did. **Four states, because four different sentences follow.**
 *
 * - [Ok] - a 2xx came back and decoded. The ONLY state a write may be reported as done from.
 * - [Unreachable] - nothing was sent (offline, no engine address). The write did not happen. The
 *   bought log is online-only (purchase-log ticket 04), so this is said in words on every surface
 *   and is never rendered as an empty log.
 * - [Refused] - the engine, or the engine's auth, said no, with its own sentence. Nothing written.
 * - [Unconfirmed] - a 2xx whose body could not be read. For a write the entry MAY have landed, so
 *   nothing may call it saved or not saved.
 */
sealed interface PurchaseOutcome<out T> {
    data class Ok<T>(val value: T) : PurchaseOutcome<T>
    data class Unreachable(val sentence: String) : PurchaseOutcome<Nothing>
    data class Refused(val sentence: String) : PurchaseOutcome<Nothing>
    data class Unconfirmed(val sentence: String) : PurchaseOutcome<Nothing>
}

/**
 * The seam [PurchasesController] talks through. The one implementation is
 * [com.kevin.legion.backend.engine.DjangoPurchasesBackend]; the interface exists because a test
 * needs a fake that can be unreachable, refusing or ok on demand (CLAUDE.md section 8: interfaces
 * only at seams that need a fake).
 */
interface PurchasesBackend {
    /** `GET /api/purchases/last-bought?q=` - every distinct matching item text, newest first. */
    suspend fun lastBought(query: String): PurchaseOutcome<List<PurchaseMatch>>

    /** `GET /api/purchases/` - newest first; [query] narrows with the same matching as [lastBought]. */
    suspend fun list(query: String?, limit: Int): PurchaseOutcome<PurchaseListing>

    /** `POST /api/purchases/`. */
    suspend fun create(draft: PurchaseDraft): PurchaseOutcome<Purchase>
}
