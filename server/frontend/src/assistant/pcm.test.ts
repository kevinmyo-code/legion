import { describe, expect, it } from 'vitest'

import { Resampler, base64ToPcm16, floatToPcm16, pcm16ToBase64, pcm16ToFloat } from './pcm'

describe('pcm conversion', () => {
  it('clips instead of wrapping and maps the ends of the range', () => {
    const out = floatToPcm16(Float32Array.from([-2, -1, 0, 1, 2, 0.5]))
    expect(Array.from(out)).toEqual([-32768, -32768, 0, 32767, 32767, 16384])
  })

  it('round-trips through base64 as little-endian 16-bit', () => {
    const pcm = Int16Array.from([0, 1, -1, 32767, -32768, 258])
    const text = pcm16ToBase64(pcm)
    // 258 = 0x0102 little-endian is bytes 02 01 at the end.
    expect(atob(text).charCodeAt(10)).toBe(2)
    expect(atob(text).charCodeAt(11)).toBe(1)
    expect(Array.from(base64ToPcm16(text))).toEqual(Array.from(pcm))
  })

  it('drops an odd trailing byte rather than inventing a sample', () => {
    expect(base64ToPcm16(btoa('\u0001\u0000\u0002')).length).toBe(1)
  })

  it('converts to floats in [-1, 1)', () => {
    const out = pcm16ToFloat(Int16Array.from([-32768, 0, 16384]))
    expect(Array.from(out)).toEqual([-1, 0, 0.5])
  })
})

describe('Resampler', () => {
  it('passes 16 kHz through untouched', () => {
    const input = Float32Array.from([0.1, 0.2, 0.3])
    expect(new Resampler(16_000).process(input)).toBe(input)
  })

  it('48 kHz to 16 kHz yields a third as many samples, seamlessly across chunks', () => {
    const ramp = Float32Array.from({ length: 960 }, (_, i) => i / 960)
    const whole = new Resampler(48_000).process(ramp)
    const split = new Resampler(48_000)
    const parts = [split.process(ramp.subarray(0, 128)), split.process(ramp.subarray(128, 500)), split.process(ramp.subarray(500))]
    const joined = Float32Array.from(parts.flatMap((part) => Array.from(part)))
    // A third of the input, give or take the one sample held back at the edge.
    expect(Math.abs(whole.length - 320)).toBeLessThanOrEqual(1)
    expect(joined.length).toBe(whole.length)
    for (let i = 0; i < whole.length; i += 1) expect(joined[i]).toBeCloseTo(whole[i], 6)
    // It is a ramp, so the output is a ramp with step 3/960.
    expect(whole[1] - whole[0]).toBeCloseTo(3 / 960, 6)
  })

  it('44.1 kHz keeps a steady ratio (a non-integer step)', () => {
    const resampler = new Resampler(44_100)
    let total = 0
    for (let i = 0; i < 100; i += 1) total += resampler.process(new Float32Array(128)).length
    expect(Math.abs(total - (128 * 100 * 16_000) / 44_100)).toBeLessThanOrEqual(2)
  })
})
