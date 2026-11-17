import { describe, expect, it } from 'vitest'

import { BASE_MS, CAP_MS, EARLY_MS, EndOfTurnPolicy, GATE_MS, WINDOW_MS, type Action } from './eot-policy'

// The phone's EndOfTurnPolicyTest.kt, ported case for case, plus the web's speculation point.
const speech = 0.9
const quiet = 0.05

function speaking(smart = true): EndOfTurnPolicy {
  const p = new EndOfTurnPolicy(smart)
  for (let i = 0; i < 20; i++) p.onWindow(speech)
  expect(p.inActivity).toBe(true)
  return p
}

function actions(p: EndOfTurnPolicy, ms: number): Action[] {
  const out: Action[] = []
  for (let t = 0; t < ms; t += WINDOW_MS) out.push(...p.onWindow(quiet))
  return out
}

const of = <T extends Action['type']>(xs: Action[], type: T) => xs.filter((a) => a.type === type) as Extract<Action, { type: T }>[]

describe('EndOfTurnPolicy (the phone policy)', () => {
  it('speech starts after the 200 ms confirm, not before', () => {
    const p = new EndOfTurnPolicy(true)
    const got = Array.from({ length: 7 }, () => p.onWindow(speech))
    expect(got.slice(0, 6).every((a) => a.length === 0)).toBe(true)
    expect(got[6]).toEqual([{ type: 'start', onsetWindows: 7 }])
  })

  it('a blip shorter than the confirm never starts a turn', () => {
    const p = new EndOfTurnPolicy(true)
    for (let i = 0; i < 4; i++) expect(p.onWindow(speech)).toEqual([])
    expect(p.onWindow(quiet)).toEqual([])
    expect(p.inActivity).toBe(false)
  })

  it('one check starts at the 192 ms gate', () => {
    const checks = of(actions(speaking(), 300), 'check')
    expect(checks).toHaveLength(1)
    expect(checks[0].silenceMs).toBe(GATE_MS)
  })

  it('no verdict before 700 ms ends the turn at the base', () => {
    const end = of(actions(speaking(), 800), 'end')
    expect(end).toHaveLength(1)
    expect(end[0].reason).toBe('base')
    expect(end[0].silenceMs).toBe(704)
  })

  it('a done verdict before the base still waits for the base', () => {
    const p = speaking()
    const check = of(actions(p, 320), 'check')[0]
    expect(p.onCheckResult(check.checkId, 0.9)).toEqual([])
    const end = of(actions(p, 500), 'end')[0]
    expect(end.reason).toBe('base')
    expect(end.silenceMs).toBe(704)
  })

  it('not done before the base holds, re-checks one at a time, ends on the first done', () => {
    const p = speaking()
    const first = of(actions(p, 400), 'check')[0]
    const second = p.onCheckResult(first.checkId, 0.2)[0] as Extract<Action, { type: 'check' }>
    const during = actions(p, 600)
    expect(during.some((a) => a.type === 'end' || a.type === 'check')).toBe(false)
    const third = p.onCheckResult(second.checkId, 0.3)[0] as Extract<Action, { type: 'check' }>
    actions(p, 200)
    const end = p.onCheckResult(third.checkId, 0.8)[0] as Extract<Action, { type: 'end' }>
    expect(end.reason).toBe('extended')
    expect(end.scores.map((s) => s.score)).toEqual([0.2, 0.3, 0.8])
    expect(end.silenceMs).toBeGreaterThan(BASE_MS)
  })

  it('a held turn ends at the cap', () => {
    const p = speaking()
    const first = of(actions(p, 400), 'check')[0]
    let id = (p.onCheckResult(first.checkId, 0.1)[0] as Extract<Action, { type: 'check' }>).checkId
    let end: Extract<Action, { type: 'end' }> | undefined
    for (let n = 1; !end && n < 200; n++) {
      end = of(p.onWindow(quiet), 'end')[0]
      if (!end && n % 10 === 0) id = (p.onCheckResult(id, 0.1)[0] as Extract<Action, { type: 'check' }>).checkId
    }
    expect(end?.reason).toBe('cap')
    expect(end!.silenceMs).toBeGreaterThanOrEqual(CAP_MS)
  })

  it('a first verdict after the base is too late to extend', () => {
    const p = speaking()
    const got = actions(p, 800)
    const check = of(got, 'check')[0]
    expect(of(got, 'end')[0].reason).toBe('base')
    expect(p.onCheckResult(check.checkId, 0.1)).toEqual([])
    expect(p.inActivity).toBe(false)
  })

  it('speech resuming cancels a pending check and a hold', () => {
    const p = speaking()
    const check = of(actions(p, 400), 'check')[0]
    const next = p.onCheckResult(check.checkId, 0.1)[0] as Extract<Action, { type: 'check' }>
    expect(p.onWindow(speech)[0].type).toBe('resumed')
    expect(p.onCheckResult(next.checkId, 0.9)).toEqual([])
    const got = actions(p, 800)
    expect(of(got, 'check')).toHaveLength(1)
    expect(of(got, 'end')[0].reason).toBe('base')
  })

  it('quiet but not silent windows do not resume speech', () => {
    const p = speaking()
    actions(p, 300)
    expect(p.onWindow(0.4).some((a) => a.type === 'resumed')).toBe(false)
  })

  it('without smart turn it is silence only, reason fallback', () => {
    const got = actions(speaking(false), 800)
    expect(of(got, 'check')).toHaveLength(0)
    expect(of(got, 'end')[0].reason).toBe('fallback')
  })

  it('a check that throws while held ends the turn on silence', () => {
    const p = speaking()
    const check = of(actions(p, 400), 'check')[0]
    const next = p.onCheckResult(check.checkId, 0.1)[0] as Extract<Action, { type: 'check' }>
    actions(p, 500)
    expect((p.onCheckFailed(next.checkId)[0] as Extract<Action, { type: 'end' }>).reason).toBe('fallback')
  })

  it('barge-in starts the activity at once', () => {
    const p = new EndOfTurnPolicy(true)
    expect(p.forceStart()).toEqual({ type: 'start', onsetWindows: 0 })
    expect(p.inActivity).toBe(true)
  })

  it('a turn held once and then ended at the base reports extended (A25, 2026-10-27)', () => {
    const p = speaking()
    let id = of(actions(p, 192), 'check')[0].checkId
    for (let n = 1; n <= 49; n++) {
      p.onWindow(quiet)
      if (n % 3 === 0) id = (p.onCheckResult(id, 0.007)[0] as Extract<Action, { type: 'check' }>).checkId
    }
    expect(p.onWindow(speech)[0].type).toBe('resumed')
    const second = of(actions(p, 192), 'check')[0]
    const third = p.onCheckResult(second.checkId, 0.099)[0] as Extract<Action, { type: 'check' }>
    expect(p.onCheckResult(third.checkId, 0.578)).toEqual([])
    const end = of(actions(p, 600), 'end')[0]
    expect(end.reason).toBe('extended')
    expect(end.silenceMs).toBe(704)
    expect(end.longestSilenceMs).toBeGreaterThanOrEqual(1_700)
  })

  it('a turn never held ends as base with its own silence as the longest', () => {
    const end = of(actions(speaking(), 800), 'end')[0]
    expect(end.reason).toBe('base')
    expect(end.longestSilenceMs).toBe(704)
  })

  it('says once per silence when it is early enough to start transcribing', () => {
    const p = speaking()
    const early = of(actions(p, 400), 'early')
    expect(early).toHaveLength(1)
    expect(early[0].silenceMs).toBeGreaterThanOrEqual(EARLY_MS)
    expect(p.onWindow(speech)[0].type).toBe('resumed')
    expect(of(actions(p, 400), 'early')).toHaveLength(1)
  })
})
