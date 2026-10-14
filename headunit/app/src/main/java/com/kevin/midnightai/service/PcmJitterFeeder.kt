package com.kevin.midnightai.service

/**
 * The slice of a MODE_STREAM PCM16 AudioTrack that [PcmJitterFeeder] drives. An interface so the
 * buffering rules are unit-testable without Android; the real adapter lives in GeminiLiveSession.
 */
interface PcmSink {
    /** Track capacity in frames (AudioTrack.getBufferSizeInFrames). */
    val capacityFrames: Int
    val isPlaying: Boolean
    /** Frames played since creation or the last flush. */
    fun headFrames(): Long
    /** HAL-observed underruns since creation (AudioTrack.getUnderrunCount). */
    fun underrunCount(): Int
    fun play()
    fun pause()
    /** Blocking write. Bytes accepted (short if paused/flushed underneath), negative on error. */
    fun write(data: ByteArray, offset: Int, size: Int): Int
}

/** What one model turn's playback looked like, for the choppy-voice diagnostics. */
data class TurnPlaybackStats(
    val chunks: Int,
    val audioMs: Long,
    /** Times the track ran dry mid-turn and the feeder paused to re-prime. */
    val rebuffers: Int,
    /** AudioTrack.getUnderrunCount delta from the turn's first play() to its end. */
    val halUnderruns: Int,
    /** Bytes that never reached the track (write error, odd trailing byte). */
    val droppedBytes: Int,
)

/**
 * Jitter buffer in front of a streaming AudioTrack.
 *
 * Choppy-voice root cause (2026-10-13): the track used to be play()ed empty at creation and every
 * Gemini chunk was written the instant it arrived, so the only cushion between the network and
 * the speaker was however far one chunk happened to lead the playhead - often nothing at the
 * start of a turn. Any hotspot jitter, or the writer thread losing the CPU on a slow head unit,
 * ran the track dry and the driver heard a gap.
 *
 * Rules:
 * - A turn starts paused and only play()s once [primeFrames] are queued in the track.
 * - If the track runs dry mid-turn (head caught up with what was written), pause and re-prime:
 *   one ~200ms hold instead of a stutter on every late chunk.
 * - [endOfTurn] releases a short reply that never reached the prime threshold.
 * - Partial writes are retried for the remainder, never dropped; only a hard error or a stall of
 *   [MAX_ZERO_WRITES] zero-byte writes gives up on a chunk, and that is counted.
 *
 * Single consumer thread: every method except [bufferedFrames] / [framesWritten] reads must be
 * called from the playback thread.
 */
class PcmJitterFeeder(
    private val sink: PcmSink,
    primeFrames: Int,
    private val sampleRate: Int,
    private val bytesPerFrame: Int = 2,
) {
    /** Clamped to half the track so priming can never fill (and block on) a paused track. */
    val primeFrames: Int = primeFrames.coerceIn(1, maxOf(1, sink.capacityFrames / 2))

    private val maxWriteBytes: Int = maxOf(1, sink.capacityFrames / 2) * bytesPerFrame

    /** Frames handed to the sink since creation or the last [onFlushed]. */
    @Volatile var framesWritten: Long = 0
        private set

    private var inTurn = false
    private var chunks = 0
    private var turnFrames = 0L
    private var rebuffers = 0
    private var droppedBytes = 0
    private var underrunsAtFirstPlay = -1

    /** Frames written but not yet played. Safe to read from any thread. */
    fun bufferedFrames(): Long = (framesWritten - sink.headFrames()).coerceAtLeast(0)

    /**
     * Writes one chunk. [isStale] is polled between writes; when it turns true (a barge-in flush
     * landed on another thread) the rest of the chunk is abandoned without being counted dropped.
     */
    fun feed(data: ByteArray, isStale: () -> Boolean = { false }) {
        val buffered = bufferedFrames()
        if (!inTurn) {
            startTurn()
            if (sink.isPlaying && buffered < primeFrames) sink.pause()
        } else if (sink.isPlaying && buffered == 0L) {
            rebuffers++
            sink.pause()
        }
        chunks++
        val usable = data.size - data.size % bytesPerFrame
        droppedBytes += data.size - usable
        var offset = 0
        var zeroWrites = 0
        while (offset < usable) {
            if (isStale()) return
            // Never more than half the track per call: if a barge-in pauses + flushes
            // the track mid-write, the in-flight write must still fit and return
            // instead of blocking forever on a paused track.
            var len = minOf(usable - offset, maxWriteBytes)
            if (!sink.isPlaying) {
                val room = primeFrames - bufferedFrames()
                if (room <= 0) {
                    startPlayback()
                    continue
                }
                len = minOf(len.toLong(), room * bytesPerFrame).toInt()
            }
            val n = sink.write(data, offset, len)
            if (n < 0) break
            if (n == 0) {
                if (++zeroWrites >= MAX_ZERO_WRITES) break
                continue
            }
            zeroWrites = 0
            offset += n
            val frames = n / bytesPerFrame
            framesWritten += frames
            turnFrames += frames
        }
        droppedBytes += usable - offset
    }

    /** The model finished sending this turn. Returns its stats, or null if no audio was fed. */
    fun endOfTurn(): TurnPlaybackStats? {
        if (!inTurn) return null
        // A reply shorter than the prime threshold is still sitting in a paused track.
        if (!sink.isPlaying && bufferedFrames() > 0) startPlayback()
        return closeTurn()
    }

    /**
     * The track was paused + flushed (barge-in). Resets the frame count to match the flushed
     * head and returns the abandoned turn's stats, or null if none was in progress.
     */
    fun onFlushed(): TurnPlaybackStats? {
        framesWritten = 0
        return if (inTurn) closeTurn() else null
    }

    private fun startTurn() {
        inTurn = true
        chunks = 0
        turnFrames = 0
        rebuffers = 0
        droppedBytes = 0
        underrunsAtFirstPlay = -1
    }

    private fun startPlayback() {
        if (underrunsAtFirstPlay < 0) underrunsAtFirstPlay = sink.underrunCount()
        sink.play()
    }

    private fun closeTurn(): TurnPlaybackStats {
        inTurn = false
        val hal = if (underrunsAtFirstPlay < 0) 0
        else (sink.underrunCount() - underrunsAtFirstPlay).coerceAtLeast(0)
        return TurnPlaybackStats(
            chunks = chunks,
            audioMs = turnFrames * 1000 / sampleRate,
            rebuffers = rebuffers,
            halUnderruns = hal,
            droppedBytes = droppedBytes,
        )
    }

    companion object {
        /** Consecutive zero-byte writes before a chunk is given up on (the track is wedged). */
        const val MAX_ZERO_WRITES = 3
    }
}

/**
 * Decides when to stop waiting for queued playback to finish before the mic re-opens.
 *
 * Replaces a fixed 1.5s cap. Gemini streams audio faster than real time, so at turnComplete a
 * long reply can still have seconds queued; the old cap opened the mic over live playback, which
 * on this head unit's HAL (no concurrent capture + playback) glitches the output and lets the mic
 * hear Zero. Now: wait while the backlog keeps moving, give up only when it stops moving for
 * [stallMs] (a wedged track must not hold the mic shut) or after [capMs] total.
 */
class PlaybackDrainWatch(
    private val stallMs: Long,
    private val capMs: Long,
    private val startMs: Long,
) {
    enum class Verdict { WAIT, DRAINED, STALLED, CAPPED }

    private var lastBacklog = Long.MIN_VALUE
    private var lastProgressMs = startMs

    fun step(backlogFrames: Long, nowMs: Long): Verdict {
        if (backlogFrames <= 0) return Verdict.DRAINED
        if (backlogFrames != lastBacklog) {
            lastBacklog = backlogFrames
            lastProgressMs = nowMs
        }
        if (nowMs - startMs >= capMs) return Verdict.CAPPED
        if (nowMs - lastProgressMs >= stallMs) return Verdict.STALLED
        return Verdict.WAIT
    }
}
