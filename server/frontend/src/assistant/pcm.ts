/**
 * The audio arithmetic, with no browser in it, so it is tested in Node and shared
 * by the capture worklet and the main thread.
 *
 * Gemini Live takes 16-bit little-endian mono PCM at 16 kHz in and sends the
 * same format at 24 kHz out (`research/01-live-from-an-iphone-pwa.md`, section 2).
 * A browser microphone is whatever the device runs at (an iPhone: 48 kHz), so the
 * capture side resamples; the playback side only converts.
 */

export const INPUT_RATE = 16_000

/** Float samples in [-1, 1] to 16-bit PCM, clipped rather than wrapped. */
export function floatToPcm16(input: Float32Array): Int16Array {
  const out = new Int16Array(input.length)
  for (let i = 0; i < input.length; i += 1) {
    const clipped = Math.max(-1, Math.min(1, input[i]))
    out[i] = clipped < 0 ? Math.round(clipped * 0x8000) : Math.round(clipped * 0x7fff)
  }
  return out
}

/** 16-bit PCM to floats in [-1, 1), for an `AudioBuffer`. */
export function pcm16ToFloat(input: Int16Array): Float32Array {
  const out = new Float32Array(input.length)
  for (let i = 0; i < input.length; i += 1) out[i] = input[i] / 0x8000
  return out
}

/**
 * A streaming linear resampler from `fromRate` to `toRate`.
 *
 * Linear interpolation is not hi-fi; it is fine for speech going to a speech
 * model, and a 48 to 16 kHz average over three samples also acts as a crude
 * low-pass. It keeps the fractional read position and the last sample between
 * calls, so a stream cut into 128-frame render quanta has no seams.
 */
export class Resampler {
  private position = 0
  private previous = 0
  private primed = false
  private readonly fromRate: number
  private readonly toRate: number

  constructor(fromRate: number, toRate: number = INPUT_RATE) {
    this.fromRate = fromRate
    this.toRate = toRate
  }

  process(input: Float32Array): Float32Array {
    if (this.fromRate === this.toRate) return input
    const step = this.fromRate / this.toRate
    const out: number[] = []
    // `position` is measured from the start of the stream's carried sample: index
    // -1 is `previous`, index 0 is input[0].
    let pos = this.primed ? this.position : 0
    const sample = (index: number) => (index < 0 ? this.previous : input[index])
    while (Math.floor(pos) + 1 <= input.length - 1) {
      const base = Math.floor(pos)
      const frac = pos - base
      out.push(sample(base) * (1 - frac) + sample(base + 1) * frac)
      pos += step
    }
    this.position = pos - input.length
    this.previous = input.length > 0 ? input[input.length - 1] : this.previous
    this.primed = true
    return Float32Array.from(out)
  }
}

/** Little-endian bytes of a PCM16 array, as base64 (what `realtimeInput.audio.data` wants). */
export function pcm16ToBase64(pcm: Int16Array): string {
  const bytes = new Uint8Array(pcm.buffer, pcm.byteOffset, pcm.byteLength)
  let binary = ''
  const chunk = 0x8000
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk))
  }
  return btoa(binary)
}

/** The reverse: base64 little-endian PCM16 from Gemini to samples. An odd trailing byte is dropped. */
export function base64ToPcm16(data: string): Int16Array {
  const binary = atob(data)
  const length = binary.length - (binary.length % 2)
  const view = new DataView(new ArrayBuffer(length))
  for (let i = 0; i < length; i += 1) view.setUint8(i, binary.charCodeAt(i))
  const out = new Int16Array(length / 2)
  for (let i = 0; i < out.length; i += 1) out[i] = view.getInt16(i * 2, true)
  return out
}
