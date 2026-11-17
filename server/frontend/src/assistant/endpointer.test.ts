import { describe, expect, it } from 'vitest'

import { Endpointer, MAX_UTTERANCE_MS, NO_SPEECH_MS, SILENCE_MS } from './endpointer'
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

  it('gives up when nothing is said', () => {
    expect(run(new Endpointer(), QUIET, NO_SPEECH_MS + 40)).toBe('nothing')
  })

  it('stops at the longest utterance', () => {
    expect(run(new Endpointer(), LOUD, MAX_UTTERANCE_MS + 40)).toBe('max')
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
