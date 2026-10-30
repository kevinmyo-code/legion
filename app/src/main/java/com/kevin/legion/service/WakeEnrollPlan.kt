package com.kevin.legion.service

/**
 * The enrollment script (ticket 20): twenty takes of the wake phrase in five conditions, then two
 * minutes of ordinary sound with no wake phrase in it. Positives measure recall, the negative
 * measures false accepts. Pure so the counts and labels are a JVM test.
 *
 * Each label is `pos_<condition>_<nn>` or `neg_ambient_<nn>`; the offline eval reads the condition
 * back out of it, so the shape is load-bearing.
 */
object WakeEnrollPlan {
    /** One take: what to show, its file label, and how long the window stays open. */
    data class Step(val label: String, val instruction: String, val windowMs: Long, val positive: Boolean)

    const val POSITIVE_WINDOW_MS = 4_000L
    const val NEGATIVE_WINDOW_MS = 120_000L

    private data class Condition(val key: String, val count: Int, val how: String)

    // The take counts are the script itself (ticket 20: twenty takes in five conditions), not tunables.
    @Suppress("MagicNumber")
    private val CONDITIONS = listOf(
        Condition("normal", 6, "in your normal voice, phone in front of you"),
        Condition("quiet", 4, "quietly, almost under your breath"),
        Condition("far", 4, "from across the room, about three metres away"),
        Condition("fast", 3, "quickly, the way you would in passing"),
        Condition("music", 3, "with music or a video playing out loud"),
    )

    /** Every step in order: the positives, then the one negative. */
    fun steps(phrase: String): List<Step> {
        val positives = CONDITIONS.flatMap { c ->
            (1..c.count).map { i ->
                Step(
                    label = "pos_${c.key}_%02d".format(i),
                    instruction = "Say \"$phrase\" ${c.how}. ($i of ${c.count})",
                    windowMs = POSITIVE_WINDOW_MS,
                    positive = true,
                )
            }
        }
        val negative = Step(
            label = "neg_ambient_01",
            instruction = "Talk normally, or play a podcast or TV, for two minutes. Do not say " +
                "\"$phrase\". This is how the tuning learns what is not the wake phrase.",
            windowMs = NEGATIVE_WINDOW_MS,
            positive = false,
        )
        return positives + negative
    }

    val POSITIVE_COUNT: Int = CONDITIONS.sumOf { it.count }
}
