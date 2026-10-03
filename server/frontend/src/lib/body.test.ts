import { describe, expect, test } from 'vitest'

import type { MealLog, WorkoutPlanItem, WorkoutSetLog } from '@/api/aspects'
import { inForceOn, macroTotalsOn, weekProgress, workoutDaysThisWeek } from '@/lib/body'

const meal = (loggedAt: string, macros: [number | null, number | null, number | null, number | null]): MealLog =>
  ({
    description: 'x',
    calories_kcal: macros[0],
    protein_g: macros[1],
    carbs_g: macros[2],
    fat_g: macros[3],
    logged_at: loggedAt,
  }) as MealLog

describe('inForceOn', () => {
  const targets = [
    { from: '2026-01-01', v: 'a' },
    { from: '2026-06-01', v: 'b' },
    { from: '2026-12-01', v: 'c' },
  ]
  const from = (row: { from: string }) => row.from

  test('is the latest target that has started', () => {
    expect(inForceOn(targets, '2026-07-04', from)?.v).toBe('b')
    expect(inForceOn(targets, '2026-06-01', from)?.v).toBe('b')
    expect(inForceOn(targets, '2026-12-31', from)?.v).toBe('c')
  })

  test('is null before the first target, not the first target applied backwards', () => {
    expect(inForceOn(targets, '2025-12-31', from)).toBeNull()
    expect(inForceOn([], '2026-07-04', from)).toBeNull()
  })
})

describe('macroTotalsOn', () => {
  test('sums only that local day and counts the meals it could not include', () => {
    const meals = [
      meal('2026-10-07T14:00:00Z', [260, 22, 31, 5]),
      meal('2026-10-07T18:00:00Z', [720, 52, 78, 18]),
      meal('2026-10-07T20:00:00Z', [null, null, null, null]),
      meal('2026-10-06T18:00:00Z', [999, 99, 99, 99]),
    ]
    expect(macroTotalsOn(meals, '2026-10-07')).toEqual({
      calories: 980,
      protein: 74,
      carbs: 109,
      fat: 23,
      mealsWithoutEstimate: 1,
    })
  })

  test('a meal with some figures counts those and is not called a gap', () => {
    const totals = macroTotalsOn([meal('2026-10-07T18:00:00Z', [300, null, null, null])], '2026-10-07')
    expect(totals).toMatchObject({ calories: 300, protein: 0, mealsWithoutEstimate: 0 })
  })

  test('an evening meal belongs to the viewer\'s day, not the UTC date', () => {
    // 02:00 UTC on the 8th is 21:00 on the 7th in Chicago.
    expect(macroTotalsOn([meal('2026-10-08T02:00:00Z', [500, 0, 0, 0])], '2026-10-07').calories).toBe(500)
    expect(macroTotalsOn([meal('2026-10-08T02:00:00Z', [500, 0, 0, 0])], '2026-10-08').calories).toBe(0)
  })
})

describe('weekProgress', () => {
  const item = (exercise: string, sets: number, from: string, reps: number | null = null): WorkoutPlanItem =>
    ({ exercise, target_sets_per_week: sets, effective_from_week: from, reps_per_set: reps }) as WorkoutPlanItem
  const log = (exercise: string, sets: number, at: string): WorkoutSetLog =>
    ({ exercise, sets, logged_at: at }) as WorkoutSetLog

  // Wednesday 7 Oct 2026: the week is Monday 5 to Sunday 11.
  const today = '2026-10-07'

  test('counts sets this Monday to Sunday against the target in force', () => {
    const progress = weekProgress(
      [item('Squat', 9, '2026-09-01', 5), item('Squat', 12, '2026-10-05', 5), item('Squat', 20, '2026-10-12')],
      [
        log('squat', 3, '2026-10-05T23:00:00Z'),
        log('Squat', 3, '2026-10-07T23:00:00Z'),
        log('Squat', 3, '2026-10-04T18:00:00Z'), // Sunday before: last week
        log('Squat', 3, '2026-10-12T18:00:00Z'), // next Monday
      ],
      today,
    )
    // The 12-set target started this Monday; the 20-set one starts next week.
    expect(progress).toEqual([{ exercise: 'Squat', targetSets: 12, repsPerSet: 5, setsDone: 6 }])
  })

  test('an exercise whose plan has not started yet is not shown', () => {
    expect(weekProgress([item('Row', 6, '2026-10-12')], [], today)).toEqual([])
  })

  test('counts workout days, not sets', () => {
    const sets = [
      log('Squat', 3, '2026-10-05T23:00:00Z'),
      log('Bench', 3, '2026-10-05T23:30:00Z'),
      log('Squat', 3, '2026-10-07T23:00:00Z'),
      log('Squat', 3, '2026-09-30T23:00:00Z'),
    ]
    expect(workoutDaysThisWeek(sets, today)).toBe(2)
  })
})
