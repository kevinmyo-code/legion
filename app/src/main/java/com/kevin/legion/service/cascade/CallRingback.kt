package com.kevin.legion.service.cascade

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * When the ringing stops (Kevin, 2026-11-28: "it should play a ringing sound for about 3 rings till
 * Alfred picks up"). Pure, so every case is a unit test.
 *
 * The phone rings [RINGS] times, then Alfred answers. If he is not ready by then (the voice service
 * is still opening), it keeps ringing, as a phone does, up to [MAX_RINGS]; past that it stops and
 * the call goes on as it would have without a ringback, so a slow open is never made slower by it.
 *
 * The cadence is shorter than a landline's (2 s on, 4 s off), which would hold the pickup for about
 * fourteen seconds: three of these rings come to about eight.
 */
internal object RingbackRule {
    const val RINGS = 3
    const val MAX_RINGS = 6
    const val RING_ON_MS = 1_600L
    const val RING_OFF_MS = 1_200L

    /** The ANSI ringback pair, so it sounds like a phone ringing on the other end, not an alarm. */
    const val LOW_HZ = 440.0
    const val HIGH_HZ = 480.0
    const val SAMPLE_RATE = 16_000

    /** Soft edges, so a ring starts and stops without a click. */
    const val RAMP_MS = 15L

    /** Each tone at a quarter of full scale: the pair together peaks at half, never clipping. */
    const val TONE_LEVEL = 0.25

    /** After ring [played] (counted from 1): ring again, or let Alfred answer. */
    fun ringAgain(played: Int, answerReady: Boolean): Boolean =
        played < RINGS || (!answerReady && played < MAX_RINGS)

    /** About how long the minimum rings hold the pickup, gaps between them included. */
    fun minimumMs(): Long = RINGS * RING_ON_MS + (RINGS - 1) * RING_OFF_MS

    /** One ring's tone: PCM16 little-endian mono at [rate]. */
    fun tone(rate: Int = SAMPLE_RATE, ms: Long = RING_ON_MS): ByteArray {
        val n = (rate * ms / MS_PER_S).toInt()
        val ramp = (rate * RAMP_MS / MS_PER_S).toInt().coerceAtLeast(1)
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val edge = min(1.0, min(i.toDouble() / ramp, (n - 1 - i).toDouble() / ramp))
            val v = TONE_LEVEL * edge * (sin(2 * PI * LOW_HZ * t) + sin(2 * PI * HIGH_HZ * t))
            val s = (v * Short.MAX_VALUE).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[2 * i] = (s and BYTE_MASK).toByte()
            out[2 * i + 1] = ((s shr BITS_PER_BYTE) and BYTE_MASK).toByte()
        }
        return out
    }

    /** The gap between rings: silence, written as audio so the track keeps its place. */
    fun silence(rate: Int = SAMPLE_RATE, ms: Long = RING_OFF_MS): ByteArray = ByteArray((rate * ms / MS_PER_S).toInt() * 2)

    private const val MS_PER_S = 1_000L
    private const val BYTE_MASK = 0xFF
    private const val BITS_PER_BYTE = 8
}

/**
 * The ringing he hears after pressing Call, before Alfred picks up ([RingbackRule]). One per call,
 * started once the call's route is entered, so it rings where the call will be heard (the earpiece,
 * or the loudspeaker), and stopped by the session's release, so a hang-up while it rings is silent
 * at once.
 */
internal class CallRingback(private val scope: CoroutineScope) {
    private val ready = CompletableDeferred<Unit>()
    private var job: Job? = null
    @Volatile private var track: AudioTrack? = null

    fun start() {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            val t = runCatching { build() }.getOrElse {
                Log.w(TAG, "ringback track: ${it.message}")
                return@launch
            }
            track = t
            val ring = RingbackRule.tone()
            val gap = RingbackRule.silence()
            var played = 0
            var frames = 0
            try {
                t.play()
                while (isActive) {
                    t.write(ring, 0, ring.size)
                    frames += ring.size / 2
                    played++
                    if (!RingbackRule.ringAgain(played, ready.isCompleted)) break
                    t.write(gap, 0, gap.size)
                    frames += gap.size / 2
                }
                // The last ring plays out before the pickup starts on the other track: a write
                // returns while up to half a second of it is still in the buffer.
                val until = SystemClock.elapsedRealtime() + DRAIN_CAP_MS
                while (isActive && t.playbackHeadPosition < frames && SystemClock.elapsedRealtime() < until) {
                    delay(DRAIN_POLL_MS)
                }
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Log.w(TAG, "ringback: ${e.message}")
            } finally {
                stop()
            }
        }
    }

    /** Alfred can answer now: the ringing stops after the minimum rings, not before. */
    fun answerReady() {
        ready.complete(Unit)
    }

    /** Waits for the ringing to finish. Returns at once when it never started. */
    suspend fun awaitDone() {
        job?.join()
    }

    /** Silent now: a hang-up, a crisis, a failed open. Safe to call twice. */
    fun stop() {
        job?.cancel()
        val t = track ?: return
        track = null
        runCatching {
            t.pause()
            t.flush()
        }
        runCatching { t.release() }
    }

    private fun build(): AudioTrack {
        val rate = RingbackRule.SAMPLE_RATE
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // The usage the reply player takes on a call, so the ring follows the call's route
                    // and is at his ear when the call is.
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf, rate))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private companion object {
        const val TAG = "CallRingback"
        const val DRAIN_CAP_MS = 1_500L
        const val DRAIN_POLL_MS = 20L
    }
}
