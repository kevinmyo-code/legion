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
 * audio that has already been cut to its last 8 s and zero-padded at the FRONT (pipecat-ai/smart-turn
 * `inference.py`, and the offline eval in the 2026-10-27 turn-taking work).
 *
 * The pipeline, step for step with transformers 5.x's numpy path (`_np_extract_fbank_features`):
 * 1. zero mean, unit variance over all 128,000 samples (`var + 1e-7`, population variance);
 * 2. reflect-pad 200 samples each side, 801 frames of 400 samples, hop 160, periodic Hann window;
 * 3. power spectrum of a 400-point DFT, bins 0..200;
 * 4. 80 Slaney-scale, Slaney-normalised triangular mel filters over 0..8000 Hz, floor 1e-10, log10;
 * 5. drop the last frame (800 remain), clamp to `max - 8`, then `(x + 4) / 4`.
 *
 * Output is `[80 * 800]`, mel-major (`m * 800 + t`), which is the row-major layout of the model's
 * `[1, 80, 800]` input. `WhisperLogMelGoldenTest` holds it against vectors produced by the Python
 * extractor. Not thread-safe: one instance per inference thread, buffers are reused.
 */
internal class WhisperLogMel {
    private val window = DoubleArray(N_FFT) { 0.5 - 0.5 * cos(2.0 * PI * it / N_FFT) }
    private val fft = Fft400()
    private val melLo = IntArray(N_MELS)
    private val melWeights = arrayOfNulls<DoubleArray>(N_MELS)

    private val padded = DoubleArray(N_SAMPLES + N_FFT)
    private val re = DoubleArray(N_FFT)
    private val im = DoubleArray(N_FFT)
    private val power = DoubleArray(N_BINS)

    init {
        buildMelFilters()
    }

    /** The last [N_SAMPLES] of [audio], zero-padded at the front when shorter. */
    fun prepare(audio: FloatArray): FloatArray {
        if (audio.size == N_SAMPLES) return audio
        val out = FloatArray(N_SAMPLES)
        if (audio.size > N_SAMPLES) {
            System.arraycopy(audio, audio.size - N_SAMPLES, out, 0, N_SAMPLES)
        } else {
            System.arraycopy(audio, 0, out, N_SAMPLES - audio.size, audio.size)
        }
        return out
    }

    /** Features for [audio] (any length; see [prepare]). */
    fun extract(audio: FloatArray): FloatArray {
        val x = prepare(audio)
        normaliseAndPad(x)
        val out = FloatArray(N_MELS * N_FRAMES)
        var globalMax = Double.NEGATIVE_INFINITY
        for (t in 0 until N_FRAMES) {
            frameSpectrum(t * HOP)
            for (m in 0 until N_MELS) {
                val w = melWeights[m]!!
                val lo = melLo[m]
                var acc = 0.0
                for (i in w.indices) acc += w[i] * power[lo + i]
                val v = log10(max(MEL_FLOOR, acc))
                out[m * N_FRAMES + t] = v.toFloat()
                if (v > globalMax) globalMax = v
            }
        }
        // Frame 800 (the 801st) is computed by the Python extractor and then dropped before the max,
        // so it is never computed here at all.
        val floor = (globalMax - DYNAMIC_RANGE).toFloat()
        for (i in out.indices) out[i] = (max(out[i], floor) + 4f) / 4f
        return out
    }

    private fun normaliseAndPad(x: FloatArray) {
        var sum = 0.0
        for (v in x) sum += v
        val mean = sum / x.size
        var sq = 0.0
        for (v in x) {
            val d = v - mean
            sq += d * d
        }
        val inv = 1.0 / sqrt(sq / x.size + 1e-7)
        val half = N_FFT / 2
        for (i in x.indices) padded[half + i] = (x[i] - mean) * inv
        // numpy 'reflect': the edge sample is not repeated.
        for (i in 0 until half) {
            padded[half - 1 - i] = padded[half + 1 + i]
            padded[half + N_SAMPLES + i] = padded[half + N_SAMPLES - 2 - i]
        }
    }

    private fun frameSpectrum(start: Int) {
        for (i in 0 until N_FFT) {
            re[i] = padded[start + i] * window[i]
            im[i] = 0.0
        }
        fft.transform(re, im)
        for (k in 0 until N_BINS) power[k] = re[k] * re[k] + im[k] * im[k]
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
            val first = weights.indexOfFirst { it > 0.0 }.coerceAtLeast(0)
            val last = weights.indexOfLast { it > 0.0 }.coerceAtLeast(first)
            melLo[m] = first
            melWeights[m] = weights.copyOfRange(first, last + 1)
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
        private const val DYNAMIC_RANGE = 8.0

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
 * A 400-point complex FFT (Stockham autosort, decimation in frequency, radices 4, 4, 5, 5), so the
 * 801 frames of one Smart Turn check cost a few million multiply-adds instead of the 64 million a
 * plain DFT would. Output is in natural order, in place.
 */
internal class Fft400 {
    private val n = 400
    private val radices = intArrayOf(4, 4, 5, 5)
    private val cosT = DoubleArray(n) { cos(-2.0 * PI * it / n) }
    private val sinT = DoubleArray(n) { sin(-2.0 * PI * it / n) }
    private val yr = DoubleArray(n)
    private val yi = DoubleArray(n)
    private val ar = DoubleArray(5)
    private val ai = DoubleArray(5)

    fun transform(re: DoubleArray, im: DoubleArray) {
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
                        var sr = 0.0
                        var si = 0.0
                        for (j in 0 until r) {
                            val idx = (j * k * rootStep) % n
                            val c = cosT[idx]
                            val s = sinT[idx]
                            sr += ar[j] * c - ai[j] * s
                            si += ar[j] * s + ai[j] * c
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
            // Ping-pong.
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
