package com.kevin.legion.memory

/**
 * One memory as the engine serves it (`/api/member-memories/`, ADR 0075, memory-and-vault ticket 09).
 *
 * **The engine is the store; the phone holds a whole-list copy** ([MemoryCache]). A memory is
 * private to the member it is about unless [shared]; [aboutYou] says whether that member is the one
 * signed in on this phone, and only then may this phone share it or make it private again (the
 * engine refuses otherwise, `member_memory/service.py`).
 *
 * Times are epoch milliseconds, parsed from the engine's ISO stamps.
 */
data class MemberMemory(
    val id: String,
    val text: String,
    /** `fact`, `preference` or `event`. */
    val kind: String,
    val importance: Int,
    /** `durable` (decays 0.999 an hour) or `episodic` (0.99 an hour). */
    val decayClass: String,
    /** `phone`, `web`, `claude`, `manual` or `consolidation`. */
    val source: String,
    val shared: Boolean,
    val aboutYou: Boolean,
    val createdAtMs: Long,
    val lastRecalledAtMs: Long? = null,
    /** The conversation record a consolidated memory was distilled from, if any. */
    val fromConversation: String? = null,
    /**
     * Why the daily memory review retired it (`merged`, `contradicted`, `expired`, `relabelled`,
     * `not_about_person`); null for a live row. Only the Retired list carries retired rows.
     */
    val retiredReason: String? = null,
    /** The review change that retired it; reverting it restores the row (memory-review ticket 08). */
    val retiredChange: Long? = null,
    /** Set when a pending review change names this row: the recall flag (ruling 02). */
    val pendingReview: PendingReview? = null,
)

/** A pending memory-review change that names a memory. [after] is said in words. */
data class PendingReview(val changeId: Long, val action: String, val after: String)

/** One memory as a review change saw it. [text] is null when it is gone since. */
data class ReviewBefore(val id: String, val text: String?)

/**
 * One change the daily memory review proposed or applied (memory-review tickets 06 and 08). The
 * engine gated it; a pending one waits for Kevin's Accept or Dismiss on the Memory screen.
 * [cluster] groups changes about the same memories, for Accept all.
 */
data class ReviewChange(
    val id: Long,
    val action: String,
    val status: String,
    val cluster: String,
    val before: List<ReviewBefore>,
    /** The text it would keep (a merge or a correction), else what it would do, in words. */
    val afterWords: String,
    val reason: String,
    val actionWords: String,
    /** The day of the conversation a correction cites, `YYYY-MM-DD`, or null. */
    val evidenceDate: String? = null,
)

/**
 * The review half of the engine's memory API (`/api/member-memories/review/`). A seam of its own
 * so [MemberMemoryBackend]'s fakes need not change, and so the screen can be tested with a fake.
 */
interface MemoryReviewBackend {
    /** `GET review/changes` - the pending ones, newest first. */
    suspend fun pending(): MemoryOutcome<List<ReviewChange>>

    /** `POST review/changes/<id>/accept`. Refused 409 when its memories changed since. Ok carries words. */
    suspend fun accept(id: Long): MemoryOutcome<String>

    /** `POST review/changes/<id>/dismiss`. */
    suspend fun dismiss(id: Long): MemoryOutcome<String>

    /** `POST review/changes/<id>/revert` - restores what an applied change retired. */
    suspend fun revert(id: Long): MemoryOutcome<String>

    /** `POST review/clusters/<cluster>/accept` - Accept all. */
    suspend fun acceptCluster(cluster: String): MemoryOutcome<String>

    /** `GET /api/member-memories/?include_superseded=true`, the retired rows only. */
    suspend fun retired(limit: Int): MemoryOutcome<List<MemberMemory>>
}

/**
 * What one call to the engine's memory did. **Four states, because four different sentences
 * follow**, the same shape as [com.kevin.legion.purchases.PurchaseOutcome]:
 *
 * - [Ok]: a 2xx that decoded. The only state a write may be reported done from.
 * - [Unreachable]: nothing was sent (offline, no engine address). A read falls back to the cache.
 * - [Refused]: the engine said no, in its own words ([status] kept: 404 means "no such memory").
 * - [Unconfirmed]: a 2xx whose body did not decode. A write MAY have landed.
 */
sealed interface MemoryOutcome<out T> {
    data class Ok<T>(val value: T) : MemoryOutcome<T>
    data class Unreachable(val sentence: String) : MemoryOutcome<Nothing>
    data class Refused(val sentence: String, val status: Int = 0) : MemoryOutcome<Nothing>
    data class Unconfirmed(val sentence: String) : MemoryOutcome<Nothing>
}

/** A whole-field edit; null leaves the field as the engine holds it. */
data class MemoryEdit(val text: String? = null, val shared: Boolean? = null)

/**
 * The seam [MemoryController] talks through. The one implementation is
 * [com.kevin.legion.backend.engine.DjangoMemberMemoryBackend]; the interface exists because tests
 * need a fake that can be offline, refusing or ok on demand (CLAUDE.md section 8).
 */
interface MemberMemoryBackend {
    /** `GET /api/member-memories/?limit=` - the caller's own and the household's shared, newest first. */
    suspend fun list(limit: Int): MemoryOutcome<List<MemberMemory>>

    /** `POST /api/member-memories/recall` - best first; the engine rehearses what it returns. */
    suspend fun recall(query: String, limit: Int): MemoryOutcome<List<MemberMemory>>

    /** `POST /api/member-memories/` - private unless [shared]. */
    suspend fun create(text: String, shared: Boolean, source: String): MemoryOutcome<MemberMemory>

    /** `PATCH /api/member-memories/<id>`. */
    suspend fun update(id: String, edit: MemoryEdit): MemoryOutcome<MemberMemory>

    /** `DELETE /api/member-memories/<id>` - a hard delete, with every older version it superseded. */
    suspend fun delete(id: String): MemoryOutcome<Unit>

    /**
     * `POST /api/member-memories/import` - legacy phone rows, private to the caller. Idempotent by
     * store and origin guid on the engine, the same key `manage.py import_legacy_memories` uses, so
     * the two doors can never import one row twice. Ok carries how many were new.
     */
    suspend fun importLegacy(rows: List<LegacyMemoryRow>): MemoryOutcome<Int>
}

/**
 * One legacy Room row as the engine's import route reads it (`ImportRowSerializer`). [originGuid]
 * is the row's `syncId`, which the legacy sync already sent as `origin_guid`.
 */
data class LegacyMemoryRow(
    /** `companion_memories` or `memories`. */
    val store: String,
    val originGuid: String,
    val text: String,
    val category: String,
    val source: String,
    val importance: Int?,
    val loggedAtMs: Long,
)

