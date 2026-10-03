import { dateForEpochDay, todayEpochDay } from '../lib/day'
import type { EngineOptions } from './engine'
import { makeChecklist, makeEvent, seedHousehold, todayAt } from './engine'

/**
 * A fortnight of a household's calendar, for the screenshots and the calendar
 * tests that only need "a believable week": shared events in pink, Kevin's
 * coursework and class schedule private, two events that overlap, an all-day
 * row, a repeating series with one occurrence skipped, and a list of each
 * visibility. Everything is relative to now, so the page always has a today.
 */

function ymd(dayOffset: number): string {
  const d = dateForEpochDay(todayEpochDay() + dayOffset)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** UTC midnight of the local date `dayOffset` days from today: an all-day row's `starts_at`. */
function allDayAt(dayOffset: number): string {
  return `${ymd(dayOffset)}T00:00:00Z`
}

/** `HH:MM` on the given day, as an ISO instant, plus a duration in minutes. */
function span(dayOffset: number, hour: number, minute: number, minutes: number) {
  const starts = todayAt(hour, minute, dayOffset)
  const ends = new Date(new Date(starts).getTime() + minutes * 60_000).toISOString()
  return { starts_at: starts, ends_at: ends }
}

const STAMP = '2026-09-01T00:00:00Z'

export function seedCalendar(): Pick<EngineOptions, 'events' | 'skips' | 'checklists' | 'items' | 'ticks'> {
  const base = seedHousehold()

  const lecture = makeEvent({
    title: 'COSC 4320 lecture',
    location: 'Class schedule',
    ...span(-14, 9, 30, 75),
    repeat_kind: 'WEEKLY',
    repeat_every: 1,
    repeat_days_of_week: 'MON,WED',
    source: 'google',
    visibility: 'private',
  })
  const bins = makeEvent({
    title: 'Bins out',
    all_day: true,
    starts_at: allDayAt(-14),
    repeat_kind: 'WEEKLY',
    repeat_every: 1,
    repeat_days_of_week: 'THU',
  })
  const swim = makeEvent({
    title: 'Swim lesson',
    location: 'Aquatic centre',
    ...span(-14, 16, 0, 45),
    repeat_kind: 'WEEKLY',
    repeat_every: 1,
    repeat_days_of_week: 'TUE',
    remind_minutes_before: 30,
  })

  const ferns = makeEvent({
    title: 'Water the ferns',
    ...span(-9, 8, 0, 15),
    repeat_kind: 'DAILY',
    repeat_every: 1,
    remind_minutes_before: 15,
  })

  const canvasTask = (title: string, dayOffset: number, meta: Record<string, unknown> = {}) =>
    makeEvent({
      title,
      kind: 'task',
      starts_at: todayAt(23, 59, dayOffset),
      visibility: 'private',
      origin_guid: `canvas:${title.length}${dayOffset}`,
      structured_meta: { canvas_assignment_id: title.length * 7, ...meta },
    })

  const events = [
    ...(base.events ?? []).filter((event) => event.title !== 'Pottery class'),
    makeEvent({ title: 'Pottery class', location: 'Studio 4', ...span(0, 17, 30, 90), visibility: 'private' }),
    // Two that overlap, to see them laid out side by side.
    makeEvent({ title: 'Planning with Mia', ...span(1, 10, 0, 90) }),
    makeEvent({ title: 'Call the plumber', ...span(1, 10, 30, 60), visibility: 'private' }),
    makeEvent({ title: 'Rent due', notes: '$1,850.00', all_day: true, starts_at: allDayAt(2) }),
    makeEvent({ title: 'Dinner reservation', location: 'Two people', ...span(4, 19, 30, 120) }),
    lecture,
    bins,
    swim,
    ferns,
    canvasTask('COSC 4320 · HW 4 due', 0, { canvas_submitted: true, submission_state: 'submitted' }),
    canvasTask('COSC 4320 · Lab 5 report due', 3),
    canvasTask('COSC 3334 · Module 2: Quiz 3 closes', 2, { missing: false }),
  ].map((event) => ({ ...event, created_at: STAMP }))

  return {
    events,
    // Next week's Bins out is skipped; this week's is not.
    skips: [
      {
        id: 'seed-skip-1',
        event: bins.id,
        skip_date: ymd(((4 - new Date().getDay() + 7) % 7) + 7),
        created_at: STAMP,
        updated_at: STAMP,
        deleted_at: null,
      },
    ],
    checklists: [
      ...(base.checklists ?? []),
      makeChecklist({ name: 'Weekend ideas', sort_order: 3, visibility: 'private' }),
    ],
    items: base.items,
    ticks: base.ticks,
  }
}
