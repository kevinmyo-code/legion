import { describe, expect, it } from 'vitest'

import { TONE_RATE, closingTone } from './cascade-words'

/**
 * The closing chime is the phone's, note for note (Kevin, 2026-11-24: the demo's voice behavior is
 * the phone's and only the persona differs). It was a single 150 ms 880 Hz tone at half gain, which
 * matched the phone in neither pitch, length nor shape.
 *
 * These numbers are `app/.../service/ListenCue.kt`'s `CLOSE_*` constants and `EARCON_GAIN`. They are
 * written out here rather than read from the Kotlin because this project builds for a browser and
 * has no `@types/node`, so a test cannot open a file. What stops the two sides drifting is on the
 * engine, where reading Kotlin is already the habit:
 * `server/tests/test_assistant_wait_lines.py::test_the_closing_chime_is_the_phones`, which reads
 * BOTH `ListenCue.kt` and `cascade-words.ts` and fails if either moves alone.
 */
const CLOSE_HIGH_HZ = 740
const CLOSE_HIGH_SEC = 0.1
const CLOSE_GAP_SEC = 0.04
const CLOSE_LOW_HZ = 494
const CLOSE_LOW_SEC = 0.15
const EARCON_GAIN = 0.25

const HIGH = Math.trunc(CLOSE_HIGH_SEC * TONE_RATE)
const GAP = Math.trunc(CLOSE_GAP_SEC * TONE_RATE)

describe('the closing chime', () => {
  it('is a falling fifth, and the low note is the longer one', () => {
    expect(CLOSE_HIGH_HZ / CLOSE_LOW_HZ).toBeCloseTo(1.5, 2)
    expect(CLOSE_LOW_SEC).toBeGreaterThan(CLOSE_HIGH_SEC)
  })

  it('is about 290 ms', () => {
    const secs = CLOSE_HIGH_SEC + CLOSE_GAP_SEC + CLOSE_LOW_SEC
    expect(secs).toBeCloseTo(0.29, 5)
    expect(closingTone().length).toBe(Math.trunc(secs * TONE_RATE))
  })

  it('goes silent between the two notes, and both notes sound', () => {
    const tone = closingTone()
    expect(tone.slice(HIGH, HIGH + GAP).every((s) => s === 0)).toBe(true)
    expect(Math.max(...tone.slice(0, HIGH))).toBeGreaterThan(0)
    expect(Math.max(...tone.slice(HIGH + GAP))).toBeGreaterThan(0)
  })

  it('never clicks: every note starts and ends at silence', () => {
    const tone = closingTone()
    for (const edge of [0, HIGH - 1, HIGH + GAP, tone.length - 1]) {
      expect(Math.abs(tone[edge])).toBe(0)
    }
  })

  it('stays inside the phone s earcon gain', () => {
    const peak = Math.max(...Array.from(closingTone(), Math.abs))
    expect(peak).toBeLessThanOrEqual(Math.ceil(EARCON_GAIN * 0x7fff))
    // And it is not so quiet that the gain was dropped by accident.
    expect(peak).toBeGreaterThan(0.2 * 0x7fff)
  })
})
