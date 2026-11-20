package com.kevin.legion.service.cascade

/**
 * His audio from the tap until the turn detector exists (Kevin, 2026-11-19: a tap opens the mic at
 * once; the session's setup happens behind it). The mic starts before the turn models, the voice
 * service and the turn plan are ready, so a word said straight after the chime would otherwise be
 * lost. Each buffer is kept with whether something was playing when it was captured (the chime), so
 * the detector judges it exactly as it would have live: its silence timing counts frames, not the
 * wall clock, so a burst replay ends a turn at the same audio position.
 *
 * Bounded: past [capBytes] the oldest audio goes (a setup that hangs must not grow memory; the newest
 * words are the ones still being said). Pure: no Android, no clock.
 */
internal class MicBacklog(private val capBytes: Int = DEFAULT_CAP_BYTES) {
    private class Chunk(val pcm: ByteArray, val playing: Boolean)

    private val chunks = ArrayDeque<Chunk>()

    /** Bytes held now. */
    var bytes = 0
        private set

    /** Bytes dropped because the cap was passed (oldest first). */
    var droppedBytes = 0
        private set

    /** Keeps a copy of [pcm]'s first [n] bytes (the mic reuses its buffer). */
    fun add(pcm: ByteArray, n: Int, playing: Boolean) {
        if (n <= 0) return
        chunks.addLast(Chunk(pcm.copyOf(n), playing))
        bytes += n
        while (bytes > capBytes && chunks.isNotEmpty()) {
            val old = chunks.removeFirst()
            bytes -= old.pcm.size
            droppedBytes += old.pcm.size
        }
    }

    /** Hands everything held to [feed], oldest first, and empties. Returns the milliseconds handed on. */
    fun drainTo(feed: (pcm: ByteArray, n: Int, playing: Boolean) -> Unit): Long {
        val ms = bytes.toLong() / PCM_BYTES_PER_MS
        while (chunks.isNotEmpty()) {
            val c = chunks.removeFirst()
            feed(c.pcm, c.pcm.size, c.playing)
        }
        bytes = 0
        return ms
    }

    companion object {
        /** 16 kHz PCM16 mono. */
        const val PCM_BYTES_PER_MS = 32

        /** Ten seconds: a cloud open takes well under one; a laptop probe and connect a few. */
        const val DEFAULT_CAP_BYTES = 10_000 * PCM_BYTES_PER_MS
    }
}
