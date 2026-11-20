package com.kevin.legion.service.cascade

/**
 * His audio through a half-duplex filler clip (blind review M1, 2026-11-19; always on since Kevin's
 * 2026-11-20 ruling, fillers on Bluetooth: the backchannel and the wait lines). With barge-in off the
 * turn detector ignores the mic while a clip, its drain and the route's settle play, so words he
 * resumed with there were lost. Now nothing captured is discarded:
 *
 * - **During** the clip (every buffer captured while something plays, settle included) the audio is
 *   kept here ([onFrame] with `playing` true), for the transcript only. It is never judged as speech:
 *   on the assistant route there is no echo reference, so the clip's own sound would read as him.
 * - **After** the clip's last sample plus the settle, buffers are counted ([onFrame] with `playing`
 *   false). The detector, judging only these, decides whether he resumed: a turn start within
 *   [RESUME_WINDOW_MS] of the settle's end is a resume ([resumes]).
 * - On a resume the new utterance begins with the first half's audio and the audio kept during the
 *   clip ([prefix]), then the detector's own pre-roll; the part of the kept audio that the pre-roll
 *   repeats is cut (counted in bytes, so it does not depend on clocks). The first half's turn is
 *   dropped unanswered, so one turn is transcribed and answered whole.
 * - No start in the window: the clip's audio is dropped (it held no speech the detector could see)
 *   and the reply goes on unchanged.
 *
 * Known limit (stated, not hidden): words said wholly inside the clip and its settle, with nothing
 * after, are not detected, because the decision uses audio after the clip only.
 * Pure: no Android, no clock.
 */
internal class ClipListen(
    /** The first half's audio (the utterance whose end scheduled the clip); empty when not kept. */
    private val firstHalf: ByteArray,
    private val capBytes: Int = CAP_BYTES,
) {
    private val during = java.io.ByteArrayOutputStream()

    /** Bytes captured since the clip and its settle ended (nothing plays). */
    var bytesAfter = 0L
        private set

    /** The clip has ended (a buffer with nothing playing arrived). */
    var clipOver = false
        private set

    /** Past the window with no start: this listen is spent. */
    val expired: Boolean get() = clipOver && bytesAfter > RESUME_WINDOW_BYTES

    fun onFrame(pcm: ByteArray, n: Int, playing: Boolean) {
        if (n <= 0) return
        if (playing && !clipOver) {
            val room = capBytes - during.size()
            if (room > 0) during.write(pcm, 0, minOf(n, room))
        } else {
            clipOver = true
            bytesAfter += n
        }
    }

    /** A turn start now is a resume of the first half: the clip is over and the window still open. */
    fun resumes(): Boolean = clipOver && bytesAfter <= RESUME_WINDOW_BYTES

    /**
     * The audio the resumed utterance starts with: the first half, then the kept clip audio without
     * the tail the detector's pre-roll ([preRollBytes]) will send again.
     */
    fun prefix(preRollBytes: Int): ByteArray {
        val kept = during.toByteArray()
        val overlap = (preRollBytes - bytesAfter).coerceIn(0, kept.size.toLong()).toInt()
        // Whole samples only.
        val cut = overlap - overlap % 2
        return firstHalf + kept.copyOfRange(0, kept.size - cut)
    }

    companion object {
        private const val PCM_BYTES_PER_MS = 32

        /** A start this soon after the clip's settle continues his sentence. */
        const val RESUME_WINDOW_MS = 1_500L
        const val RESUME_WINDOW_BYTES = RESUME_WINDOW_MS * PCM_BYTES_PER_MS

        /** The clip plus drain plus settle is well under this; more is not kept. */
        const val CAP_BYTES = 5_000 * PCM_BYTES_PER_MS
    }
}
