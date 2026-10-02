import { expect, test } from 'vitest'

import { canvasLine, canvasMetaOf, type CanvasMeta } from '@/lib/canvas'

const NOW = Date.parse('2026-09-28T12:00:00Z')

function meta(overrides: Partial<CanvasMeta> = {}): CanvasMeta {
  return {
    canvas_assignment_id: 123,
    canvas_submitted: false,
    submission_state: 'unsubmitted',
    submitted_at: null,
    missing: false,
    late: false,
    excused: false,
    score: null,
    grade: null,
    points_possible: null,
    read_at: new Date(NOW).toISOString(),
    ...overrides,
  }
}

// Ticket 11's table, row for row.

test('not done, Canvas submitted: "Canvas says submitted" (the useful one)', () => {
  expect(canvasLine(false, meta({ canvas_submitted: true }), NOW)).toBe('Canvas says submitted')
})

test('done, Canvas submitted: "Canvas: submitted"', () => {
  expect(canvasLine(true, meta({ canvas_submitted: true }), NOW)).toBe('Canvas: submitted')
})

test('done, not submitted, not excused: "Canvas: not submitted" (a mismatch worth seeing)', () => {
  expect(canvasLine(true, meta(), NOW)).toBe('Canvas: not submitted')
})

test('missing:true wins regardless of done', () => {
  expect(canvasLine(false, meta({ missing: true }), NOW)).toBe('Canvas: marked missing')
  expect(canvasLine(true, meta({ missing: true }), NOW)).toBe('Canvas: marked missing')
})

test('excused:true wins regardless of done', () => {
  expect(canvasLine(false, meta({ excused: true }), NOW)).toBe('Canvas: excused')
  expect(canvasLine(true, meta({ excused: true }), NOW)).toBe('Canvas: excused')
})

test('not done, not submitted: nothing', () => {
  expect(canvasLine(false, meta(), NOW)).toBe('')
})

test('excused beats missing when Canvas sets both', () => {
  expect(canvasLine(false, meta({ missing: true, excused: true }), NOW)).toBe('Canvas: excused')
})

// The grade suffix.

test('a grade follows the submitted line: score/points_possible', () => {
  expect(
    canvasLine(true, meta({ canvas_submitted: true, score: 95, points_possible: 100 }), NOW),
  ).toBe('Canvas: submitted, 95/100')
})

test('a letter grade is used when there is no numeric score', () => {
  expect(canvasLine(true, meta({ canvas_submitted: true, grade: 'A' }), NOW)).toBe(
    'Canvas: submitted, A',
  )
})

test('no grade at all: no suffix', () => {
  expect(canvasLine(true, meta({ canvas_submitted: true }), NOW)).toBe('Canvas: submitted')
})

// The age suffix - read_at older than the canvas freshness threshold (2h).

test('read_at within the freshness threshold: no age said', () => {
  const readAt = NOW - 30 * 60 * 1000 // 30 minutes ago
  expect(canvasLine(true, meta({ canvas_submitted: true, read_at: new Date(readAt).toISOString() }), NOW)).toBe(
    'Canvas: submitted',
  )
})

test('read_at past the threshold: "as of <relative>" is appended', () => {
  const readAt = NOW - 3 * 60 * 60 * 1000 // 3 hours ago
  expect(
    canvasLine(true, meta({ canvas_submitted: true, read_at: new Date(readAt).toISOString() }), NOW),
  ).toBe('Canvas: submitted as of 3 hours ago')
})

test('stale age is appended to every line shape, not just submitted', () => {
  const readAt = NOW - 5 * 60 * 60 * 1000
  expect(
    canvasLine(true, meta({ missing: true, read_at: new Date(readAt).toISOString() }), NOW),
  ).toBe('Canvas: marked missing as of 5 hours ago')
})

test('no read_at at all: no age said, still the base line', () => {
  expect(canvasLine(true, meta({ canvas_submitted: true, read_at: null }), NOW)).toBe(
    'Canvas: submitted',
  )
})

// canvasMetaOf

test('canvasMetaOf reads a Canvas-backed structured_meta', () => {
  const raw = { canvas_assignment_id: 55, canvas_submitted: true }
  expect(canvasMetaOf(raw)).toEqual(raw)
})

test('canvasMetaOf returns null for a non-Canvas row', () => {
  expect(canvasMetaOf(null)).toBeNull()
  expect(canvasMetaOf(undefined)).toBeNull()
  expect(canvasMetaOf({})).toBeNull()
  expect(canvasMetaOf({ some_other_key: true })).toBeNull()
})
