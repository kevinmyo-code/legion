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
)

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

}

