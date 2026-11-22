import { describe, expect, test } from 'vitest'

import { markTargets, OrbRenderer } from '@/components/assistant/orb'

import { flightFrame, flightTravel, sideRestX, type FlightGeometry } from './orb-flight'

/**
 * The orb's flight as numbers (Kevin, 2026-11-22: one continuous flow from the middle of the page
 * to its side place). The browser check measures it on screen; these pin the path:
 * it starts exactly on the stage's orb, ends exactly at its rest place, and never jumps between.
 */

// A 1440 x 900 desk at 16 px to the rem: the stage orb 240 px wide, its centre 300 px down.
const DESK: FlightGeometry = { stageX: 720, stageY: 300, size: 240, restX: 1440 - 128, restY: 450 - 40, restSize: 160, travel: 500 }

describe('the flight', () => {
  test('at the top it sits exactly on the stage orb, at its size', () => {
    expect(flightFrame(DESK, 0)).toEqual({ x: 720, y: 300, scale: 1, progress: 0 })
  })

  test('once the stage has scrolled away it is at rest, 10rem wide', () => {
    for (const y of [DESK.travel, DESK.travel + 1, 5000]) {
      const f = flightFrame(DESK, y)
      expect(f.x).toBeCloseTo(DESK.restX)
      expect(f.y).toBeCloseTo(DESK.restY)
      expect(f.scale * DESK.size).toBeCloseTo(160)
      expect(f.progress).toBe(1)
    }
  })

  test('one continuous path: no step between one pixel of scroll and the next', () => {
    let last = flightFrame(DESK, 0)
    for (let y = 1; y <= 600; y++) {
      const f = flightFrame(DESK, y)
      expect(Math.hypot(f.x - last.x, f.y - last.y)).toBeLessThan(25)
      expect(Math.abs(f.scale - last.scale)).toBeLessThan(0.02)
      last = f
    }
  })

  test('it lands as the stage leaves the upper part of the screen, never in under 360 px', () => {
    expect(flightTravel(1000, 900)).toBe(640)
    expect(flightTravel(400, 900)).toBe(360)
    expect(flightFrame(DESK, 250).progress).toBeCloseTo(0.5)
  })

  test("the side rest place is the middle of the 12rem column each section keeps", () => {
    expect(sideRestX('right', 1440, 16)).toBe(1440 - 14 * 16 + 6 * 16)
    expect(sideRestX('left', 1440, 16)).toBe(1.5 * 16 + 6 * 16)
  })
})

describe('the mark (the Q at its section)', () => {
  // An invented 8 x 8 mark: a 4 x 2 bar.
  const size = 8
  const alpha = new Uint8Array(size * size)
  for (let y = 3; y < 5; y++) for (let x = 2; x < 6; x++) alpha[y * size + x] = 255

  test('every point gets a target inside the mark, centred, the long side spanning -1..1', () => {
    const targets = markTargets(alpha, size, 300)
    expect(targets).toHaveLength(600)
    for (let i = 0; i < 300; i++) {
      expect(Math.abs(targets[i * 2])).toBeLessThanOrEqual(1)
      expect(Math.abs(targets[i * 2 + 1])).toBeLessThanOrEqual(0.5)
    }
  })

  test('the same targets on every load', () => {
    expect(markTargets(alpha, size, 50)).toEqual(markTargets(alpha, size, 50))
  })

  test('nothing to draw with: a ring, never nothing', () => {
    const ring = markTargets(new Uint8Array(size * size), size, 20)
    for (let i = 0; i < 20; i++) expect(Math.hypot(ring[i * 2], ring[i * 2 + 1])).toBeCloseTo(0.8)
  })

  test('the orb gathers into the mark in about a second and scatters back, motion or not', () => {
    for (const still of [false, true]) {
      const orb = new OrbRenderer(100)
      orb.setMark(markTargets(alpha, size, orb.size()))
      for (let i = 0; i < 60; i++) orb.step(1 / 60, 0, still)
      expect(orb.morphed()).toBeGreaterThan(0.95)
      orb.setMark(null)
      for (let i = 0; i < 60; i++) orb.step(1 / 60, 0, still)
      expect(orb.morphed()).toBeLessThan(0.05)
    }
  })
})
