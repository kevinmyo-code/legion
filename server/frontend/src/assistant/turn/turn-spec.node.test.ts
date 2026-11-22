// @vitest-environment node
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { describe, expect, it } from 'vitest'

import * as gate from './barge-in-gate'
import * as detector from './detector'
import * as eot from './eot-policy'
import * as cascade from '../cascade-client'

/**
 * One voice agent (Kevin, 2026-11-22: "all my voice agents should be the same"): the web's turn
 * numbers against `shared/turn/spec.json`, and every case in `shared/turn/cases.json` through the
 * web's ports. The phone's `SharedTurnSpecTest.kt` reads the same two files, so a number or a rule
 * that moves on one client and not the other fails a build. Read at run time, not imported: the
 * Docker web build sees only `server/frontend/`.
 */

function shared(name: string): string {
  let dir = dirname(fileURLToPath(import.meta.url))
  for (;;) {
    const candidate = join(dir, 'shared', 'turn', name)
    if (existsSync(candidate)) return readFileSync(candidate, 'utf-8')
    const up = dirname(dir)
    if (up === dir) throw new Error(`shared/turn/${name} not found`)
    dir = up
  }
}

const spec = JSON.parse(shared('spec.json'))
const cases = JSON.parse(shared('cases.json'))

describe('shared/turn/spec.json: the web keeps the phone numbers', () => {
  it('end of turn', () => {
    const e = spec.end_of_turn
    expect({
      window_ms: eot.WINDOW_MS,
      start_prob: eot.START_PROB,
      end_prob: eot.END_PROB,
      confirm_ms: eot.CONFIRM_MS,
      gate_ms: eot.GATE_MS,
      base_ms: eot.BASE_MS,
      finished_ms: eot.FINISHED_MS,
      cap_ms: eot.CAP_MS,
      threshold: eot.THRESHOLD,
      early_threshold: eot.EARLY_THRESHOLD,
      early_ms: eot.EARLY_MS,
    }).toEqual(e)
  })

  it('barge-in', () => {
    expect({
      window_ms: gate.WINDOW_MS,
      prob: gate.PROB,
      speech_prob: gate.SPEECH_PROB,
      sustain_ms: gate.SUSTAIN_MS,
      span_windows: gate.SPAN_WINDOWS,
      echo_margin: gate.ECHO_MARGIN,
      echo_percentile: gate.ECHO_PERCENTILE,
      default_echo_floor: gate.DEFAULT_ECHO_FLOOR,
      min_level: gate.MIN_LEVEL,
      learn_windows: gate.LEARN_WINDOWS,
      user_guard_ms: gate.USER_GUARD_MS,
      verdict_ms: gate.VERDICT_MS,
      self_speech_ms: gate.SELF_SPEECH_MS,
      max_self_interrupts: gate.MAX_SELF_INTERRUPTS,
      clearly_user_factor: gate.CLEARLY_USER_FACTOR,
      warmup_ms: gate.WARMUP_MS,
      warmup_windows: gate.WARMUP_WINDOWS,
      pre_roll_windows: detector.PREROLL_WINDOWS,
    }).toEqual(spec.barge_in)
  })

  it('the web-only numbers', () => {
    const w = spec.web_only
    expect(eot.SPECULATE_MS).toBe(w.speculate_ms)
    expect(cascade.ECHO_TAIL_MS).toBe(w.echo_tail_ms)
    expect(detector.MAX_SILERO_MS).toBe(w.max_silero_ms)
    expect(detector.MAX_SMART_MS).toBe(w.max_smart_ms)
  })
})

type PolicyStep = { speech?: number; quiet?: number; prob?: number; n?: number; score?: number; fail?: boolean }
type GateStep = { reply?: 'start' | 'end'; warm_up?: boolean; user_turn_end?: boolean; prob?: number; level?: number; n?: number; playing?: boolean }

function runPolicy(c: { config: { smart: boolean; finished: boolean; early_end: boolean }; steps: PolicyStep[] }): string[] {
  const p = new eot.EndOfTurnPolicy(c.config.smart, eot.BASE_MS, c.config.finished ? eot.FINISHED_MS : eot.BASE_MS, c.config.early_end)
  const out: string[] = []
  let lastCheck = 0
  const take = (actions: eot.Action[]) => {
    for (const a of actions) {
      if (a.type === 'start') out.push('start')
      else if (a.type === 'check') {
        lastCheck = a.checkId
        out.push(`check@${a.silenceMs}`)
      } else if (a.type === 'resumed') out.push('resumed')
      else if (a.type === 'end') out.push(`end:${a.reason}@${a.silenceMs}`)
    }
  }
  for (const s of c.steps) {
    if (s.speech !== undefined) for (let i = 0; i < s.speech; i++) take(p.onWindow(0.9))
    else if (s.quiet !== undefined) for (let i = 0; i < s.quiet; i++) take(p.onWindow(0.05))
    else if (s.prob !== undefined) for (let i = 0; i < (s.n ?? 1); i++) take(p.onWindow(s.prob))
    else if (s.score !== undefined) take(p.onCheckResult(lastCheck, s.score))
    else if (s.fail) take(p.onCheckFailed(lastCheck))
  }
  return out
}

function runGate(c: { steps: GateStep[] }): string[] {
  const g = new gate.BargeInGate()
  const out: string[] = []
  for (const s of c.steps) {
    if (s.reply === 'start') g.onReplyStart()
    else if (s.reply === 'end') g.onReplyEnd()
    else if (s.warm_up) g.startWarmUp()
    else if (s.user_turn_end) g.onUserTurnEnd()
    else {
      for (let i = 0; i < (s.n ?? 1); i++) {
        const e = g.onWindow(s.prob!, s.level!, s.playing ?? true)
        if (!e) continue
        if (e.type === 'fire') out.push(`fire level=${e.level} threshold=${e.threshold}`)
        else if (e.type === 'verdict') out.push(`verdict self=${e.selfInterrupt} count=${e.selfInterrupts} disabled=${e.nowDisabled}`)
        else out.push(`ignored ${e.reason}`)
      }
    }
  }
  return out
}

describe('shared/turn/cases.json: end of turn', () => {
  for (const c of cases.policy) {
    it(c.name, () => expect(runPolicy(c)).toEqual(c.expect))
  }
})

describe('shared/turn/cases.json: barge-in', () => {
  for (const c of cases.gate) {
    it(c.name, () => expect(runGate(c)).toEqual(c.expect))
  }
})
