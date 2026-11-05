package com.kevin.legion.memory

import kotlin.math.pow

/**
 * The engine's recall score (`server/member_memory/scoring.py`), applied to the phone's cache when
 * the engine cannot be reached, and to the continuity note (which must not rehearse: a recall on
 * the engine bumps `last_recalled_at`, and doing that at every session start would keep the same
 * three memories on top forever).
 *
 * `recency + importance / 10 + relevance`, recency decaying 0.999 an hour for `durable` and 0.99 for
 * `episodic`, relevance the share of the query's terms the text contains, a hard gate that a query
 * with terms returns no row matching none of them, ties to the newer row. **A port, not a second
 * rule:** the engine's answer wins whenever it can be reached; this exists only so an offline recall
 * is ranked the way the online one would be.
 */
object MemoryScore {
    private const val DURABLE_DECAY_PER_HOUR = 0.999
    private const val EPISODIC_DECAY_PER_HOUR = 0.99
    private const val MS_PER_HOUR = 3_600_000.0
    private const val MIN_TERM = 3
    private const val IMPORTANCE_SCALE = 10.0

    private val SPLIT = Regex("[^a-z0-9]+")

    // The engine's list, verbatim (`scoring.STOPWORDS`).
    private val STOPWORDS = (
        "the and for are but not you your yours with this that these those was were have has had " +
            "from what when where which who whom why how about into onto over under than then them they " +
            "their there here his her hers him she its our ours out off all any can could would should " +
            "will just also very much more most some such only own same too did does doing done been " +
            "being remember recall know tell said say says please again ever like want need"
        ).split(" ").toSet()

    fun terms(text: String): List<String> =
        SPLIT.split(text.lowercase()).filter { it.length >= MIN_TERM && it !in STOPWORDS }.distinct()

    fun relevance(queryTerms: List<String>, text: String): Double {
        if (queryTerms.isEmpty()) return 1.0
        val lowered = text.lowercase()
        return queryTerms.count { lowered.contains(it) }.toDouble() / queryTerms.size
    }

    fun recency(decayClass: String, lastAccessMs: Long, nowMs: Long): Double {
        val hours = ((nowMs - lastAccessMs).coerceAtLeast(0L)) / MS_PER_HOUR
        val rate = if (decayClass == "durable") DURABLE_DECAY_PER_HOUR else EPISODIC_DECAY_PER_HOUR
        return rate.pow(hours)
    }

    /** The best [limit] of [memories] for [query], best first. */
    fun best(memories: List<MemberMemory>, query: String, nowMs: Long, limit: Int): List<MemberMemory> {
        val queryTerms = terms(query)
        return memories
            .mapNotNull { m ->
                val rel = relevance(queryTerms, m.text)
                if (queryTerms.isNotEmpty() && rel <= 0.0) {
                    null
                } else {
                    val last = m.lastRecalledAtMs ?: m.createdAtMs
                    m to (recency(m.decayClass, last, nowMs) + m.importance / IMPORTANCE_SCALE + rel)
                }
            }
            .sortedWith(
                compareByDescending<Pair<MemberMemory, Double>> { it.second }.thenByDescending { it.first.createdAtMs },
            )
            .take(limit)
            .map { it.first }
    }
}
