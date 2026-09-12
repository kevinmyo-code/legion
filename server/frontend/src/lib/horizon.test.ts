import { describe, expect, it } from 'vitest'

import type { Event } from '@/api/types'
import { todayEpochDay } from '@/lib/day'
import {
  buildHorizon,
  groupByCourse,
  loadSentence,
  nextUp,
  overdueTasks,
  splitCourse,
} from '@/lib/horizon'

/**
 * The fixtures are Kevin's real rows, read from the live engine on 2026-09-12,
 * including their real timestamps. That matters: a coursework deadline is
 * `11:59 PM` local, which Canvas stores as `04:59Z the NEXT day`, and a test
 * that "simplified" those to clean local midnights would pass against a
 * bucketing bug. The phone was bitten by exactly that on the same day.
 */

const HOUR = 3_600_000
const DAY = 86_400_000

/** An epoch-day back to the UTC instant an 11:59 PM local deadline really
 * carries: next-day 04:59Z at UTC-5. Built from the day number so the suite does
 * not depend on the wall clock. */
function deadlineIso(dayOffset: number): string {
  const base = new Date()
  base.setHours(0, 0, 0, 0)
  return new Date(base.getTime() + dayOffset * DAY + 23 * HOUR + 59 * 60_000).toISOString()
}

function task(id: string, title: string, dayOffset: number, done = false): Event {
  return {
    id,
    title,
    kind: 'task',
    done,
    starts_at: deadlineIso(dayOffset),
    deleted_at: null,
  } as unknown as Event
}

function event(id: string, title: string, dayOffset: number, hour = 9): Event {
  const base = new Date()
  base.setHours(0, 0, 0, 0)
  return {
    id,
    title,
    kind: 'event',
    done: false,
    starts_at: new Date(base.getTime() + dayOffset * DAY + hour * HOUR).toISOString(),
    deleted_at: null,
  } as unknown as Event
}

describe('splitCourse', () => {
  it('splits the Canvas title on its middle dot', () => {
    expect(splitCourse('COSC 4320 Software Engineering · Module 2: Assignment 2')).toEqual({
      course: 'COSC 4320 Software Engineering',
      label: 'Module 2: Assignment 2',
    })
  })

  it('leaves a title with no course prefix whole', () => {
    // Never guess. Chopping on the first colon or dash would mangle
    // "Module 2: Assignment 2 - Waterfall Model", which contains both.
    expect(splitCourse('Auntie Greta birthday')).toEqual({
      course: null,
      label: 'Auntie Greta birthday',
    })
    expect(splitCourse('Module 2: Assignment 2 - Waterfall Model').course).toBeNull()
  })
})

describe('buildHorizon', () => {
  it('buckets an 11:59 PM local deadline on its own day, not the UTC next one', () => {
    const today = todayEpochDay()
    const cells = buildHorizon(today, [task('a', 'X · Quiz', 1)])
    expect(cells[1].tasks).toBe(1)
    expect(cells[2].tasks).toBe(0)
  })

  it('counts tasks and events apart - three lectures is not three deadlines', () => {
    const today = todayEpochDay()
    const cells = buildHorizon(today, [
      event('e1', 'Lecture', 3),
      event('e2', 'Lecture', 3),
      event('e3', 'Lecture', 3),
      task('t1', 'C · Quiz', 1),
    ])
    expect(cells[3]).toMatchObject({ events: 3, tasks: 0 })
    expect(cells[1]).toMatchObject({ events: 0, tasks: 1 })
  })

  it('carries how many of a day are already done', () => {
    const today = todayEpochDay()
    const cells = buildHorizon(today, [
      task('t1', 'C · A', 1),
      task('t2', 'C · B', 1, true),
    ])
    expect(cells[1]).toMatchObject({ tasks: 2, tasksDone: 1 })
  })

  it('ignores tombstones and rows with no anchor', () => {
    const today = todayEpochDay()
    const dead = { ...task('t1', 'C · A', 1), deleted_at: '2026-09-01T00:00:00Z' } as Event
    const floating = { ...task('t2', 'C · B', 1), starts_at: null } as unknown as Event
    const cells = buildHorizon(today, [dead, floating])
    expect(cells[1].tasks).toBe(0)
  })
})

describe('overdueTasks', () => {
  it('keeps an unfinished deadline that has passed', () => {
    const today = todayEpochDay()
    const rows = overdueTasks(today, [task('t1', 'C · Missed', -2)])
    expect(rows).toHaveLength(1)
  })

  it('drops one that was done, and one older than the window', () => {
    const today = todayEpochDay()
    const rows = overdueTasks(today, [
      task('t1', 'C · Done', -2, true),
      task('t2', 'C · Ancient', -40),
    ])
    expect(rows).toHaveLength(0)
  })

  it('never returns today or later - those belong to the day sections', () => {
    const today = todayEpochDay()
    const rows = overdueTasks(today, [task('t1', 'C · Today', 0), task('t2', 'C · Soon', 3)])
    expect(rows).toHaveLength(0)
  })
})

describe('groupByCourse', () => {
  it('groups nine identical-time deadlines by the only thing that separates them', () => {
    const rows = [
      task('a', 'MATH 3391 · Chapter 2 Quiz', 1),
      task('b', 'COSC 4320 · Waterfall Model', 1),
      task('c', 'MATH 3391 · WebAssign homework', 1),
    ]
    const groups = groupByCourse(rows)
    expect(groups.map((g) => g.course)).toEqual(['MATH 3391', 'COSC 4320'])
    expect(groups[0].items).toHaveLength(2)
  })
})

describe('loadSentence', () => {
  it('states the count rather than leaving it to be derived', () => {
    const today = todayEpochDay()
    const cells = buildHorizon(today, [
      task('a', 'C · A', 1),
      task('b', 'C · B', 1),
      task('c', 'C · C', 1),
    ])
    expect(loadSentence(cells[1])).toBe('3 things due tomorrow.')
  })

  it('says what is already done instead of hiding it', () => {
    const today = todayEpochDay()
    const cells = buildHorizon(today, [task('a', 'C · A', 1), task('b', 'C · B', 1, true)])
    expect(loadSentence(cells[1])).toBe('2 things due tomorrow, 1 already done.')
  })

  it('says all done rather than counting down to nothing', () => {
    const today = todayEpochDay()
    const cells = buildHorizon(today, [task('a', 'C · A', 0, true)])
    expect(loadSentence(cells[0])).toBe('All 1 done today.')
  })

  it('is absent rather than "0 due" on a day with no tasks', () => {
    const today = todayEpochDay()
    const cells = buildHorizon(today, [event('e', 'Lecture', 1)])
    expect(loadSentence(cells[1])).toBeNull()
    expect(loadSentence(undefined)).toBeNull()
  })
})

describe('nextUp', () => {
  it('skips today and tomorrow, which are rendered in full already', () => {
    const today = todayEpochDay()
    const cells = buildHorizon(today, [
      task('a', 'C · A', 0),
      task('b', 'C · B', 1),
      task('c', 'C · C', 4),
    ])
    expect(nextUp(cells).map((c) => c.offset)).toEqual([4])
  })

  it('skips a day whose work is finished', () => {
    const today = todayEpochDay()
    const cells = buildHorizon(today, [task('a', 'C · A', 5, true)])
    expect(nextUp(cells)).toHaveLength(0)
  })
})
