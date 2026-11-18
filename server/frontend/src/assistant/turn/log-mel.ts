/**
 * Smart Turn v3.2's input features: the phone's `WhisperLogMel.kt` (app/.../service/turn/), ported.
 * The same numbers as Hugging Face's `WhisperFeatureExtractor(chunk_length=8)` with
 * `do_normalize=True` on 16 kHz mono float audio cut to its last 8 s, zero-padded at the FRONT:
 * 1. zero mean, unit variance over all 128,000 samples (`var + 1e-7`, population variance);
 * 2. reflect-pad 200 samples each side, 800 frames of 400 samples, hop 160, periodic Hann window;
 * 3. power spectrum of a 400-point DFT, bins 0..200;
 * 4. 80 Slaney mel filters over 0..8000 Hz, floor 1e-10, log10;
 * 5. clamp to `max - 8`, then `(x + 4) / 4`.
 * Step 1 is affine, so a frame's normalised spectrum is `(X_raw - mean * DFT(window)) / std`, and a
 * frame's RAW spectrum is cached by its position in the turn: a re-check later in the same silence
 * transforms only new frames, as on the phone. The DFT here is a direct table product (the phone uses
 * a real FFT); `log-mel.test.ts` holds the result against the phone's Python golden vectors.
 * Output: `[80 * 800]`, mel-major, the row-major layout of the model's `[1, 80, 800]` input.
 */

export const SAMPLE_RATE = 16_000
export const N_SAMPLES = 8 * SAMPLE_RATE
export const N_FFT = 400
export const HOP = 160
export const N_BINS = N_FFT / 2 + 1
export const N_MELS = 80
export const N_FRAMES = 800
const MEL_FLOOR = 1e-10
const DYNAMIC_RANGE = 8
const MIN_LOG_HZ = 1000
const MIN_LOG_MEL = 15
const LOGSTEP = Math.log(6.4) / 27

export function hzToMel(hz: number): number {
  return hz >= MIN_LOG_HZ ? MIN_LOG_MEL + Math.log(hz / MIN_LOG_HZ) / LOGSTEP : (3 * hz) / 200
}

export function melToHz(mel: number): number {
  return mel >= MIN_LOG_MEL ? MIN_LOG_HZ * Math.exp(LOGSTEP * (mel - MIN_LOG_MEL)) : (200 * mel) / 3
}

const ZERO = new Float32Array(2 * N_BINS)

export class WhisperLogMel {
  private readonly window = new Float32Array(N_FFT)
  private readonly cos = new Float32Array(N_BINS * N_FFT)
  private readonly sin = new Float32Array(N_BINS * N_FFT)
  private readonly windowRe = new Float32Array(N_BINS)
  private readonly windowIm = new Float32Array(N_BINS)
  private readonly melLo = new Int32Array(N_MELS)
  private readonly melWeights: Float32Array[] = []
  private readonly frame = new Float32Array(N_FFT)
  private readonly power = new Float32Array(N_BINS)
  private readonly scratch = new Float32Array(2 * N_BINS)
  private readonly out = new Float32Array(N_MELS * N_FRAMES)
  private cache = new Map<number, Float32Array>()
  private cacheTurn: unknown = null
  lastFramesComputed = 0

  constructor() {
    for (let i = 0; i < N_FFT; i++) this.window[i] = 0.5 - 0.5 * Math.cos((2 * Math.PI * i) / N_FFT)
    for (let k = 0; k < N_BINS; k++) {
      for (let n = 0; n < N_FFT; n++) {
        const a = (-2 * Math.PI * ((k * n) % N_FFT)) / N_FFT
        this.cos[k * N_FFT + n] = Math.cos(a)
        this.sin[k * N_FFT + n] = Math.sin(a)
      }
    }
    this.dft(this.window, this.scratch)
    this.windowRe.set(this.scratch.subarray(0, N_BINS))
    this.windowIm.set(this.scratch.subarray(N_BINS))
    this.buildMelFilters()
  }

  /** Features for `audio` exactly as the Python extractor computes them. No cache. */
  extract(audio: Float32Array): Float32Array {
    return this.compute(audio, audio.length, null).slice()
  }

  /** Features for the first `length` samples of a turn buffer that only grows; spectra are reused. */
  extractTurn(turn: Float32Array, length: number, turnKey: unknown): Float32Array {
    if (turnKey !== this.cacheTurn) {
      this.cache = new Map()
      this.cacheTurn = turnKey
    }
    return this.compute(turn, length - (length % HOP), this.cache)
  }

  resetTurn() {
    this.cache = new Map()
    this.cacheTurn = null
  }

  private dft(x: Float32Array, out: Float32Array) {
    for (let k = 0; k < N_BINS; k++) {
      let re = 0
      let im = 0
      const row = k * N_FFT
      for (let n = 0; n < N_FFT; n++) {
        const v = x[n]
        re += v * this.cos[row + n]
        im += v * this.sin[row + n]
      }
      out[k] = re
      out[N_BINS + k] = im
    }
  }

  private compute(audio: Float32Array, length: number, frameCache: Map<number, Float32Array> | null): Float32Array {
    const base = length - N_SAMPLES
    const first = Math.max(0, base)
    let sum = 0
    for (let i = first; i < length; i++) sum += audio[i]
    const mean = sum / N_SAMPLES
    let sq = 0
    for (let i = first; i < length; i++) {
      const d = audio[i] - mean
      sq += d * d
    }
    sq += (N_SAMPLES - (length - first)) * mean * mean
    const std = Math.sqrt(sq / N_SAMPLES + 1e-7)
    const invVar = 1 / (std * std)
    let computed = 0
    let globalMax = -Infinity
    const half = N_FFT / 2
    for (let t = 0; t < N_FRAMES; t++) {
      const startInWindow = t * HOP - half
      const absStart = base + startInWindow
      const inside = startInWindow >= 0 && startInWindow + N_FFT <= N_SAMPLES
      const key = Math.floor((absStart + half) / HOP)
      const cached = inside && frameCache ? frameCache.get(key) : undefined
      const allPadding = inside && absStart + N_FFT <= 0
      let spec: Float32Array
      if (cached) spec = cached
      else if (allPadding) spec = ZERO
      else {
        for (let i = 0; i < N_FFT; i++) this.frame[i] = this.rawAt(audio, length, base, startInWindow + i) * this.window[i]
        this.dft(this.frame, this.scratch)
        computed++
        if (inside && frameCache) {
          spec = this.scratch.slice()
          frameCache.set(key, spec)
        } else spec = this.scratch
      }
      for (let k = 0; k < N_BINS; k++) {
        const r = spec[k] - mean * this.windowRe[k]
        const i = spec[N_BINS + k] - mean * this.windowIm[k]
        this.power[k] = (r * r + i * i) * invVar
      }
      for (let mel = 0; mel < N_MELS; mel++) {
        const w = this.melWeights[mel]
        const lo = this.melLo[mel]
        let acc = 0
        for (let i = 0; i < w.length; i++) acc += w[i] * this.power[lo + i]
        const v = Math.log10(Math.max(MEL_FLOOR, acc))
        this.out[mel * N_FRAMES + t] = v
        if (v > globalMax) globalMax = v
      }
    }
    this.lastFramesComputed = computed
    if (frameCache) {
      const minKey = Math.floor(base / HOP) - 1
      for (const k of frameCache.keys()) if (k < minKey) frameCache.delete(k)
    }
    const floor = globalMax - DYNAMIC_RANGE
    for (let i = 0; i < this.out.length; i++) this.out[i] = (Math.max(this.out[i], floor) + 4) / 4
    return this.out
  }

  private rawAt(audio: Float32Array, length: number, base: number, j: number): number {
    const w = j < 0 ? -j : j >= N_SAMPLES ? 2 * (N_SAMPLES - 1) - j : j
    const idx = base + w
    return idx < 0 || idx >= length ? 0 : audio[idx]
  }

  private buildMelFilters() {
    const melMax = hzToMel(SAMPLE_RATE / 2)
    const filterFreqs = Array.from({ length: N_MELS + 2 }, (_, i) => melToHz((melMax * i) / (N_MELS + 1)))
    const fftFreqs = Array.from({ length: N_BINS }, (_, k) => ((SAMPLE_RATE / 2) * k) / (N_BINS - 1))
    for (let m = 0; m < N_MELS; m++) {
      const lower = filterFreqs[m]
      const centre = filterFreqs[m + 1]
      const upper = filterFreqs[m + 2]
      const enorm = 2 / (upper - lower)
      const weights = fftFreqs.map((f) => Math.max(0, Math.min((f - lower) / (centre - lower), (upper - f) / (upper - centre))) * enorm)
      let firstBin = weights.findIndex((x) => x > 0)
      if (firstBin < 0) firstBin = 0
      let lastBin = firstBin
      for (let k = weights.length - 1; k >= 0; k--) if (weights[k] > 0) { lastBin = Math.max(k, firstBin); break }
      this.melLo[m] = firstBin
      this.melWeights[m] = Float32Array.from(weights.slice(firstBin, lastBin + 1))
    }
  }
}
