package com.kevin.legion.service

/**
 * Fixed input gain ahead of the wake detectors (ticket 20, interim). The A25's capture arrives about
 * 30 dB below normal speech level (sweep `.scratch/wake-word/research/20-sweep-2026-11-03.md`:
 * speech RMS -51 to -74 dBFS), so the spotter sees the phrase too quietly to score it.
 *
 * **Interim, chosen without negatives** (Kevin 2026-11-03, "agreed on all 3", the sweep's middle
 * row: gain 4, keywordsThreshold 0.10, keywordsScore 5.0, 13/20 recall). False accepts are NOT
 * measured; this is revisited once the 2-minute negative is recorded. Mirrored in
 * `tools/wake_eval/core.py` (`APP_INPUT_GAIN`).
 *
 * Only the detectors hear the gained copy: the peak meter and [WakeSampleRecorder] stay raw, so
 * recordings remain usable for re-running the sweep at any gain.
 */
const val WAKE_INPUT_GAIN = 4

object WakeInputGain {
    /**
     * A new array of the first [n] samples of [buf] multiplied by [gain], saturated to the Short
     * range (the sweep clipped to [-32768, 32767] the same way). [buf] is left untouched.
     */
    fun apply(buf: ShortArray, n: Int, gain: Int): ShortArray {
        require(n in 0..buf.size) { "n=$n outside 0..${buf.size}" }
        return ShortArray(n) { i ->
            (buf[i].toInt() * gain).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}
