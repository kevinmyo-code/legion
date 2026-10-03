import type { MealLog, WorkoutPlanItem, WorkoutSetLog } from '@/api/aspects'
import { localDay, parseDay, weekStartOf } from '@/lib/figures'

/**
 * The few calculations the Body screen does over rows it has fully read.
 *
 * Kept as plain functions so they are tested without a screen, and so none of
 * them is ever handed a partial list: `useRows` follows `next` to the end or
 * fails, and these only run on the whole.
 */

/**
 * The target in force on `day`: the one with the latest `effective_from` that is
 * not after `day`. Targets are history, not a single row someone overwrites - a
 * new target starts on its own date and leaves the old one explaining the past.
 * Null when `day` is before every target (nothing was set yet).
 */
export function inForceOn<T>(rows: readonly T[], day: string, effectiveFrom: (row: T) => string): T | null {
  let best: T | null = null
  for (const row of rows) {
    const from = effectiveFrom(row)
    if (from <= day && (best === null || from > effectiveFrom(best))) best = row
  }
  return best
}

export interface MacroTotals {
  calories: number
  protein: number
  carbs: number
  fat: number
  /** Meals that had no figure at all and so are not in the totals. */
  mealsWithoutEstimate: number
}

/**
 * The estimated macros eaten on one local day. Every figure summed here is the
 * model's guess from a meal's description (CLAUDE.md section 4 rule 5), so the
 * result is an estimate and the screen says so beside it. A meal with no
 * figures contributes nothing and is COUNTED, so the caller can say how many
 * meals the total does not include rather than letting a low total read as a
 * light day.
 */
export function macroTotalsOn(meals: readonly MealLog[], day: string): MacroTotals {
  const totals: MacroTotals = { calories: 0, protein: 0, carbs: 0, fat: 0, mealsWithoutEstimate: 0 }
  for (const meal of meals) {
    if (localDay(meal.logged_at) !== day) continue
    const figures = [meal.calories_kcal, meal.protein_g, meal.carbs_g, meal.fat_g]
    if (figures.every((value) => value === null || value === undefined)) {
      totals.mealsWithoutEstimate += 1
      continue
    }
    totals.calories += meal.calories_kcal ?? 0
    totals.protein += meal.protein_g ?? 0
    totals.carbs += meal.carbs_g ?? 0
    totals.fat += meal.fat_g ?? 0
  }
  return totals
}

export interface ExerciseProgress {
  exercise: string
  targetSets: number
  repsPerSet: number | null
  setsDone: number
}

/**
 * Sets logged this week against what the plan in force asks for.
 *
 * "This week" is Monday to Sunday in the viewer's own calendar, from
 * `weekStartOf`. An exercise has a target per `effective_from_week`; the latest
 * one not after this week's Monday is in force, so changing a target next week
 * does not rewrite this one.
 */
export function weekProgress(
  items: readonly WorkoutPlanItem[],
  sets: readonly WorkoutSetLog[],
  today: string,
): ExerciseProgress[] {
  const monday = weekStartOf(today)
  const sunday = new Date(parseDay(monday))
  sunday.setDate(sunday.getDate() + 6)
  const last = localDay(sunday)

  const byExercise = new Map<string, WorkoutPlanItem[]>()
  for (const item of items) {
    const key = item.exercise.trim().toLowerCase()
    byExercise.set(key, [...(byExercise.get(key) ?? []), item])
  }

  const progress: ExerciseProgress[] = []
  for (const [key, versions] of byExercise) {
    const current = inForceOn(versions, monday, (row) => row.effective_from_week)
    if (current === null) continue
    const setsDone = sets
      .filter((set) => set.exercise.trim().toLowerCase() === key)
      .filter((set) => {
        const day = localDay(set.logged_at)
        return day >= monday && day <= last
      })
      .reduce((sum, set) => sum + set.sets, 0)
    progress.push({
      exercise: current.exercise,
      targetSets: current.target_sets_per_week,
      repsPerSet: current.reps_per_set ?? null,
      setsDone,
    })
  }
  return progress.sort((a, b) => a.exercise.localeCompare(b.exercise))
}

/** How many different local days this week have at least one set logged. */
export function workoutDaysThisWeek(sets: readonly WorkoutSetLog[], today: string): number {
  const monday = weekStartOf(today)
  const sunday = new Date(parseDay(monday))
  sunday.setDate(sunday.getDate() + 6)
  const last = localDay(sunday)
  const days = new Set<string>()
  for (const set of sets) {
    const day = localDay(set.logged_at)
    if (day >= monday && day <= last) days.add(day)
  }
  return days.size
}
