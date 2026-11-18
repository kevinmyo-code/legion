import { describe, expect, it, vi } from 'vitest'

import {
  BargeInGate,
  DEFAULT_ECHO_FLOOR,
  ECHO_MARGIN,
  LEARN_WINDOWS,
  MIN_LEVEL,
  SPAN_WINDOWS,
  SUSTAIN_MS,
  SUSTAIN_WINDOWS,
  VERDICT_MS,
  WINDOW_MS,
  peakLevel,
  type GateEvent,
} from './barge-in-gate'

// Fails fast, with its name, if anything hangs.
vi.setConfig({ testTimeout: 10_000 })

// The phone's BargeInGateTest.kt, ported case for case (A25 numbers kept).
const windows = SUSTAIN_WINDOWS
const loud = 25_000
const echo = 10_000

function gate() {
  const g = new BargeInGate()
  g.onReplyStart()
  return g
}

function feed(g: BargeInGate, n: number, prob: number, level: number, playing = true): GateEvent[] {
  const out: GateEvent[] = []
  for (let i = 0; i < n; i++) {
    const e = g.onWindow(prob, level, playing)
    if (e) out.push(e)
  }
  return out
}

const fires = (es: GateEvent[]) => es.filter((e) => e.type === 'fire') as Extract<GateEvent, { type: 'fire' }>[]
const verdicts = (es: GateEvent[]) => es.filter((e) => e.type === 'verdict') as Extract<GateEvent, { type: 'verdict' }>[]

describe('BargeInGate (the phone gate)', () => {
  it('sustained loud speech over the reply fires', () => {
    const fire = fires(feed(gate(), windows, 0.95, loud))
    expect(fire).toHaveLength(1)
    expect(fire[0].sustainMs).toBe(SUSTAIN_MS)
    expect(fire[0].level).toBe(loud)
  })

  it('a confident mm-hm of 192 ms followed by silence never fires', () => {
    const g = gate()
    expect(feed(g, 6, 1.0, loud)).toEqual([])
    expect(feed(g, 60, 0.1, 500)).toEqual([])
    expect(g.onReplyEnd()!.bestSpanWindows).toBe(6)
  })

  it('two backchannels further apart than the span never add up', () => {
    const g = gate()
    expect(feed(g, 6, 1.0, loud)).toEqual([])
    expect(feed(g, SPAN_WINDOWS - 6, 0.1, 500)).toEqual([])
    expect(feed(g, 6, 1.0, loud)).toEqual([])
    expect(g.onReplyEnd()!.bestSpanWindows).toBe(6)
  })

  it('confident speech at echo level never fires', () => {
    expect(feed(gate(), 40, 0.99, echo)).toEqual([])
  })

  it('the echo floor is learned from the reply', () => {
    const g = gate()
    expect(g.echoFloor()).toBe(DEFAULT_ECHO_FLOOR)
    feed(g, 20, 0.3, 4_000)
    expect(g.echoFloor()).toBe(4_000)
    expect(fires(feed(g, windows, 0.95, 7_000))).toHaveLength(1)
  })

  it('eight qualifying windows as 4, gap 2, 4 within the span fire', () => {
    const g = gate()
    expect(feed(g, 4, 0.95, loud)).toEqual([])
    expect(feed(g, 2, 0.5, 2_000)).toEqual([])
    expect(feed(g, 3, 0.95, loud)).toEqual([])
    expect(fires(feed(g, 1, 0.95, loud))[0].sustainMs).toBe(SUSTAIN_MS)
  })

  it('seven qualifying windows in any span never fire', () => {
    const g = gate()
    for (let i = 0; i < 5; i++) {
      expect(feed(g, 7, 0.95, loud)).toEqual([])
      expect(feed(g, SPAN_WINDOWS - 7, 0.1, 500)).toEqual([])
    }
    const s = g.onReplyEnd()!
    expect(s.fired).toBe(false)
    expect(s.bestSpanWindows).toBe(7)
  })

  it('run 5: a jittery stop fires on the first burst that reaches 8 in a span', () => {
    const g = gate()
    feed(g, 20, 0.1, 500)
    expect(g.threshold()).toBe(MIN_LEVEL)
    expect(feed(g, 7, 1.0, 30_560)).toEqual([])
    expect(feed(g, 2, 0.6, 9_000)).toEqual([])
    const fire = fires(feed(g, 1, 1.0, 30_560))[0]
    expect(fire.sustainMs).toBe(SUSTAIN_MS)
    expect(fire.level).toBe(30_560)
    const s = g.onReplyEnd()!
    expect(s.fired).toBe(true)
    expect(s.longestRunMs).toBe(7 * WINDOW_MS)
    expect(s.bestSpanWindows).toBe(8)
  })

  it('nothing fires while the reply is not playing', () => {
    expect(feed(gate(), 20, 0.99, loud, false)).toEqual([])
  })

  it('no speech after a fire is a self-interrupt, two turn barge-in off', () => {
    const g = gate()
    feed(g, windows, 0.95, loud)
    const v = verdicts(feed(g, 60, 0.05, 300))[0]
    expect(v.selfInterrupt).toBe(true)
    expect(v.nowDisabled).toBe(false)
    g.onReplyStart()
    feed(g, windows, 0.95, loud)
    expect(verdicts(feed(g, 60, 0.05, 300))[0].nowDisabled).toBe(true)
    expect(g.disabled).toBe(true)
    g.onReplyStart()
    expect(feed(g, 20, 0.99, loud)).toEqual([])
  })

  it('speech continuing after a fire is a real interrupt', () => {
    const g = gate()
    feed(g, windows, 0.95, loud)
    expect(verdicts(feed(g, 60, 0.9, 20_000))[0].selfInterrupt).toBe(false)
    expect(g.selfInterrupts).toBe(0)
  })

  it('one fire per reply', () => {
    const g = gate()
    feed(g, windows, 0.95, loud)
    feed(g, 60, 0.9, 20_000)
    expect(g.onWindow(0.99, loud, true)).toBeNull()
  })

  it('a short loud stop against a quiet echo floor is not a self-interrupt', () => {
    const g = gate()
    feed(g, 20, 0.1, 0)
    expect(g.threshold()).toBe(MIN_LEVEL)
    expect(fires(feed(g, windows, 1.0, 22_914))).toHaveLength(1)
    const v = verdicts([...feed(g, 4, 0.9, 15_000), ...feed(g, 60, 0.05, 200)])[0]
    expect(v.speechMsAfter).toBe(128)
    expect(v.selfInterrupt).toBe(false)
  })

  it('a fire just over the threshold with nothing after is still a self-interrupt', () => {
    const g = gate()
    feed(g, 20, 0.1, 8_000)
    expect(fires(feed(g, windows, 0.95, 13_000))).toHaveLength(1)
    expect(verdicts(feed(g, 60, 0.05, 200))[0].selfInterrupt).toBe(true)
  })

  it('stats: a quiet voice is speech but never loud enough', () => {
    const g = gate()
    expect(feed(g, 20, 0.95, 5_000)).toEqual([])
    const s = g.onReplyEnd()!
    expect([s.windows, s.speechWindows, s.loudWindows, s.qualifyingWindows]).toEqual([20, 20, 0, 0])
    expect(s.echoFloor).toBe(5_000)
    expect(s.threshold).toBe(7_500)
  })

  it('stats: a reply the gate never heard has zero windows', () => {
    const g = gate()
    feed(g, 20, 0.99, loud, false)
    const s = g.onReplyEnd()!
    expect(s.windows).toBe(0)
    expect(s.threshold).toBe(Math.trunc(DEFAULT_ECHO_FLOOR * ECHO_MARGIN))
  })

  it('run 6: the reply after a barge-in does not learn the user still talking', () => {
    const g = gate()
    feed(g, 20, 0.1, 500)
    expect(fires(feed(g, windows, 0.99, 29_738))[0].threshold).toBe(MIN_LEVEL)
    expect(verdicts(feed(g, 60, 0.9, 20_000))[0].selfInterrupt).toBe(false)
    g.onReplyEnd()
    g.onReplyStart()
    expect(g.echoFloor()).toBe(500)
    expect(feed(g, 8, 0.7, 9_785)).toEqual([])
    expect(feed(g, 12, 0.1, 500)).toEqual([])
    expect(g.echoFloor()).toBe(500)
    expect(fires(feed(g, windows, 0.95, 13_000))[0].threshold).toBe(MIN_LEVEL)
  })

  it('run 6 contrast: without the guard the floor learns the user', () => {
    const g = gate()
    feed(g, 8, 0.7, 9_785)
    feed(g, 12, 0.1, 500)
    expect(g.echoFloor()).toBe(9_785)
    expect(g.threshold()).toBe(14_677)
    expect(feed(g, 19, 0.95, 13_000)).toEqual([])
    expect(g.onReplyEnd()!.threshold).toBe(19_500)
  })

  it("the guard after the user's turn ends runs one second of windows, played or not", () => {
    const g = new BargeInGate()
    g.onUserTurnEnd()
    feed(g, 20, 0.1, 300, false)
    g.onReplyStart()
    feed(g, 10, 0.7, 9_785)
    expect(g.echoFloor()).toBe(DEFAULT_ECHO_FLOOR)
    feed(g, 10, 0.1, 500)
    expect(g.echoFloor()).toBe(500)
    feed(g, 10, 0.7, 9_785)
    expect(g.echoFloor()).toBe(9_785)
  })

  it("a reply starts from the last reply's learned floor; a short reply keeps it", () => {
    const g = gate()
    feed(g, 20, 0.1, 500)
    g.onReplyEnd()
    g.onReplyStart()
    expect(g.echoFloor()).toBe(500)
    feed(g, LEARN_WINDOWS - 1, 0.1, 8_000)
    g.onReplyEnd()
    g.onReplyStart()
    expect(g.echoFloor()).toBe(500)
  })

  it('no reply open means no stats', () => {
    expect(new BargeInGate().onReplyEnd()).toBeNull()
  })

  it("the web's own count: two self-interrupts it found switch the conversation to half-duplex", () => {
    // One per barge-in, and a reply has at most one: the second is over the next reply.
    const g = gate()
    expect(g.countSelfInterrupt()).toBe(false)
    g.onReplyStart()
    expect(g.countSelfInterrupt()).toBe(true)
    g.onReplyStart()
    expect(g.armedNow).toBe(false)
  })

  it('peak level is the largest absolute sample, as on the phone', () => {
    expect(peakLevel(new Int16Array([100, -2500, 300]))).toBe(2500)
    expect(peakLevel(new Float32Array([0.5, -0.25]))).toBe(16384)
  })
})

describe('one barge-in counts once (review 2026-11-17)', () => {
  const VERDICT_WINDOWS = Math.ceil(VERDICT_MS / WINDOW_MS) + 1

  it("the page counting it first, then the gate's verdict: one self-interrupt", () => {
    const g = gate()
    expect(fires(feed(g, windows, 0.95, loud))).toHaveLength(1)
    expect(g.countSelfInterrupt()).toBe(false)
    const v = verdicts(feed(g, VERDICT_WINDOWS, 0.05, 500))
    expect(v).toHaveLength(1)
    expect(g.selfInterrupts).toBe(1)
    expect(g.disabled).toBe(false)
  })

  it("the gate's verdict first, then the page: one self-interrupt", () => {
    const g = gate()
    feed(g, windows, 0.95, loud)
    expect(verdicts(feed(g, VERDICT_WINDOWS, 0.05, 500))[0].selfInterrupt).toBe(true)
    expect(g.countSelfInterrupt()).toBe(false)
    expect(g.selfInterrupts).toBe(1)
  })

  it('a new conversation turns barge-in back on', () => {
    const g = gate()
    g.disable()
    g.newConversation()
    g.onReplyStart()
    expect(g.disabled).toBe(false)
    expect(g.selfInterrupts).toBe(0)
    expect(fires(feed(g, windows, 0.95, loud))).toHaveLength(1)
  })
})
