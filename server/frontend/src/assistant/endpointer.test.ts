import { describe, expect, it } from 'vitest'

import { Endpointer, LATE_LOUD_MS, MAX_UTTERANCE_MS, SILENCE_MS, WINDOW_MS } from './endpointer'
import { SseParser } from './sse'

const LOUD = new Int16Array(320).fill(3000)
const QUIET = new Int16Array(320)

function run(endpointer: Endpointer, chunk: Int16Array, ms: number) {
  let verdict = 'listening'
  for (let t = 0; t < ms && verdict === 'listening'; t += 20) verdict = endpointer.push(chunk)
  return verdict
}

describe('Endpointer', () => {
  it('ends after the silence window once speech has started', () => {
    const e = new Endpointer()
    expect(run(e, LOUD, 500)).toBe('listening')
    expect(run(e, QUIET, SILENCE_MS - 20)).toBe('listening')
    expect(e.push(QUIET)).toBe('end')
    expect(e.heardSpeech).toBe(true)
  })

  it('a click is not speech', () => {
    const e = new Endpointer()
    e.push(LOUD)
    expect(run(e, QUIET, SILENCE_MS + 100)).toBe('listening')
    expect(e.heardSpeech).toBe(false)
  })

  it('the follow-up window lapses with no speech', () => {
    expect(run(new Endpointer(), QUIET, WINDOW_MS + 40)).toBe('lapsed')
  })

  it('a late sound runs the window again, at most twice', () => {
    const e = new Endpointer()
    for (let i = 0; i < 3; i++) {
      expect(run(e, QUIET, WINDOW_MS - LATE_LOUD_MS + 200)).toBe('listening')
      e.push(LOUD)
      const verdict = run(e, QUIET, LATE_LOUD_MS)
      if (i < 2) expect(verdict).toBe('listening')
      else expect(verdict).toBe('lapsed')
    }
  })

  it('stops at the longest utterance, counted from speech', () => {
    const e = new Endpointer()
    run(e, QUIET, 3000)
    expect(run(e, LOUD, MAX_UTTERANCE_MS - 200)).toBe('listening')
    expect(run(e, LOUD, 400)).toBe('max')
  })
})

describe('SseParser', () => {
  it('parses events across chunk boundaries and drops a bad one', () => {
    const p = new SseParser()
    expect(p.feed('event: turn\ndata: {"turn"')).toEqual([])
    const out = p.feed(': 0}\n\nevent: bad\ndata: {nope\n\nevent: sentence\r\ndata: {"text":"Hi."}\r\n\r\n')
    expect(out).toEqual([
      { type: 'turn', data: { turn: 0 } },
      { type: 'sentence', data: { text: 'Hi.' } },
    ])
  })
})
