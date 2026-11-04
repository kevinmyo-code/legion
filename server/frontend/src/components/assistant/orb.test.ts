import { describe, expect, test } from 'vitest'

import type { AssistantState } from '@/assistant/live-client'
import { loudness } from '@/assistant/web-audio'

import { OrbRenderer, orbMode, parseColour } from './orb'

const base: AssistantState = {
  phase: 'idle',
  mic: 'off',
  activity: 'idle',
  lines: [],
  companion: null,
  notice: null,
  ended: null,
}

describe('orbMode', () => {
  test('each conversation state shows as one mode', () => {
    expect(orbMode(base)).toBe('idle')
    expect(orbMode({ ...base, mic: 'starting' })).toBe('connecting')
    expect(orbMode({ ...base, phase: 'connecting', mic: 'on' })).toBe('connecting')
    expect(orbMode({ ...base, phase: 'live', mic: 'on', activity: 'idle' })).toBe('listening')
    expect(orbMode({ ...base, phase: 'live', mic: 'on', activity: 'listening' })).toBe('listening')
    expect(orbMode({ ...base, phase: 'live', mic: 'on', activity: 'thinking' })).toBe('thinking')
    expect(orbMode({ ...base, phase: 'live', mic: 'on', activity: 'speaking' })).toBe('speaking')
    // A typed question being checked still shows thinking.
    expect(orbMode({ ...base, phase: 'live', activity: 'thinking' })).toBe('thinking')
  })

  test('an ended conversation and a blocked mic rest', () => {
    expect(orbMode({ ...base, phase: 'ended', ended: 'Over.' })).toBe('resting')
    expect(orbMode({ ...base, notice: { kind: 'mic-denied', text: 'Blocked.' } })).toBe('resting')
  })
})

describe('OrbRenderer', () => {
  test('a change of mode is eased, not cut', () => {
    const orb = new OrbRenderer(10)
    const spinIdle = orb.params()[0]
    orb.setMode('thinking')
    orb.step(0.016, 0, false)
    const after = orb.params()[0]
    expect(after).toBeGreaterThan(spinIdle)
    expect(after).toBeLessThan(0.85)
    for (let i = 0; i < 200; i++) orb.step(0.05, 0, false)
    expect(orb.params()[0]).toBeCloseTo(0.85, 3)
  })

  test('reduced motion still reaches the new mode', () => {
    const orb = new OrbRenderer(10)
    orb.setMode('speaking')
    for (let i = 0; i < 200; i++) orb.step(0.05, 1, true)
    expect(orb.params()[2]).toBeCloseTo(1, 3)
  })
})

describe('loudness', () => {
  test('silence is 0, a loud signal is 1, speech sits between', () => {
    expect(loudness([])).toBe(0)
    expect(loudness(new Float32Array(512))).toBe(0)
    expect(loudness(new Float32Array(512).fill(0.5))).toBe(1)
    const speech = loudness(new Float32Array(512).fill(0.03))
    expect(speech).toBeGreaterThan(0.2)
    expect(speech).toBeLessThan(0.9)
  })
})

describe('parseColour', () => {
  test('reads hex and computed rgb', () => {
    expect(parseColour('#8a2d1e')).toEqual([138, 45, 30])
    expect(parseColour(' rgb(29, 26, 22)')).toEqual([29, 26, 22])
    expect(parseColour('')).toBeNull()
  })
})
