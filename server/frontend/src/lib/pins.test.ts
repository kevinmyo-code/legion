import { afterEach, describe, expect, test } from 'vitest'

import { MAX_PINS, PINS_KEY, parsePins, readPins, setPinned } from '@/lib/pins'

/**
 * Pins live in `localStorage` (ticket 10), and storage is the one thing here
 * that can throw, be full, be blocked or hold anything at all. Each rule below
 * is a way it has been seen to go wrong on a phone.
 */

afterEach(() => {
  window.localStorage.clear()
})

describe('reading', () => {
  test('nothing stored is no pins', () => {
    expect(readPins()).toEqual([])
  })

  test('a stored list comes back in order, without duplicates', () => {
    window.localStorage.setItem(PINS_KEY, JSON.stringify(['b', 'a', 'b']))
    expect(readPins()).toEqual(['b', 'a'])
  })

  test.each([
    ['not json', '{oops'],
    ['an object', '{"a":1}'],
    ['a number', '4'],
    ['null', 'null'],
  ])('%s is no pins, never an error', (_name, raw) => {
    expect(parsePins(raw)).toEqual([])
  })

  test('entries that are not ids are dropped, the rest kept', () => {
    expect(parsePins(JSON.stringify(['a', 3, null, '', 'b']))).toEqual(['a', 'b'])
  })

  test('storage that throws on read is no pins', () => {
    const real = Storage.prototype.getItem
    Storage.prototype.getItem = () => {
      throw new Error('blocked')
    }
    try {
      expect(readPins()).toEqual([])
    } finally {
      Storage.prototype.getItem = real
    }
  })
})

describe('writing', () => {
  test('pin and unpin persist under the versioned key', () => {
    expect(PINS_KEY).toBe('legion.pins.v1')
    expect(setPinned('a', true)).toBe('pinned')
    expect(setPinned('b', true)).toBe('pinned')
    expect(JSON.parse(window.localStorage.getItem(PINS_KEY)!)).toEqual(['a', 'b'])
    expect(setPinned('a', false)).toBe('unpinned')
    expect(readPins()).toEqual(['b'])
  })

  test('pinning the same list twice stores it once', () => {
    setPinned('a', true)
    setPinned('a', true)
    expect(readPins()).toEqual(['a'])
  })

  test(`a ${MAX_PINS + 1}th pin is refused and nothing changes`, () => {
    for (const id of ['a', 'b', 'c']) setPinned(id, true)
    expect(setPinned('d', true)).toBe('full')
    expect(readPins()).toEqual(['a', 'b', 'c'])
    // Unpinning is always allowed, and frees the place.
    expect(setPinned('a', false)).toBe('unpinned')
    expect(setPinned('d', true)).toBe('pinned')
  })

  test('a write the device refuses says unsaved and changes nothing', () => {
    setPinned('a', true)
    const real = Storage.prototype.setItem
    Storage.prototype.setItem = () => {
      throw new Error('quota')
    }
    try {
      expect(setPinned('b', true)).toBe('unsaved')
    } finally {
      Storage.prototype.setItem = real
    }
    expect(readPins()).toEqual(['a'])
  })
})
