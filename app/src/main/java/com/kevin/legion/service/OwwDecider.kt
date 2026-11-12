package com.kevin.legion.service

/**
 * Turns openWakeWord's per-frame scores (one every 80 ms) into wake decisions, with no audio and no
 * Android in it so the threshold and the trigger gap are unit tested.
 *
 * - A score at or above [threshold] is [Decision.Open], unless an open happened less than [gapMs]
 *   ago: one spoken phrase scores high for several frames in a row, and only the first may open.
 *   The gap is the engine's own trigger gap ([WakeWordEngine.MIN_TRIGGER_GAP_MS]), so both engines
 *   space wakes the same way.
 * - A run of frames at or above [nearMissFloor] that ends without opening is one
 *   [Decision.NearMiss] carrying its peak. That is what tuning needs to see (a phrase that almost
 *   woke it) without a log line every 80 ms.
 * - Everything else is null: nothing to log.
 */
class OwwDecider(
    val threshold: Float,
    private val gapMs: Long = WakeWordEngine.MIN_TRIGGER_GAP_MS,
    val nearMissFloor: Float = threshold * NEAR_MISS_FRACTION,
) {
    sealed interface Decision {
        val score: Float

        data class Open(override val score: Float) : Decision
        data class NearMiss(override val score: Float) : Decision
    }

    private var lastOpenAtMs = Long.MIN_VALUE / 2
    private var runPeak = 0f
    private var inRun = false
    private var runOpened = false

    fun onScore(score: Float, nowMs: Long): Decision? = when {
        score >= threshold && nowMs - lastOpenAtMs >= gapMs -> open(score, nowMs)
        score >= nearMissFloor -> {
            extendRun(score)
            null
        }
        else -> endRun()
    }

    private fun open(score: Float, nowMs: Long): Decision {
        lastOpenAtMs = nowMs
        inRun = true
        runOpened = true
        runPeak = score
        return Decision.Open(score)
    }

    private fun extendRun(score: Float) {
        if (!inRun) {
            inRun = true
            runOpened = false
            runPeak = score
        } else if (score > runPeak) {
            runPeak = score
        }
        // Above threshold inside the gap: the trigger gap held it back, which is not a near miss.
        if (score >= threshold) runOpened = true
    }

    /** A frame under the near-miss floor ends any run; a run that never opened is one near miss. */
    private fun endRun(): Decision? {
        val ended = inRun && !runOpened
        val peak = runPeak
        inRun = false
        runOpened = false
        runPeak = 0f
        return if (ended) Decision.NearMiss(peak) else null
    }

    companion object {
        /** A run must reach this share of the threshold to be logged as a near miss. */
        const val NEAR_MISS_FRACTION = 0.5f
    }
}
