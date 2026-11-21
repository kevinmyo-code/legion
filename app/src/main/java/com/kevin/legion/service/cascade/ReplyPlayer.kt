package com.kevin.legion.service.cascade

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

/**
 * Plays the laptop's speech: PCM16 mono at the service's rate on a MODE_STREAM [AudioTrack].
 * A copy of the playback half of [com.kevin.legion.service.GeminiLiveSession] (Kevin's rule for
 * this branch: the Live path is not refactored to share code), trimmed to what the cascade needs.
 *
 * In call-style audio (barge-in on) it plays on the voice-communication usage, so the platform's
 * echo canceller has the reply as its reference, exactly as Live does; otherwise on the assistant
 * usage. [flush] stops a reply at once (barge-in, tap) and makes every write still in flight for
 * the old reply a no-op.
 */
internal class ReplyPlayer(
    val sampleRate: Int,
    private val callAudio: () -> Boolean,
    /**
     * Speaking speed, pitch kept (2026-11-17: the cloud voice was too slow; default 1.15x): read when
     * the track is built, so a Settings change applies from the next conversation. Every voice and
     * every scripted clip plays through here, so all of them speed up alike.
     */
    private val speed: () -> Float = { 1f },
) {
    private val lock = Any()
    private var track: AudioTrack? = null
    @Volatile private var framesWritten = 0L
    private val generation = AtomicInteger(0)
    @Volatile private var released = false

    /** The generation a writer must carry; a [flush] in between makes its writes no-ops. */
    fun generation(): Int = generation.get()

    /** Writes [pcm] (blocking while the buffer is full). False once flushed or released. */
    @Suppress("ReturnCount") // every way a write stops early is a flush or a release
    fun write(pcm: ByteArray, gen: Int): Boolean {
        val t = synchronized(lock) {
            if (released || gen != generation.get()) return false
            val tr = track ?: build().also { track = it }
            try {
                if (tr.playState != AudioTrack.PLAYSTATE_PLAYING) tr.play()
            } catch (_: IllegalStateException) {
                return false
            }
            tr
        }
        var offset = 0
        while (offset < pcm.size) {
            if (gen != generation.get() || track !== t) return false
            val n = try {
                t.write(pcm, offset, pcm.size - offset)
            } catch (_: IllegalStateException) {
                return false
            }
            if (n <= 0) return false
            offset += n
        }
        framesWritten += offset / BYTES_PER_FRAME
        return true
    }

    /** Drops everything queued and playing now. */
    fun flush() {
        generation.incrementAndGet()
        synchronized(lock) {
            track?.let {
                runCatching {
                    it.pause()
                    it.flush()
                    it.play()
                }
            }
            framesWritten = 0
        }
    }

    /** Rebuilds on the next write (the call-audio mode changed). */
    fun rebuild() = synchronized(lock) {
        track?.let { runCatching { it.pause(); it.flush(); it.release() } }
        track = null
        framesWritten = 0
    }

    /** Waits until the last written frame has played, or [timeoutMs]. */
    suspend fun awaitDrained(timeoutMs: Long = DRAIN_TIMEOUT_MS) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val head = synchronized(lock) {
                val t = track ?: return@synchronized null
                if (t.playState != AudioTrack.PLAYSTATE_PLAYING) return@synchronized null
                try {
                    t.playbackHeadPosition.toLong() and HEAD_MASK
                } catch (_: IllegalStateException) {
                    null
                }
            } ?: return
            if (head >= framesWritten) return
            delay(POLL_MS)
        }
    }

    /** Audio written and not yet played, in ms of the source (0 with no playing track). */
    fun aheadMs(): Long = synchronized(lock) {
        val t = track?.takeIf { it.playState == AudioTrack.PLAYSTATE_PLAYING } ?: return@synchronized 0L
        val head = runCatching { t.playbackHeadPosition.toLong() and HEAD_MASK }.getOrElse { framesWritten }
        (framesWritten - head).coerceAtLeast(0L) * MS_PER_S / sampleRate
    }

    /** Frames written since the track was built or last flushed: the frame the last write ends on. */
    fun writtenFrames(): Long = framesWritten

    /**
     * The frame the device says it has PRESENTED ([AudioTrack.getTimestamp]), in the head position's
     * count; null when the track gives no timestamp (none yet, not playing, or unsupported).
     */
    fun presentedFrame(): Long? = synchronized(lock) {
        val t = track?.takeIf { it.playState == AudioTrack.PLAYSTATE_PLAYING } ?: return@synchronized null
        val ts = android.media.AudioTimestamp()
        runCatching { t.getTimestamp(ts) }.getOrDefault(false).takeIf { it }?.let { ts.framePosition }
    }

    fun release() = synchronized(lock) {
        released = true
        generation.incrementAndGet()
        track?.let { runCatching { it.pause(); it.flush(); it.release() } }
        track = null
    }

    private fun build(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val usage = if (callAudio()) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf, sampleRate)) // ~0.5 s, as Live's track
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.setVolume(AudioTrack.getMaxVolume())
        val rate = VoiceEngineSettings.clampSpeed(speed())
        if (rate != 1f) {
            // Time-stretched, pitch kept. A device that refuses plays at normal speed rather than not at all.
            runCatching { t.playbackParams = PlaybackParams().setSpeed(rate).setPitch(1f) }
        }
        t.play()
        framesWritten = 0
        return t
    }

    companion object {
        private const val BYTES_PER_FRAME = 2
        private const val POLL_MS = 20L
        private const val DRAIN_TIMEOUT_MS = 4_000L
        private const val HEAD_MASK = 0xFFFF_FFFFL
        private const val MS_PER_S = 1_000L
    }
}
