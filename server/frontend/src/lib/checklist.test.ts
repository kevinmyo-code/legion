import { expect, test } from 'vitest'

import type { Checklist, ChecklistItem, ChecklistTick } from '@/api/types'
import { tickState } from '@/lib/checklist'

function checklist(overrides: Partial<Checklist>): Checklist {
  return {
    id: 'c1',
    name: 'untitled',
    schedule_kind: null,
    schedule_every: null,
    schedule_days_of_week: null,
    sort_order: 0,
    archived: false,
    created_at: '2026-01-01T00:00:00Z',
    updated_at: '2026-01-01T00:00:00Z',
    deleted_at: null,
    sync_id: null,
    ...overrides,
  }
}

function item(overrides: Partial<ChecklistItem> = {}): ChecklistItem {
  return {
    id: 'i1',
    checklist: 'c1',
    text: 'untitled',
    sort_order: 0,
    created_at: '2026-01-01T00:00:00Z',
    updated_at: '2026-01-01T00:00:00Z',
    deleted_at: null,
    sync_id: null,
    measure_unit: null,
    measure_target: null,
    measure_direction: null,
    ...overrides,
  }
}

function tick(overrides: Partial<ChecklistTick>): ChecklistTick {
  return {
    id: 't1',
    item: 'i1',
    day: 100,
    ticked_at: '2026-01-05T00:00:00Z',
    updated_at: '2026-01-05T00:00:00Z',
    deleted_at: null,
    sync_id: null,
    value: null,
    source: 'USER_REPORTED',
    ...overrides,
  }
}

test('a scheduled checklist item is only ticked if TODAY has a tick', () => {
  const scheduled = checklist({ schedule_kind: 'DAILY', schedule_every: 1 })
  const it = item()
  const todaysTick = tick({ day: 100 })

  expect(tickState(scheduled, it, [todaysTick], 100)).toEqual({ ticked: true, dayToClear: 100 })
  expect(tickState(scheduled, it, [todaysTick], 101)).toEqual({ ticked: false, dayToClear: 101 })
})

test('a plain checklist item is ticked if ANY live tick exists, on whatever day it happened', () => {
  const plain = checklist({ schedule_kind: null })
  const it = item()
  const oldTick = tick({ day: 50, ticked_at: '2026-01-02T00:00:00Z' })

  const state = tickState(plain, it, [oldTick], 100)
  expect(state.ticked).toBe(true)
  // Undoing it must clear day 50, the day it actually happened - not day
  // 100, "today", which has no tick at all.
  expect(state.dayToClear).toBe(50)
})

test('a plain checklist item with several ticks clears the MOST RECENT one', () => {
  const plain = checklist({ schedule_kind: null })
  const it = item()
  const older = tick({ id: 't1', day: 40, ticked_at: '2026-01-02T00:00:00Z' })
  const newer = tick({ id: 't2', day: 60, ticked_at: '2026-01-04T00:00:00Z' })

  expect(tickState(plain, it, [older, newer], 100).dayToClear).toBe(60)
})

test('a soft-deleted tick never counts as ticked', () => {
  const scheduled = checklist({ schedule_kind: 'DAILY', schedule_every: 1 })
  const it = item()
  const deletedTick = tick({ day: 100, deleted_at: '2026-01-06T00:00:00Z' })

  expect(tickState(scheduled, it, [deletedTick], 100)).toEqual({ ticked: false, dayToClear: 100 })
})

test('a tick belonging to a different item is ignored', () => {
  const scheduled = checklist({ schedule_kind: 'DAILY', schedule_every: 1 })
  const it = item({ id: 'i1' })
  const othersTick = tick({ item: 'i2', day: 100 })

  expect(tickState(scheduled, it, [othersTick], 100).ticked).toBe(false)
})
