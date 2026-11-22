package com.kevin.legion.service.cascade

/**
 * Why a short clip can go unheard, and the rule that makes it start (2026-11-21, the closing chime
 * not heard on the A25). A MODE_STREAM track that has just been started, flushed, or restarted after
 * an underrun is "filling": the mixer does not take any of its frames until the frames waiting reach
 * its start threshold, which by default is its whole buffer (0.5 s here). A clip shorter than that,
 * with nothing written after it, is never mixed: the closing chime (290 ms) after the close's flush,
 * an acknowledgement after a silence long enough for an underrun. A device log showed it:
 * the chime was written, the track was never ready, and the mixer dropped it for underrun
 * ("BUFFER TIMEOUT") about half a second after its restart.
 *
 * The rule: from API 31 the track's start threshold is set to one frame, so any write plays at once;
 * below API 31 (minSdk 24) a short clip is padded with trailing silence up to the buffer, so it
 * crosses the default threshold and plays at once too. Pure.
 */
internal object ShortClip {
    /** `AudioTrack.setStartThresholdInFrames` exists from this API level (Android 12). */
    const val THRESHOLD_API = 31

    private const val BYTES_PER_FRAME = 2

    /** The start threshold to set on a new track, in frames; null where it cannot be set. */
    fun startThreshold(sdk: Int): Int? = if (sdk >= THRESHOLD_API) 1 else null

    /**
     * [pcm] (16-bit mono) as written to a track whose buffer holds [bufferFrames]: unchanged where the
     * threshold is one frame; below [THRESHOLD_API], padded with silence to the buffer so it starts.
     */
    fun startable(pcm: ByteArray, bufferFrames: Int, sdk: Int): ByteArray =
        if (sdk >= THRESHOLD_API || pcm.size / BYTES_PER_FRAME >= bufferFrames) {
            pcm
        } else {
            pcm.copyOf(bufferFrames * BYTES_PER_FRAME)
        }
}
