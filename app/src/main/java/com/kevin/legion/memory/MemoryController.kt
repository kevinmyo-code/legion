package com.kevin.legion.memory

import android.content.Context
import com.kevin.legion.backend.engine.DjangoMemberMemoryBackend
import com.kevin.legion.backend.engine.DjangoMemoryReviewBackend
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineHttp
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one controller memory's voice tool AND the Memory screen call (ADR 0035: two doors onto one
 * implementation). Memory v2 lives on the engine (ADR 0075); this phone keeps [cache], the engine's
 * whole list, for when the engine cannot be reached.
 *
 * - **Reads** go to the engine first. Offline, recall and list answer from the cache and say so
 *   ([MemoryRead.fromCache]); a missing cache is "I can't see", never "nothing remembered".
 * - **Writes** go to the engine only. Offline they are refused in words, not queued: the create
 *   route has no idempotency key, so a queued retry could save the same memory twice (CLAUDE.md
 *   section 7 allows either; refusal is the one that cannot duplicate).
 * - **Forget is a hard delete** on the engine (Kevin, 2026-11-05). The cache drops the row on the
 *   engine's 204 and is then refreshed whole, so the forgotten memory is not read back offline.
 *
 * A plain class with its backend, cache and clock injected (CLAUDE.md section 8).
 *
 * **The daily memory review** (memory-review tickets 08 and 09) answers through [review]: the
 * pending changes, Accept, Dismiss, Accept all, Restore, and the retired rows. Never queued
 * offline: a stale accept must reach the engine's 409, so an accept is only ever sent live.
 */
@Suppress("TooManyFunctions") // one per memory operation, both doors (tool and screen) share them
class MemoryController(
    private val backend: MemberMemoryBackend,
    private val cache: MemoryCache,
    private val now: () -> Long = System::currentTimeMillis,
    private val review: MemoryReviewBackend? = backend as? MemoryReviewBackend,
) {

    /** A read's answer: the memories, and whether they came from the phone's copy. */
    data class MemoryRead(
        val memories: List<MemberMemory>,
        val fromCache: Boolean,
        /** When the cache was filled; null for an engine answer. */
        val cachedAtMs: Long? = null,
        /** Why the engine was not used, in words; null for an engine answer. */
        val why: String? = null,
    )

    /** Re-reads the engine's whole list into the cache. The cache is replaced, never merged. */
    suspend fun refresh(): MemoryOutcome<List<MemberMemory>> {
        val outcome = backend.list(LIST_LIMIT)
        if (outcome is MemoryOutcome.Ok) cache.replace(outcome.value, now())
        return outcome
    }

    /** What the cache holds now, with no network. Null when nothing was ever fetched. */
    suspend fun cached(): CachedMemories? = cache.read()

    /**
     * [refresh] only when the cache is older than [RESUME_STALE_MS] (or missing): `MainActivity`
     * calls this on every resume, and the retired legacy pull it replaced was throttled the same
     * way, so switching apps does not re-fetch the whole list each time.
     */
    suspend fun refreshIfStale() {
        val held = cache.read()
        if (held == null || now() - held.fetchedAtMs > RESUME_STALE_MS) refresh()
    }

    /**
     * The best [limit] memories for [query] from the engine (which rehearses them). When the engine
     * cannot be reached, the cache ranked by the engine's own score; a refusal is not a reason to
     * read the cache (the engine said no to this caller, so the copy is not theirs to read either).
     */
    suspend fun recall(query: String, limit: Int = RECALL_LIMIT): MemoryOutcome<MemoryRead> =
        when (val outcome = backend.recall(query.trim(), limit)) {
            is MemoryOutcome.Ok -> MemoryOutcome.Ok(MemoryRead(outcome.value, fromCache = false))
            is MemoryOutcome.Unreachable -> fromCache(outcome.sentence) { MemoryScore.best(it, query, now(), limit) }
            is MemoryOutcome.Unconfirmed -> fromCache(outcome.sentence) { MemoryScore.best(it, query, now(), limit) }
            is MemoryOutcome.Refused -> outcome
        }

    /** Everything this member may see, newest first: the engine's list (refreshing the cache), else the cache. */
    suspend fun list(): MemoryOutcome<MemoryRead> = when (val outcome = refresh()) {
        is MemoryOutcome.Ok -> MemoryOutcome.Ok(MemoryRead(outcome.value, fromCache = false))
        is MemoryOutcome.Unreachable -> fromCache(outcome.sentence) { it }
        is MemoryOutcome.Unconfirmed -> fromCache(outcome.sentence) { it }
        is MemoryOutcome.Refused -> outcome
    }

    /**
     * Saves [text] as a memory about the signed-in member, private unless [shared]. Blank text is
     * refused before anything is sent. [source] is `phone` for the voice tool, `manual` for the screen.
     */
    suspend fun remember(
        text: String,
        shared: Boolean = false,
        source: String = SOURCE_PHONE,
    ): MemoryOutcome<MemberMemory> {
        val clean = text.trim()
        if (clean.isEmpty()) return MemoryOutcome.Refused("There was nothing in that to save, so nothing was saved.")
        if (clean.length > TEXT_MAX) return MemoryOutcome.Refused(tooLong(clean, "saved"))
        val outcome = backend.create(clean, shared, source)
        if (outcome is MemoryOutcome.Ok) refresh()
        return outcome
    }

    /** Hard-deletes memory [id] on the engine. Ok only from the engine's 204. */
    suspend fun forget(id: String): MemoryOutcome<Unit> {
        val clean = id.trim()
        if (clean.isEmpty()) return MemoryOutcome.Refused("Which memory? Nothing was deleted.")
        val outcome = backend.delete(clean)
        if (outcome is MemoryOutcome.Ok) {
            cache.remove(clean)
            refresh()
        }
        return outcome
    }

    /**
     * Uploads legacy Room [rows] to the engine's one-time import (idempotent there), refreshing the
     * cache when anything was new. Nothing is sent for an empty list.
     */
    suspend fun importLegacy(rows: List<LegacyMemoryRow>): MemoryOutcome<Int> {
        if (rows.isEmpty()) return MemoryOutcome.Ok(0)
        val outcome = backend.importLegacy(rows)
        if (outcome is MemoryOutcome.Ok && outcome.value > 0) refresh()
        return outcome
    }

    /** Changes a memory's text. */
    suspend fun edit(memory: MemberMemory, text: String): MemoryOutcome<MemberMemory> {
        val clean = text.trim()
        if (clean.isEmpty()) return MemoryOutcome.Refused("A memory needs some text. Nothing was changed.")
        if (clean.length > TEXT_MAX) return MemoryOutcome.Refused(tooLong(clean, "changed"))
        return write(backend.update(memory.id, MemoryEdit(text = clean)))
    }

    /** Shares a memory with the household, or makes it private again. Only one about this member. */
    suspend fun setShared(memory: MemberMemory, shared: Boolean): MemoryOutcome<MemberMemory> {
        if (!memory.aboutYou) {
            return MemoryOutcome.Refused(
                "Only the person a memory is about can share it or make it private. Nothing was changed.",
            )
        }
        return write(backend.update(memory.id, MemoryEdit(shared = shared)))
    }

    /**
     * The few memories the session's continuity note names: the engine's list (refreshed if the
     * cache is older than [CONTINUITY_STALE_MS], bounded by [waitMs] so a slow engine never holds a
     * session's start), then ranked on the phone with an empty query. Never a recall on the engine,
     * which would rehearse them.
     */
    suspend fun continuity(limit: Int = CONTINUITY_COUNT, waitMs: Long = CONTINUITY_WAIT_MS): List<MemberMemory> {
        val held = cache.read()
        if (held == null || now() - held.fetchedAtMs > CONTINUITY_STALE_MS) {
            withTimeoutOrNull(waitMs) { refresh() }
        }
        val memories = cache.read()?.memories ?: return emptyList()
        return MemoryScore.best(memories, "", now(), limit)
    }

    /** The review's pending changes; none when this controller has no review backend. */
    suspend fun reviewPending(): MemoryOutcome<List<ReviewChange>> =
        review?.pending() ?: MemoryOutcome.Ok(emptyList())

    /** Accept one pending change. Ok refreshes the list; a 409 comes back as Refused, in words. */
    suspend fun acceptChange(change: ReviewChange): MemoryOutcome<String> =
        reviewWrite { it.accept(change.id) }

    suspend fun dismissChange(change: ReviewChange): MemoryOutcome<String> =
        reviewWrite { it.dismiss(change.id) }

    /** Accept all: every pending change in [cluster]. */
    suspend fun acceptCluster(cluster: String): MemoryOutcome<String> =
        reviewWrite { it.acceptCluster(cluster) }

    /** Restore a retired memory by undoing the review change that retired it. */
    suspend fun restore(memory: MemberMemory): MemoryOutcome<String> {
        val change = memory.retiredChange
            ?: return MemoryOutcome.Refused(NOT_REVIEWED)
        return reviewWrite { it.revert(change) }
    }

    /** The rows the review retired, newest first. */
    suspend fun retired(): MemoryOutcome<List<MemberMemory>> =
        review?.retired(LIST_LIMIT) ?: MemoryOutcome.Ok(emptyList())

    private suspend fun reviewWrite(
        call: suspend (MemoryReviewBackend) -> MemoryOutcome<String>,
    ): MemoryOutcome<String> {
        val backend = review ?: return MemoryOutcome.Unreachable(NO_REVIEW)
        val outcome = call(backend)
        if (outcome is MemoryOutcome.Ok) refresh()
        return outcome
    }

    private suspend fun write(outcome: MemoryOutcome<MemberMemory>): MemoryOutcome<MemberMemory> {
        if (outcome is MemoryOutcome.Ok) refresh()
        return outcome
    }

    private suspend fun fromCache(
        why: String,
        pick: (List<MemberMemory>) -> List<MemberMemory>,
    ): MemoryOutcome<MemoryRead> {
        val held = cache.read()
            ?: return MemoryOutcome.Unreachable("$why There is no copy of your memories on this phone yet.")
        return MemoryOutcome.Ok(
            MemoryRead(pick(held.memories), fromCache = true, cachedAtMs = held.fetchedAtMs, why = why),
        )
    }

    companion object {
        const val SOURCE_PHONE = "phone"
        const val SOURCE_MANUAL = "manual"
        const val RECALL_LIMIT = 6
        /** The engine's `LIST_LIMIT_MAX`: the whole list, as far as one member's store goes. */
        const val LIST_LIMIT = 500
        /** The engine's `TEXT_MAX_CHARS`, checked here only to say it before sending. */
        const val TEXT_MAX = 500
        private const val CONTINUITY_COUNT = 3
        private const val CONTINUITY_STALE_MS = 10 * 60_000L
        private const val CONTINUITY_WAIT_MS = 1_500L
        private const val RESUME_STALE_MS = 5 * 60_000L
        private const val NO_REVIEW = "This phone cannot reach the memory review."
        private const val NOT_REVIEWED =
            "This one was not retired by the memory review, so it cannot be restored here."

        private fun tooLong(clean: String, verb: String): String =
            "A memory is at most $TEXT_MAX characters; that was ${clean.length}. Nothing was $verb."

        /** The production wiring: the engine over the saved address and device token, the file cache. */
        fun forContext(context: Context): MemoryController {
            val app = context.applicationContext
            val http = EngineHttp(EngineConfig(app))
            return MemoryController(
                DjangoMemberMemoryBackend(http),
                FileMemoryCache.forContext(app),
                review = DjangoMemoryReviewBackend(http),
            )
        }
    }
}
