import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

import { TONE_RATE, closingTone } from './cascade-words'

/**
 * The closing chime is the phone's (Kevin, 2026-11-24: the demo's voice behavior is the phone's and
 * only the persona differs). It was a single 150 ms 880 Hz tone at half gain, which matched the
 * phone in neither pitch, length nor shape. These tests read `ListenCue.kt` for the numbers, the way
 * the engine's wait-line test reads `FillerTiming.kt`, so neither side can move alone.
 */
const LISTEN_CUE = join(
  __dirname,
  '../../../../app/src/main/java/com/kevin/legion/service/ListenCue.kt',
)

function phoneConst(name: string): number {
  const kt = readFileSync(LISTEN_CUE, 'utf8')
  // `[0-9_.]` rather than `\d`: one less backslash to get wrong in a built pattern.
  const found = kt.match(new RegExp(`const val ${name} = ([0-9_.]+)`))
  if (!found) throw new Error(`${name} is gone from ListenCue.kt: the phone's chime moved`)
  return Number(found[1].replace(/_/g, ''))
}

describe('the closing chime', () => {
  it('is a falling fifth, F#5 then B4, from the phone', () => {
    expect(phoneConst('CLOSE_HIGH_HZ')).toBe(740)
    expect(phoneConst('CLOSE_LOW_HZ')).toBe(494)
    // A fifth down, and the low note is the longer one.
    expect(phoneConst('CLOSE_LOW_SEC')).toBeGreaterThan(phoneConst('CLOSE_HIGH_SEC'))
  })

  it('is as long as the phone says, about 290 ms', () => {
    const secs =
      phoneConst('CLOSE_HIGH_SEC') + phoneConst('CLOSE_GAP_SEC') + phoneConst('CLOSE_LOW_SEC')
    expect(secs).toBeCloseTo(0.29, 5)
    expect(closingTone().length).toBe(Math.trunc(secs * TONE_RATE))
  })

  it('goes silent between the two notes', () => {
    const tone = closingTone()
    const high = Math.trunc(phoneConst('CLOSE_HIGH_SEC') * TONE_RATE)
    const gap = Math.trunc(phoneConst('CLOSE_GAP_SEC') * TONE_RATE)
    const middle = tone.slice(high, high + gap)
    expect(middle.every((s) => s === 0)).toBe(true)
    // ... and both notes carry sound.
    expect(Math.max(...tone.slice(0, high))).toBeGreaterThan(0)
    expect(Math.max(...tone.slice(high + gap))).toBeGreaterThan(0)
  })

  it('never clicks: each note starts and ends at silence', () => {
    const tone = closingTone()
    const high = Math.trunc(phoneConst('CLOSE_HIGH_SEC') * TONE_RATE)
    const gap = Math.trunc(phoneConst('CLOSE_GAP_SEC') * TONE_RATE)
    for (const edge of [0, high - 1, high + gap, tone.length - 1]) {
      expect(Math.abs(tone[edge])).toBe(0)
    }
  })

  it('stays inside the phone s earcon gain', () => {
    // EARCON_GAIN is 0.25 on the phone, so no sample may pass a quarter of full scale.
    const peak = Math.max(...Array.from(closingTone(), Math.abs))
    expect(peak).toBeLessThanOrEqual(Math.ceil(0.25 * 0x7fff))
    expect(peak).toBeGreaterThan(0.2 * 0x7fff)
  })
})
