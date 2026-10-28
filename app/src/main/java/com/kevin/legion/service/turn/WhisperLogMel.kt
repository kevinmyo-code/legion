package com.kevin.legion.service.turn

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Smart Turn v3.2's input features, computed natively: the same numbers as Hugging Face's
 * `WhisperFeatureExtractor(chunk_length=8)` called with `do_normalize=True` on 16 kHz mono float
 * audio cut to its last 8 s and zero-padded at the FRONT (pipecat-ai/smart-turn `inference.py`, and
 * the offline eval behind the 2026-10-27 turn-taking work).
 *
 * The pipeline, step for step with transformers 5.x's numpy path (`_np_extract_fbank_features`):
 * 1. zero mean, unit variance over all 128,000 samples (`var + 1e-7`, population variance);
 * 2. reflect-pad 200 samples each side, 801 frames of 400 samples, hop 160, periodic Hann window;
 * 3. power spectrum of a 400-point DFT, bins 0..200;
 * 4. 80 Slaney-scale, Slaney-normalised triangular mel filters over 0..8000 Hz, floor 1e-10, log10;
 * 5. drop the last frame (800 remain), clamp to `max - 8`, then `(x + 4) / 4`.
 *
 * **Cheaper than it looks, on purpose (A25, 2026-10-27: the first port took 172 ms).** The DFT is a
 * real-input FFT (one 200-point complex FFT plus a split), in floats. And because step 1 is affine,
 * a frame's normalised spectrum is `(X_raw - mean * DFT(window)) / std`: [extractTurn] keeps each
 * frame's RAW spectrum keyed by its position in the turn, so a re-check later in the same silence
 * only transforms the frames it has not seen. That needs the frame grid to stay put between checks,
 * so [extractTurn] uses the turn's length rounded down to a whole hop (at most 9.9 ms of the newest
 * audio, which at a check is silence).
 *
 * Output is `[80 * 800]`, mel-major (`m * 800 + t`), the row-major layout of the model's
 * `[1, 80, 800]` input. `WhisperLogMelGoldenTest` holds it against vectors from the Python
 * extractor. Not thread-safe: one instance per inference thread.
 */
internal class WhisperLogMel {
    private val window = FloatArray(N_FFT) { (0.5 - 0.5 * cos(2.0 * PI * it / N_FFT)).toFloat() }
    private val rfft = RealFft400()
    private val melLo = IntArray(N_MELS)
    private val melWeights = arrayOfNulls<FloatArray>(N_MELS)

    // DFT of the window itself: what a constant (the mean) contributes to every frame.
    private val windowRe = FloatArray(N_BINS)
    private val windowIm = FloatArray(N_BINS)

    private val frame = FloatArray(N_FFT)
    private val power = FloatArray(N_BINS)
    private val scratchRe = FloatArray(N_BINS)
    private val scratchIm = FloatArray(N_BINS)
    private val out = FloatArray(N_MELS * N_FRAMES)

    // Raw (un-normalised) spectra of frames that lie wholly inside the turn, keyed by hop index.
    private val cache = HashMap<Int, FloatArray>()
    private var cacheTurn: Any? = null

    /** Frames transformed (not served from the cache) by the last call. For logs and tests. */
    var lastFramesComputed = 0
        private set

    init {
        buildMelFilters()
        rfft.transform(window, windowRe, windowIm)
    }

    /**
     * Features for [audio] exactly as the Python extractor computes them: any length, last 8 s,
     * front zero-padding. Allocates its result; uses no cache.
     */
    fun extract(audio: FloatArray): FloatArray = compute(audio, audio.size, null).copyOf()

    /**
     * Features for the first [length] samples of [turn], a buffer that only ever grows within one
     * turn (same array or a longer copy, same [turnKey]). Spectra of frames already seen in this
     * turn are reused. Returns an internal buffer, valid until the next call.
     */
    fun extractTurn(turn: FloatArray, length: Int, turnKey: Any): FloatArray {
        if (turnKey !== cacheTurn) {
            cache.clear()
            cacheTurn = turnKey
        }
        val aligned = length - length % HOP
        return compute(turn, aligned, cache)
    }

    /** Drops cached spectra; the next [extractTurn] starts a new turn. */
    fun resetTurn() {
        cache.clear()
        cacheTurn = null
    }

    private fun compute(audio: FloatArray, length: Int, frameCache: HashMap<Int, FloatArray>?): FloatArray {
        // Window = audio[length - N_SAMPLES, length), negative indices are the front zero-padding.
        val base = length - N_SAMPLES
        var sum = 0.0
        val first = max(0, base)
        for (i in first until length) sum += audio[i]
        val mean = sum / N_SAMPLES
        var sq = 0.0
        for (i in first until length) {
            val d = audio[i] - mean
            sq += d * d
        }
        // The zero-padded samples each contribute mean^2.
        sq += (N_SAMPLES - (length - first)) * mean * mean
        val std = sqrt(sq / N_SAMPLES + 1e-7)
        val m = mean.toFloat()
        val invVar = (1.0 / (std * std)).toFloat()

        var computed = 0
        var globalMax = Float.NEGATIVE_INFINITY
        val half = N_FFT / 2
        for (t in 0 until N_FRAMES) {
            // Frame start in padded-window coordinates is t*HOP; in audio coordinates subtract half.
            val startInWindow = t * HOP - half
            val absStart = base + startInWindow
            val inside = startInWindow >= 0 && startInWindow + N_FFT <= N_SAMPLES
            val key = (absStart + half) / HOP
            val cached = if (inside && frameCache != null) frameCache[key] else null
            val spec: FloatArray
            if (cached != null) {
                spec = cached
            } else {
                for (i in 0 until N_FFT) frame[i] = rawAt(audio, length, base, startInWindow + i) * window[i]
                rfft.transform(frame, scratchRe, scratchIm)
                computed++
                if (inside && frameCache != null) {
                    spec = FloatArray(2 * N_BINS)
                    System.arraycopy(scratchRe, 0, spec, 0, N_BINS)
                    System.arraycopy(scratchIm, 0, spec, N_BINS, N_BINS)
                    frameCache[key] = spec
                } else {
                    spec = SHARED_SCRATCH_MARKER
                }
            }
            for (k in 0 until N_BINS) {
                val re: Float
                val im: Float
                if (spec === SHARED_SCRATCH_MARKER) {
                    re = scratchRe[k]; im = scratchIm[k]
                } else {
                    re = spec[k]; im = spec[N_BINS + k]
                }
                val r = re - m * windowRe[k]
                val i = im - m * windowIm[k]
                power[k] = (r * r + i * i) * invVar
            }
            for (mel in 0 until N_MELS) {
                val w = melWeights[mel]!!
                val lo = melLo[mel]
                var acc = 0f
                for (i in w.indices) acc += w[i] * power[lo + i]
                val v = log10(max(MEL_FLOOR, acc.toDouble())).toFloat()
                out[mel * N_FRAMES + t] = v
                if (v > globalMax) globalMax = v
            }
        }
        lastFramesComputed = computed
        if (frameCache != null) {
            // Frames that slid out of the 8 s window will never be asked for again.
            val minKey = (base + 0) / HOP - 1
            frameCache.keys.removeAll { it < minKey }
        }
        // The 801st frame is computed by the Python extractor and then dropped before the max, so
        // it is never computed here at all.
        val floor = globalMax - DYNAMIC_RANGE
        for (i in out.indices) out[i] = (max(out[i], floor) + 4f) / 4f
        return out
    }

    /**
     * The un-normalised sample at padded-window index [j] (0..N_SAMPLES+N_FFT): numpy 'reflect' at
     * both ends of the 8 s window (the edge sample is not repeated), zero-padding before the audio.
     */
    private fun rawAt(audio: FloatArray, length: Int, base: Int, j: Int): Float {
        val w = when {
            j < 0 -> -j
            j >= N_SAMPLES -> 2 * (N_SAMPLES - 1) - j
            else -> j
        }
        val idx = base + w
        return if (idx < 0 || idx >= length) 0f else audio[idx]
    }

    private fun buildMelFilters() {
        val melMax = hzToMel(SAMPLE_RATE / 2.0)
        val filterFreqs = DoubleArray(N_MELS + 2) { melToHz(melMax * it / (N_MELS + 1)) }
        val fftFreqs = DoubleArray(N_BINS) { (SAMPLE_RATE / 2).toDouble() * it / (N_BINS - 1) }
        for (m in 0 until N_MELS) {
            val lower = filterFreqs[m]
            val centre = filterFreqs[m + 1]
            val upper = filterFreqs[m + 2]
            val enorm = 2.0 / (upper - lower)
            val weights = DoubleArray(N_BINS) { k ->
                val down = (fftFreqs[k] - lower) / (centre - lower)
                val up = (upper - fftFreqs[k]) / (upper - centre)
                max(0.0, min(down, up)) * enorm
            }
            val firstBin = weights.indexOfFirst { it > 0.0 }.coerceAtLeast(0)
            val lastBin = weights.indexOfLast { it > 0.0 }.coerceAtLeast(firstBin)
            melLo[m] = firstBin
            melWeights[m] = FloatArray(lastBin - firstBin + 1) { weights[firstBin + it].toFloat() }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val N_SAMPLES = 8 * SAMPLE_RATE
        const val N_FFT = 400
        const val HOP = 160
        const val N_BINS = N_FFT / 2 + 1
        const val N_MELS = 80
        const val N_FRAMES = 800
        private const val MEL_FLOOR = 1e-10
        private const val DYNAMIC_RANGE = 8f
        private val SHARED_SCRATCH_MARKER = FloatArray(0)

        // Slaney mel scale (librosa / transformers `mel_scale="slaney"`).
        private const val MIN_LOG_HZ = 1000.0
        private const val MIN_LOG_MEL = 15.0
        private val LOGSTEP = ln(6.4) / 27.0

        fun hzToMel(hz: Double): Double =
            if (hz >= MIN_LOG_HZ) MIN_LOG_MEL + ln(hz / MIN_LOG_HZ) / LOGSTEP else 3.0 * hz / 200.0

        fun melToHz(mel: Double): Double =
            if (mel >= MIN_LOG_MEL) MIN_LOG_HZ * exp(LOGSTEP * (mel - MIN_LOG_MEL)) else 200.0 * mel / 3.0
    }
}

/**
 * The 400-point DFT of a real frame, bins 0..200: the 200-point complex FFT of the even/odd sample
 * pairs, then the standard split `X[k] = E[k] + W^k O[k]`.
 */
internal class RealFft400 {
    private val n = 400
    private val half = 200
    private val fft = StockhamFft(half, intArrayOf(4, 2, 5, 5))
    private val zr = FloatArray(half)
    private val zi = FloatArray(half)
    private val wr = FloatArray(half + 1) { cos(-2.0 * PI * it / n).toFloat() }
    private val wi = FloatArray(half + 1) { sin(-2.0 * PI * it / n).toFloat() }

    fun transform(x: FloatArray, outRe: FloatArray, outIm: FloatArray) {
        for (i in 0 until half) {
            zr[i] = x[2 * i]
            zi[i] = x[2 * i + 1]
        }
        fft.transform(zr, zi)
        for (k in 0..half) {
            val a = k % half
            val b = (half - k) % half
            // E = (Z[a] + conj(Z[b])) / 2 ; O = (Z[a] - conj(Z[b])) / 2i
            val er = 0.5f * (zr[a] + zr[b])
            val ei = 0.5f * (zi[a] - zi[b])
            val or = 0.5f * (zi[a] + zi[b])
            val oi = -0.5f * (zr[a] - zr[b])
            outRe[k] = er + wr[k] * or - wi[k] * oi
            outIm[k] = ei + wr[k] * oi + wi[k] * or
        }
    }
}

/** In-place complex FFT of size [n] = product of [radices] (Stockham autosort, natural order out). */
internal class StockhamFft(private val n: Int, private val radices: IntArray) {
    private val cosT = FloatArray(n) { cos(-2.0 * PI * it / n).toFloat() }
    private val sinT = FloatArray(n) { sin(-2.0 * PI * it / n).toFloat() }
    private val yr = FloatArray(n)
    private val yi = FloatArray(n)
    private val ar = FloatArray(radices.max())
    private val ai = FloatArray(radices.max())

    init {
        require(radices.fold(1) { a, b -> a * b } == n) { "radices must multiply to $n" }
    }

    fun transform(re: FloatArray, im: FloatArray) {
        var xr = re
        var xi = im
        var tr = yr
        var ti = yi
        var len = n
        var stride = 1
        for (r in radices) {
            val m = len / r
            val twStep = n / len
            val rootStep = n / r
            for (p in 0 until m) {
                for (q in 0 until stride) {
                    for (j in 0 until r) {
                        ar[j] = xr[q + stride * (p + j * m)]
                        ai[j] = xi[q + stride * (p + j * m)]
                    }
                    for (k in 0 until r) {
                        var sr = 0f
                        var si = 0f
                        var idx = 0
                        val step = k * rootStep
                        for (j in 0 until r) {
                            val c = cosT[idx]
                            val s = sinT[idx]
                            sr += ar[j] * c - ai[j] * s
                            si += ar[j] * s + ai[j] * c
                            idx += step
                            if (idx >= n) idx -= n
                        }
                        val tw = (k * p * twStep) % n
                        val c = cosT[tw]
                        val s = sinT[tw]
                        val o = q + stride * (r * p + k)
                        tr[o] = sr * c - si * s
                        ti[o] = sr * s + si * c
                    }
                }
            }
            val sr = xr; xr = tr; tr = sr
            val si = xi; xi = ti; ti = si
            len = m
            stride *= r
        }
        if (xr !== re) {
            System.arraycopy(xr, 0, re, 0, n)
            System.arraycopy(xi, 0, im, 0, n)
        }
    }
}
