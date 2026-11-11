package com.kevin.legion.service.cascade

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The explicit Gemini context cache for the cascade's prefix (system instruction + tools +
 * toolConfig). Measured 2026-11-10: an explicit cache hit on every call (107 of 107), while the
 * implicit cache never hit a new conversation (0 of 9), so this is what makes a cascade turn cheap.
 *
 * Never blocks a turn: [peek] answers from memory and, when there is no usable cache, starts
 * creating one in the background and returns null, so that turn goes inline (the design's
 * "cache missing or create fails" row: about 0.1 s and a fraction of a cent more, nothing heard).
 * A cache that a request reports broken is dropped by [invalidate] and recreated on a later turn.
 *
 * Keyed by [CascadePrefix.key]. [memo] holds the name; production uses the in-memory one, so the
 * cache is reused for the life of the app process (a restart creates a new one; the old one
 * expires on its own TTL).
 */
class ContextCacheStore(
    private val api: CacheApi,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val memo: Memo = Memo.InMemory(),
    private val log: (String) -> Unit = {},
) {
    data class Entry(val key: String, val name: String, val expiresAtMs: Long)

    interface Memo {
        fun load(): Entry?
        fun save(entry: Entry?)

        class InMemory : Memo {
            @Volatile private var e: Entry? = null
            override fun load(): Entry? = e
            override fun save(entry: Entry?) {
                e = entry
            }
        }
    }

    @Volatile private var entry: Entry? = memo.load()
    @Volatile private var creating: Job? = null
    @Volatile private var failures = 0
    @Volatile private var lastFailureAtMs = 0L

    /** The usable cache name for [prefix], or null (and creation started) when there is none. */
    fun peek(prefix: CascadePrefix): String? {
        val e = entry
        if (e != null && e.key == prefix.key && e.expiresAtMs - now() > MARGIN_MS) return e.name
        startCreate(prefix)
        return null
    }

    /** Drops [name] if it is the one held: a request said it is gone or unusable. */
    fun invalidate(name: String) {
        if (entry?.name == name) {
            entry = null
            memo.save(null)
            log("cache_invalidated")
        }
    }

    /** Starts creation now (at conversation open), so the first turn can already use it. */
    fun warm(prefix: CascadePrefix) {
        peek(prefix)
    }

    @Synchronized
    private fun startCreate(prefix: CascadePrefix) {
        if (creating?.isActive == true) return
        // Back off after failures so a key that cannot create caches does not pay a POST per turn.
        if (failures > 0 && now() - lastFailureAtMs < backoffMs(failures)) return
        creating = scope.launch {
            val started = now()
            when (val r = api.create(CascadeRequests.cacheCreate(prefix))) {
                is CacheCreateOutcome.Created -> {
                    val e = Entry(prefix.key, r.name, started + CascadeRequests.CACHE_TTL_S * MS_PER_S)
                    entry = e
                    memo.save(e)
                    failures = 0
                    log("cache_create_ms=${now() - started} result=created")
                }
                is CacheCreateOutcome.Failed -> {
                    failures++
                    lastFailureAtMs = now()
                    log("cache_create_ms=${now() - started} result=failed code=${r.code}")
                }
            }
        }
    }

    companion object {
        /** A cache this close to expiry is treated as gone: a turn must not race its expiry. */
        const val MARGIN_MS = 120_000L
        private const val MS_PER_S = 1_000L
        private const val BASE_BACKOFF_MS = 60_000L
        private const val MAX_BACKOFF_MS = 30 * 60_000L

        fun backoffMs(failures: Int): Long =
            (BASE_BACKOFF_MS shl (failures - 1).coerceIn(0, 5)).coerceAtMost(MAX_BACKOFF_MS)
    }
}
