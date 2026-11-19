package com.kevin.legion.vehicle

/**
 * The which-car question as code SPOKE it on connect ([DongleCarController]'s raise, marked through
 * [AdapterBinding.markAsked] only when it was really said aloud), so that his one-word answer is not
 * held by the lone-word guard (seam d, 2026-11-19: the ask is a spoken line, not a tool result, so
 * "Comet." cost an extra yes/no turn). A tool result's ask has its own field (`needs_answer`) and
 * never lands here. Process memory, like [AdapterBinding]'s.
 */
object SpokenWhichCarAsk {
    /** The connection and the time the ask was spoken on. */
    @Volatile private var spoken: Pair<String, Long>? = null

    /** The ask was spoken on [connection] at [atMs]. */
    fun mark(connection: String, atMs: Long) {
        spoken = connection to atMs
    }

    /**
     * His one [word] answers it: only on the same [connection], within [ANSWER_MS] of the ask, only
     * when the word is a word of exactly one car's label ([labels]) by membership, and only when the
     * model's adapter_in [vehicleArg] has that word too.
     */
    fun answers(
        connection: String?,
        word: String,
        vehicleArg: String,
        labels: Collection<String>,
        nowMs: Long,
    ): Boolean {
        val (on, at) = spoken ?: return false
        val inTime = connection != null && connection == on && nowMs - at in 0..ANSWER_MS
        val w = word.lowercase()
        return inTime && labels.count { w in wordsOf(it) } == 1 && w in wordsOf(vehicleArg)
    }

    private fun wordsOf(text: String): Set<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()

    /**
     * How long after the spoken ask one word may answer it: the line itself, the connect-time listen
     * hold (15 s) and the follow-up window's extensions, with room to spare.
     */
    const val ANSWER_MS = 45_000L

    /** Test seam. */
    internal fun resetForTest() {
        spoken = null
    }
}
