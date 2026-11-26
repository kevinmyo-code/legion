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
 * Keyed by [CascadePrefix.key]. [memo] holds the names; production keeps them across restarts. It
 * holds one entry per prefix variant (tool gating, #453: the chat set and the full set each have a
 * prefix of their own), so a turn that switches set reuses that set's cache instead of replacing the
 * other one. A failed create backs off per key: a set too small to cache must not delay the other.
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
        fun load(): List<Entry>
        fun save(entries: List<Entry>)

        class InMemory : Memo {
            @Volatile private var e: List<Entry> = emptyList()
            override fun load(): List<Entry> = e
            override fun save(entries: List<Entry>) {
                e = entries
            }
        }
    }

    // Immutable snapshots, replaced whole: the newest [MAX_ENTRIES] caches, one per prefix key.
    @Volatile private var entries: Map<String, Entry> = memo.load().associateBy { it.key }
    @Volatile private var creating: Job? = null
    @Volatile private var failed: Map<String, Failure> = emptyMap()

    private data class Failure(val count: Int, val atMs: Long)

    /**
     * Told of each cache this store creates (model, tokens or null, TTL seconds), for the cost
     * audit: the session open at the time adds it to its summary, so storage can be priced.
     */
    @Volatile var onCreated: ((model: String, tokens: Int?, ttlS: Long) -> Unit)? = null

    /**
     * The usable cache name for [prefix], or null (and creation started) when there is none. With
     * [create] false a cache that exists is still used but none is made: a conversation's first turn
     * (cost cut 4, 2026-11-26), so a one-turn conversation never pays a create.
     */
    fun peek(prefix: CascadePrefix, create: Boolean = true): String? {
        val e = entries[prefix.key]
        if (e != null && e.expiresAtMs - now() > MARGIN_MS) return e.name
        if (create) startCreate(prefix)
        return null
    }

    /**
     * For the open's log line: `live left_s=N` (this prompt's cache is reused), `other_prompt` (a
     * cache exists for a different prompt; a new one will be created), `expiring`, `creating`, `none`.
     */
    fun state(prefix: CascadePrefix): String {
        val e = entries[prefix.key]
            ?: return when {
                entries.isNotEmpty() -> "other_prompt"
                creating?.isActive == true -> "creating"
                else -> "none"
            }
        val left = e.expiresAtMs - now()
        return when {
            left <= MARGIN_MS -> "expiring"
            else -> "live left_s=${left / MS_PER_S}"
        }
    }

    /** Drops [name] if it is the one held: a request said it is gone or unusable. */
    fun invalidate(name: String) {
        val held = entries.filterValues { it.name == name }
        if (held.isNotEmpty()) {
            entries = entries - held.keys
            memo.save(entries.values.toList())
            log("cache_invalidated")
        }
    }

    /** Starts creation now. The open no longer calls it (the cache is made at the second turn); the voice eval does. */
    fun warm(prefix: CascadePrefix) {
        peek(prefix)
    }

    @Synchronized
    private fun startCreate(prefix: CascadePrefix) {
        if (creating?.isActive == true) return
        // Back off after failures so a key that cannot create caches does not pay a POST per turn.
        val f = failed[prefix.key]
        if (f != null && now() - f.atMs < backoffMs(f.count)) return
        creating = scope.launch {
            val started = now()
            when (val r = api.create(CascadeRequests.cacheCreate(prefix))) {
                is CacheCreateOutcome.Created -> {
                    val e = Entry(prefix.key, r.name, started + CascadeRequests.CACHE_TTL_S * MS_PER_S)
                    // The newest MAX_ENTRIES stay; an older one is left to expire on its own TTL.
                    entries = (entries + (e.key to e)).values.sortedByDescending { it.expiresAtMs }
                        .take(MAX_ENTRIES).associateBy { it.key }
                    memo.save(entries.values.toList())
                    failed = failed - prefix.key
                    log("cache_create_ms=${now() - started} result=created")
                    val usage = CallUsage(
                        kind = CallUsage.CACHE_CREATE,
                        model = prefix.model,
                        present = r.tokens != null,
                        prompt = r.tokens ?: 0,
                        promptText = r.tokens ?: 0,
                        chars = prefix.systemInstruction.length,
                    )
                    log(usage.line() + " ttl_s=${CascadeRequests.CACHE_TTL_S}")
                    onCreated?.invoke(prefix.model, r.tokens, CascadeRequests.CACHE_TTL_S)
                }
                is CacheCreateOutcome.Failed -> {
                    failed = failed + (prefix.key to Failure((failed[prefix.key]?.count ?: 0) + 1, now()))
                    log("cache_create_ms=${now() - started} result=failed code=${r.code}")
                }
            }
        }
    }

    companion object {
        /** A cache this close to expiry is treated as gone: a turn must not race its expiry. */
        const val MARGIN_MS = 120_000L

        /** One cache per tool-set variant ([ToolGate]): chat and full. */
        const val MAX_ENTRIES = 2
        private const val MS_PER_S = 1_000L
        private const val BASE_BACKOFF_MS = 60_000L
        private const val MAX_BACKOFF_MS = 30 * 60_000L
        private const val MAX_BACKOFF_SHIFT = 5

        fun backoffMs(failures: Int): Long =
            (BASE_BACKOFF_MS shl (failures - 1).coerceIn(0, MAX_BACKOFF_SHIFT)).coerceAtMost(MAX_BACKOFF_MS)
    }
}
