package com.kevin.legion.service.speaker

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The arithmetic of the speaker check, ported from the study's `study.py` so the phone treats audio
 * exactly as the measured arm did: 20 ms frames, an energy-only window pick, gain normalisation of
 * the active frames to about -26 dBFS, and cosine against the mean of L2-normalised embeddings.
 * Pure: no Android, no model.
 */
object SpeakerMath {
    const val SAMPLE_RATE = 16_000

    /** 20 ms at 16 kHz, the study's frame. */
    const val FRAME = 320

    /** Active-frame RMS after normalisation, as a fraction of full scale (about -26 dBFS). */
    const val TARGET_ACTIVE_RMS = 0.05

    private const val FULL_SCALE = 32768.0
    private const val MIN_GAIN_RMS = 1e-6
    private const val NOISE_PERCENTILE = 10.0
    private const val ACTIVE_FLOOR_RATIO = 3.0
    private const val ACTIVE_FLOOR_OFFSET = 3.0
    private const val PERCENT = 100.0
    private const val EPS = 1e-9
    private const val MS_PER_SECOND = 1000L

    fun samples(ms: Int): Int = (SAMPLE_RATE.toLong() * ms / MS_PER_SECOND).toInt()

    fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x
        val n = sqrt(sum) + EPS
        return FloatArray(v.size) { (v[it] / n).toFloat() }
    }

    /** Cosine similarity; 0 when either vector is all zeros or the sizes differ. */
    @Suppress("ReturnCount") // two refusals, then the value
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            na += a[i].toDouble() * a[i]
            nb += b[i].toDouble() * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0f
        return (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }

    /** The voiceprint: the mean of the L2-normalised embeddings. Null for none or mixed sizes. */
    @Suppress("ReturnCount") // two refusals, then the value
    fun centroid(embeddings: List<FloatArray>): FloatArray? {
        val dim = embeddings.firstOrNull()?.size ?: return null
        if (dim == 0 || embeddings.any { it.size != dim }) return null
        val sum = DoubleArray(dim)
        for (e in embeddings) {
            val u = l2Normalize(e)
            for (i in 0 until dim) sum[i] += u[i].toDouble()
        }
        return FloatArray(dim) { (sum[it] / embeddings.size).toFloat() }
    }

    /** RMS of each whole 20 ms frame, in int16 units. */
    fun frameRms(pcm: ShortArray): DoubleArray {
        val frames = pcm.size / FRAME
        return DoubleArray(frames) { f ->
            var s = 0.0
            for (i in f * FRAME until (f + 1) * FRAME) s += pcm[i].toDouble() * pcm[i]
            sqrt(s / FRAME)
        }
    }

    /** numpy's default (linear) percentile. */
    fun percentile(values: DoubleArray, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sortedArray()
        val pos = p / PERCENT * (s.size - 1)
        val lo = floor(pos).toInt()
        val hi = ceil(pos).toInt()
        return s[lo] + (s[hi] - s[lo]) * (pos - lo)
    }

    /**
     * RMS of the active frames (int16 units): frames louder than max(3 x floor, floor + 3), where
     * the floor is the 10th-percentile frame. All frames when none is active, the whole signal when
     * it is shorter than a frame.
     */
    fun activeRms(pcm: ShortArray): Double {
        val rms = frameRms(pcm)
        if (rms.isEmpty()) {
            var s = 0.0
            for (v in pcm) s += v.toDouble() * v
            return if (pcm.isEmpty()) 0.0 else sqrt(s / pcm.size)
        }
        val nf = percentile(rms, NOISE_PERCENTILE)
        val gate = max(ACTIVE_FLOOR_RATIO * nf, nf + ACTIVE_FLOOR_OFFSET)
        val active = rms.filter { it > gate }
        val use = if (active.isEmpty()) rms.toList() else active
        return sqrt(use.sumOf { it * it } / use.size)
    }

    /** int16 to float in [-1, 1], scaled so the active frames sit at [TARGET_ACTIVE_RMS]. */
    fun normalise(pcm: ShortArray): FloatArray {
        val r = activeRms(pcm) / FULL_SCALE
        val g = TARGET_ACTIVE_RMS / max(r, MIN_GAIN_RMS)
        return FloatArray(pcm.size) { ((pcm[it] / FULL_SCALE) * g).coerceIn(-1.0, 1.0).toFloat() }
    }

    /**
     * Start of the loudest [width]-sample window inside [lo, hi), stepping one frame at a time and
     * choosing by energy only, never by the speaker model. A range no wider than the window gives
     * the window ending at [hi].
     */
    fun bestWindowStart(pcm: ShortArray, width: Int, lo: Int, hi: Int): Int {
        val end = hi.coerceAtMost(pcm.size)
        val start = lo.coerceAtLeast(0)
        if (end - start <= width) return (end - width).coerceAtLeast(0)
        val prefix = DoubleArray(pcm.size + 1)
        for (i in pcm.indices) prefix[i + 1] = prefix[i] + pcm[i].toDouble() * pcm[i]
        var best = -1.0
        var bestAt = start
        var st = start
        while (st + width <= end) {
            val e = prefix[st + width] - prefix[st]
            if (e > best) {
                best = e
                bestAt = st
            }
            st += FRAME
        }
        return bestAt
    }
}

/** The scored window, the same function for the live check and for enrolment. */
object SpeakerWindow {
    /**
     * The loudest [SpeakerPolicy.WINDOW_MS] in the [SpeakerPolicy.SEARCH_MS] ending at sample
     * [end] of [pcm]. Shorter audio gives what there is. Empty when there is nothing before [end].
     */
    fun pick(pcm: ShortArray, end: Int = pcm.size): ShortArray {
        val stop = end.coerceIn(0, pcm.size)
        if (stop == 0) return ShortArray(0)
        val width = SpeakerMath.samples(SpeakerPolicy.WINDOW_MS)
        val lo = stop - SpeakerMath.samples(SpeakerPolicy.SEARCH_MS)
        val start = SpeakerMath.bestWindowStart(pcm, width, lo, stop)
        return pcm.copyOfRange(start, (start + width).coerceAtMost(stop))
    }
}

/** The last [capacity] samples the wake engine captured, oldest first on [snapshot]. */
class SpeakerRing(val capacity: Int) {
    private val buf = ShortArray(capacity)
    private var written = 0L

    @Synchronized
    fun push(src: ShortArray, n: Int) {
        val count = n.coerceIn(0, src.size)
        for (i in 0 until count) buf[((written + i) % capacity).toInt()] = src[i]
        written += count
    }

    @Synchronized
    fun reset() {
        written = 0L
    }

    @Synchronized
    fun snapshot(): ShortArray {
        val have = minOf(written, capacity.toLong()).toInt()
        val start = written - have
        return ShortArray(have) { buf[((start + it) % capacity).toInt()] }
    }
}
