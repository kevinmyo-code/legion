import { expect, test } from 'vitest'

import type { Checklist, ChecklistItem, ChecklistTick, Event } from '@/api/types'
import { localDayOf } from '@/lib/day'
import { eventsOnDay, itemsDueOn } from '@/lib/today'

// `vite.config.ts`'s `test.env.TZ` fixes the suite to America/Chicago.

function event(overrides: Partial<Event>): Event {
  return {
    id: crypto.randomUUID(),
    title: 'untitled',
    starts_at: null,
    ends_at: null,
    all_day: false,
    location: null,
    notes: null,
    source: 'legion',
    google_event_id: null,
    done: false,
    done_at: null,
    sort_order: null,
    trigger_place_label: null,
    repeat_kind: null,
    repeat_every: null,
    repeat_days_of_week: null,
    repeat_day: null,
    repeat_month: null,
    repeat_end_kind: null,
    repeat_end_date: null,
    repeat_end_count: null,
    exact: false,
    exact_downgraded: false,
    missed_at: null,
    missed_dismissed_at: null,
    logged_at: null,
    provenance: 'DETERMINISTIC',
    created_at: '2026-01-01T00:00:00Z',
    updated_at: '2026-01-01T00:00:00Z',
    deleted_at: null,
    origin_guid: null,
    structured_meta: null,
    kind: 'event',
    ...overrides,
  }
}

test('eventsOnDay buckets by the VIEWER local day, matching the Sunday-homework case', () => {
  const sundayDeadline = event({
    kind: 'task',
    title: 'Chem homework',
    starts_at: '2026-09-14T04:59:00Z', // Chicago-local Sunday 11:59pm
  })
  const sunday = localDayOf('2026-09-14T04:59:00Z')
  expect(eventsOnDay(sunday, [sundayDeadline])).toEqual([sundayDeadline])
  expect(eventsOnDay(sunday + 1, [sundayDeadline])).toEqual([])
})

test('eventsOnDay excludes soft-deleted rows and rows with no anchor at all', () => {
  const anchoredDay = localDayOf('2026-09-10T15:00:00Z')
  const deleted = event({
    starts_at: '2026-09-10T15:00:00Z',
    deleted_at: '2026-09-11T00:00:00Z',
  })
  const untimed = event({ starts_at: null })
  expect(eventsOnDay(anchoredDay, [deleted, untimed])).toEqual([])
})

function checklist(overrides: Partial<Checklist>): Checklist {
  return {
    id: crypto.randomUUID(),
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

function item(checklistId: string, overrides: Partial<ChecklistItem> = {}): ChecklistItem {
  return {
    id: crypto.randomUUID(),
    checklist: checklistId,
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

test('itemsDueOn only surfaces items from a checklist with a real schedule', () => {
  const day = localDayOf('2026-06-01T12:00:00Z')
  const plainList = checklist({ name: 'Groceries', schedule_kind: null })
  const dailyList = checklist({ name: 'Morning routine', schedule_kind: 'DAILY', schedule_every: 1 })
  const groceryItem = item(plainList.id, { text: 'Milk' })
  const routineItem = item(dailyList.id, { text: 'Stretch' })

  const due = itemsDueOn(day, [plainList, dailyList], [groceryItem, routineItem], [])
  expect(due).toHaveLength(1)
  expect(due[0].item.text).toBe('Stretch')
})

test('itemsDueOn reports whether today already has a tick', () => {
  const day = localDayOf('2026-06-01T12:00:00Z')
  const list = checklist({ schedule_kind: 'DAILY', schedule_every: 1 })
  const stretch = item(list.id, { text: 'Stretch' })
  const tick: ChecklistTick = {
    id: crypto.randomUUID(),
    item: stretch.id,
    day,
    ticked_at: '2026-09-10T12:00:00Z',
    updated_at: '2026-09-10T12:00:00Z',
    deleted_at: null,
    sync_id: null,
    value: null,
    source: 'USER_REPORTED',
  }

  const due = itemsDueOn(day, [list], [stretch], [tick])
  expect(due[0].tickedToday).toBe(true)

  const dueYesterday = itemsDueOn(day - 1, [list], [stretch], [tick])
  expect(dueYesterday[0].tickedToday).toBe(false)
})
