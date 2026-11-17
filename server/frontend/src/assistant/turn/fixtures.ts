/**
 * Synthesized 16 kHz PCM for the turn-taking tests (no recording of anyone): a voiced "speech"
 * signal (a 150 Hz buzz with harmonics, syllable-shaped at 4 Hz), silence, the assistant's own
 * reply as the mic hears it (the same kind of signal, quieter: the echo after the browser's echo
 * cancellation), and the person talking over that echo.
 */

export const RATE = 16_000

/** Voiced, syllable-shaped speech at `peak` (int16 units). */
export function speech(ms: number, peak = 12_000, seed = 0): Int16Array {
  const n = Math.round((ms / 1000) * RATE)
  const out = new Int16Array(n)
  const f0 = 150 + seed * 23
  for (let i = 0; i < n; i++) {
    const t = i / RATE
    const syllable = 0.55 + 0.45 * Math.sin(2 * Math.PI * 4 * t + seed)
    let v = 0
    for (let h = 1; h <= 6; h++) v += Math.sin(2 * Math.PI * f0 * h * t + h * seed) / h
    out[i] = Math.max(-32767, Math.min(32767, Math.round((v / 2.45) * peak * syllable)))
  }
  return out
}

export function silence(ms: number): Int16Array {
  return new Int16Array(Math.round((ms / 1000) * RATE))
}

/** The reply's echo: speech in another voice at a lower level. */
export function echo(ms: number, peak = 5_000): Int16Array {
  return speech(ms, peak, 3)
}

/** The person talking over the reply's echo. */
export function over(ms: number, userPeak = 16_000, echoPeak = 5_000): Int16Array {
  const a = speech(ms, userPeak, 0)
  const b = echo(ms, echoPeak)
  const out = new Int16Array(a.length)
  for (let i = 0; i < a.length; i++) out[i] = Math.max(-32767, Math.min(32767, a[i] + b[i]))
  return out
}

export function concat(...parts: Int16Array[]): Int16Array {
  const out = new Int16Array(parts.reduce((n, p) => n + p.length, 0))
  let at = 0
  for (const p of parts) {
    out.set(p, at)
    at += p.length
  }
  return out
}

/** Splits into the 20 ms chunks the capture worklet hands over. */
export function chunks(pcm: Int16Array, size = 320): Int16Array[] {
  const out: Int16Array[] = []
  for (let at = 0; at < pcm.length; at += size) out.push(pcm.slice(at, at + size))
  return out
}
