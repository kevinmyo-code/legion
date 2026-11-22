package com.kevin.legion.service.cascade

import kotlinx.coroutines.delay

/**
 * A wait line written to the player a slice at a time, never more than [LEAD_MS] ahead of what has
 * played, so it can be cut between slices when the answer's audio is in ([DeadAir.cutPlaying]).
 * Written whole, a 1.3 s line sat about 0.5 s deep in the track's buffer, and the answer could only
 * wait behind it. The player is never flushed for this: the answer's writes carry the generation the
 * reply was made with, and a flush would silence them.
 */
internal object PacedClip {
    /** One write: 40 ms of audio. */
    const val SLICE_MS = 40L

    /** At most this much of the line is queued ahead of the play head. */
    const val LEAD_MS = 160L

    /** A cut line ends on this much of itself faded to silence. */
    const val FADE_MS = 8L

    private const val POLL_MS = 10L
    private const val MAX_PACE_WAIT_MS = 500L
    private const val MS_PER_S = 1_000L
    private const val BYTES_PER_SAMPLE = 2
    private const val BYTE_MASK = 0xFF
    private const val BITS = 8
    private const val EVEN = -2

    /** Where a played clip ended: whole, cut for the answer, or stopped by the player (a flush). */
    enum class End { WHOLE, CUT, STOPPED }

    /**
     * Plays [pcm] at [sampleRate]: [write] one slice (false: the player refused it), [aheadMs] the audio
     * queued and not yet played, [cut] asked before each slice with the milliseconds of the line left.
     */
    suspend fun play(
        pcm: ByteArray,
        sampleRate: Int,
        write: suspend (ByteArray) -> Boolean,
        aheadMs: () -> Long,
        cut: (remainingMs: Long) -> Boolean,
    ): End {
        val bytesPerMs = sampleRate * BYTES_PER_SAMPLE / MS_PER_S.toInt()
        val slice = (SLICE_MS * bytesPerMs).toInt().coerceAtLeast(BYTES_PER_SAMPLE)
        var offset = 0
        var ended = End.WHOLE
        while (offset < pcm.size && ended == End.WHOLE) {
            val remainingMs = (pcm.size - offset) / bytesPerMs.coerceAtLeast(1) + aheadMs()
            if (cut(remainingMs)) {
                ended = End.CUT
                // The next few ms of the line, faded to silence: a cut mid-waveform clicks (review D1).
                write(fadeOut(pcm, offset, (FADE_MS * bytesPerMs).toInt()))
            } else {
                // A play head that stops moving never holds the line forever: the write blocks instead.
                var waited = 0L
                while (aheadMs() > LEAD_MS && waited < MAX_PACE_WAIT_MS) {
                    delay(POLL_MS)
                    waited += POLL_MS
                }
                val end = minOf(offset + slice, pcm.size)
                if (!write(pcm.copyOfRange(offset, end))) ended = End.STOPPED
                offset = end
            }
        }
        return ended
    }

    /** [bytes] of 16-bit PCM from [offset] (fewer at the end), scaled linearly from full to silence. */
    fun fadeOut(pcm: ByteArray, offset: Int, bytes: Int): ByteArray {
        val out = pcm.copyOfRange(offset, minOf(offset + bytes, pcm.size) and EVEN)
        val n = out.size / BYTES_PER_SAMPLE
        for (i in 0 until n) {
            val lo = out[2 * i].toInt() and BYTE_MASK
            val v = (lo or (out[2 * i + 1].toInt() shl BITS)) * (n - 1 - i) / n
            out[2 * i] = (v and BYTE_MASK).toByte()
            out[2 * i + 1] = (v shr BITS).toByte()
        }
        return out
    }
}
