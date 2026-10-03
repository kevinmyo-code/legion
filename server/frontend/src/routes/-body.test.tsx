import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

import { seedAspects } from '@/test/aspects-seed'
import { createEngine } from '@/test/engine'
import { pressTab, renderApp } from '@/test/render-app'

/**
 * `/body`: weight, sleep, meals against targets, workouts. Time is pinned to a
 * Wednesday (7 Oct 2026, local) so "this week" has a Monday to count from
 * whatever day the suite runs on; only `Date` is faked, so waits still work.
 */

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date(2026, 9, 7, 12, 0, 0))
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

const engineWith = (tables = seedAspects()) => createEngine({ tables })

async function openTab(name: string) {
  pressTab(await screen.findByRole('tab', { name }))
}

describe('at family width', () => {
  test('is the bigger-screen card and reads nothing', async () => {
    const engine = engineWith()
    renderApp('/body', engine, 'family')
    expect(await screen.findByText('This page is made for a bigger screen.')).toBeInTheDocument()
    expect(screen.queryByRole('tab')).not.toBeInTheDocument()
    expect(Object.keys(engine.calls).filter((key) => key.includes('/api/body'))).toEqual([])
  })
})

describe('weight', () => {
  test('shows the latest figure and a trend drawn beside its numbers', async () => {
    renderApp('/body', engineWith())

    expect(await screen.findByText(/^\d+(\.\d)? lbs$/, { selector: 'p' })).toBeInTheDocument()
    expect(await screen.findByRole('img', { name: /Bodyweight over the last 24 entries in lbs/ })).toBeInTheDocument()
    expect(screen.getByText(/Last 24 entries in lbs, oldest on the left\./)).toBeInTheDocument()
    // The table is the numbers; the chart is only the glance.
    expect(screen.getAllByRole('row').length).toBeGreaterThan(5)
  })

  test('with no entries it says so in a sentence and draws no chart', async () => {
    renderApp('/body', engineWith({}))
    expect(await screen.findByText(/No weight logged yet\./)).toBeInTheDocument()
    expect(screen.queryByRole('img', { name: /Bodyweight/ })).not.toBeInTheDocument()
  })

  test('with one entry there is a figure and no fake trend', async () => {
    const tables = seedAspects()
    tables['body/bodyweight_logs'] = tables['body/bodyweight_logs'].slice(0, 1)
    renderApp('/body', engineWith(tables))
    expect(await screen.findByText(/One entry so far, so there is no trend to draw yet\./)).toBeInTheDocument()
    expect(screen.queryByRole('img', { name: /Bodyweight/ })).not.toBeInTheDocument()
  })

  test('round-trips: log, edit, delete; a typed entry is reported, never proven', async () => {
    const engine = engineWith()
    renderApp('/body', engine)
    await screen.findByRole('button', { name: /Log weight/ })

    fireEvent.click(screen.getByRole('button', { name: /Log weight/ }))
    let dialog = await screen.findByRole('dialog', { name: 'Log weight' })
    fireEvent.change(within(dialog).getByLabelText('Weight'), { target: { value: '175.5' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    const stored = engine.tables['body/bodyweight_logs'].find((row) => row.weight_value === 175.5)
    expect(stored).toMatchObject({ weight_unit: 'lbs', trust_tier: 'REPORTED' })
    expect(await screen.findByRole('cell', { name: '175.5 lbs' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Edit the 175.5 lbs entry' }))
    dialog = await screen.findByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Weight'), { target: { value: '176' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(stored).toMatchObject({ weight_value: 176 }))

    fireEvent.click(await screen.findByRole('button', { name: 'Delete the 176 lbs entry' }))
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(stored?.deleted_at).toBeTruthy())
  })

  test('editing a proven entry says it becomes reported', async () => {
    const engine = engineWith()
    renderApp('/body', engine)
    // The newest seeded entry is proven (index 0).
    const proven = [...engine.tables['body/bodyweight_logs']].find((row) => row.trust_tier === 'PROVEN')!
    const label = `Edit the ${String(proven.weight_value)} lbs entry`
    fireEvent.click((await screen.findAllByRole('button', { name: label }))[0])
    expect(await screen.findByText(/This entry is proven\. Saving a change makes it reported/)).toBeInTheDocument()
  })

  test('a refused save shows the engine sentence and keeps the dialog', async () => {
    const engine = engineWith()
    engine.refusals['PUT /api/body/bodyweight_logs/*'] = {
      status: 400,
      body: { weight_value: ['weight_value must be greater than 0 (got -3).'] },
    }
    renderApp('/body', engine)
    fireEvent.click(await screen.findByRole('button', { name: /Log weight/ }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Weight'), { target: { value: '-3' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'Nothing was saved. weight_value: weight_value must be greater than 0 (got -3).',
    )
  })

  test('a field left blank is caught before the engine is asked', async () => {
    const engine = engineWith()
    renderApp('/body', engine)
    fireEvent.click(await screen.findByRole('button', { name: /Log weight/ }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Weight is needed.')
    expect(engine.calls['PUT /api/body/bodyweight_logs/']).toBeUndefined()
  })
})

describe('sleep', () => {
  test('measures the last night against the target, and draws bars beside the table', async () => {
    renderApp('/body', engineWith())
    await openTab('Sleep')

    expect(await screen.findByRole('meter', { name: /Night of Oct 7, 2026/ })).toBeInTheDocument()
    expect(screen.getByText(/of 8 h target/)).toBeInTheDocument()
    expect(await screen.findByRole('img', { name: /Hours slept on the last 14 logged nights/ })).toBeInTheDocument()
    expect(screen.getByText(/A night with no entry has no bar: it is not counted as zero\./)).toBeInTheDocument()
  })

  test('with no target it says there is none, rather than inventing a line', async () => {
    const tables = seedAspects()
    tables['body/sleep_targets'] = []
    renderApp('/body', engineWith(tables))
    await openTab('Sleep')
    expect(await screen.findByText(/No sleep target is set for that night\./)).toBeInTheDocument()
    expect(screen.queryByRole('meter')).not.toBeInTheDocument()
  })

  test('logs a night as hours and minutes stored as minutes', async () => {
    const engine = engineWith()
    renderApp('/body', engine)
    await openTab('Sleep')
    fireEvent.click(await screen.findByRole('button', { name: /Log a night/ }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Night of'), { target: { value: '2026-10-08' } })
    fireEvent.change(within(dialog).getByLabelText('Hours'), { target: { value: '7' } })
    fireEvent.change(within(dialog).getByLabelText('Minutes'), { target: { value: '30' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    const stored = engine.tables['body/sleep_logs'].find((row) => row.sleep_date === '2026-10-08')
    expect(stored).toMatchObject({ duration_minutes: 450, quality: null, trust_tier: 'REPORTED' })
  })

  test('a table with nothing in it says so, with no chart', async () => {
    renderApp('/body', engineWith({}))
    await openTab('Sleep')
    expect(await screen.findByText('No sleep logged yet.')).toBeInTheDocument()
    expect(screen.queryByRole('img', { name: /Hours slept/ })).not.toBeInTheDocument()
  })
})

describe('meals', () => {
  test('measures the day against the target, every logged figure marked estimate and the target not', async () => {
    renderApp('/body', engineWith())
    await openTab('Meals')

    const calories = await screen.findByRole('meter', { name: 'Calories' })
    expect(calories).toHaveAttribute('aria-valuetext', '1,620 kcal of 2,200 kcal')
    // 260 + 720 + 640 kcal: the coffee with no figures is counted as a gap, not as zero kcal.
    expect(screen.getByText(/1 meal that day has no estimate and is not in these totals\./)).toBeInTheDocument()
    expect(screen.getAllByText('estimate').length).toBeGreaterThanOrEqual(4 + 3)
    // The meter line says "of", never a percentage or a score.
    expect(screen.queryByText(/%/)).not.toBeInTheDocument()
  })

  test('a day with no meals says so, and a day with no target says so', async () => {
    const tables = seedAspects()
    tables['body/meal_targets'] = []
    renderApp('/body', engineWith(tables))
    await openTab('Meals')
    expect(await screen.findByText(/No meal target is set, so there is nothing to measure these meals against/)).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Day'), { target: { value: '2026-09-01' } })
    expect(await screen.findByText(/No meals logged on Sep 1, 2026\./)).toBeInTheDocument()
  })

  test('logs a meal with an estimate and stores it as reported', async () => {
    const engine = engineWith()
    renderApp('/body', engine)
    await openTab('Meals')
    fireEvent.click(await screen.findByRole('button', { name: /Log a meal/ }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('What you ate'), { target: { value: 'Toast' } })
    fireEvent.change(within(dialog).getByLabelText(/Calories, kcal \(estimate\)/), { target: { value: '180' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    const stored = engine.tables['body/meal_logs'].find((row) => row.description === 'Toast')
    expect(stored).toMatchObject({ calories_kcal: 180, protein_g: null, trust_tier: 'REPORTED' })
    expect(await screen.findByRole('meter', { name: 'Calories' })).toHaveAttribute('aria-valuetext', '1,800 kcal of 2,200 kcal')
  })

  test('sets a target and deletes it', async () => {
    const engine = engineWith()
    renderApp('/body', engine)
    await openTab('Meals')
    fireEvent.click(await screen.findByRole('button', { name: /Set a target/ }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Calories, kcal'), { target: { value: '2000' } })
    fireEvent.change(within(dialog).getByLabelText('Protein, g'), { target: { value: '160' } })
    fireEvent.change(within(dialog).getByLabelText('Carbs, g'), { target: { value: '200' } })
    fireEvent.change(within(dialog).getByLabelText('Fat, g'), { target: { value: '60' } })
    fireEvent.change(within(dialog).getByLabelText('Starts on'), { target: { value: '2026-10-06' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(engine.tables['body/meal_targets'].find((row) => row.calories_kcal === 2000)).toBeTruthy()
    // The new target is in force today, so the meter's goal changes with it.
    expect(await screen.findByRole('meter', { name: 'Calories' })).toHaveAttribute('aria-valuetext', '1,620 kcal of 2,000 kcal')
  })
})

describe('workouts', () => {
  test('measures this week against the plan, sets and days, with no percentages', async () => {
    renderApp('/body', engineWith())
    await openTab('Workouts')

    expect(await screen.findByRole('meter', { name: 'Workout days' })).toHaveAttribute(
      'aria-valuetext',
      '3 days of 4 sessions planned',
    )
    expect(screen.getByRole('meter', { name: 'Squat (5 reps a set)' })).toHaveAttribute('aria-valuetext', '6 sets of 9 sets')
    expect(screen.getByRole('meter', { name: 'Bench press (5 reps a set)' })).toHaveAttribute(
      'aria-valuetext',
      '3 sets of 9 sets',
    )
    expect(screen.queryByText(/%/)).not.toBeInTheDocument()
  })

  test('with no plan it says nothing is in force instead of showing empty meters', async () => {
    const tables = seedAspects()
    tables['body/workout_plans'] = []
    tables['body/workout_plan_items'] = []
    renderApp('/body', engineWith(tables))
    await openTab('Workouts')
    expect(await screen.findByText(/No workout plan is in force this week/)).toBeInTheDocument()
    expect(screen.queryByRole('meter')).not.toBeInTheDocument()
  })

  test('logs sets, and weight without a unit defaults to pounds only when a weight was typed', async () => {
    const engine = engineWith()
    renderApp('/body', engine)
    await openTab('Workouts')
    fireEvent.click(await screen.findByRole('button', { name: /Log sets/ }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Exercise'), { target: { value: 'Deadlift' } })
    fireEvent.change(within(dialog).getByLabelText('Sets'), { target: { value: '2' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    const stored = engine.tables['body/workout_set_logs'].find((row) => row.exercise === 'Deadlift')
    expect(stored).toMatchObject({ sets: 2, reps: null, weight_value: null, weight_unit: null })
    expect(await screen.findByRole('cell', { name: 'Not logged' })).toBeInTheDocument()
  })
})

describe('the remaining tables each round-trip through the engine', () => {
  test('a sleep target: set, then delete', async () => {
    const engine = engineWith()
    renderApp('/body', engine)
    await openTab('Sleep')
    fireEvent.click(await screen.findByRole('button', { name: /Set a target/ }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Hours'), { target: { value: '7' } })
    fireEvent.change(within(dialog).getByLabelText('Minutes'), { target: { value: '30' } })
    fireEvent.change(within(dialog).getByLabelText('Starts on'), { target: { value: '2026-10-01' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    const stored = engine.tables['body/sleep_targets'].find((row) => row.target_minutes === 450)
    expect(stored).toMatchObject({ effective_from_date: '2026-10-01' })
    // In force from the 1st, so last night is now measured against 7 h 30 min.
    expect(await screen.findByText(/of 7 h 30 min target/)).toBeInTheDocument()

    fireEvent.click(await screen.findByRole('button', { name: /Delete the sleep target from Oct 1, 2026/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(stored?.deleted_at).toBeTruthy())
  })

  test('a workout plan and a planned exercise: add each, edit the exercise, delete it', async () => {
    const engine = engineWith()
    renderApp('/body', engine)
    await openTab('Workouts')

    fireEvent.click(await screen.findByRole('button', { name: /Add a plan/ }))
    let dialog = await screen.findByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Sessions a week'), { target: { value: '5' } })
    fireEvent.change(within(dialog).getByLabelText('From the week of'), { target: { value: '2026-10-12' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(engine.tables['body/workout_plans'].find((row) => row.sessions_per_week === 5)).toMatchObject({
      effective_from_week: '2026-10-12',
    })

    fireEvent.click(await screen.findByRole('button', { name: /Add an exercise/ }))
    dialog = await screen.findByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Exercise'), { target: { value: 'Pull-up' } })
    fireEvent.change(within(dialog).getByLabelText('Sets a week'), { target: { value: '6' } })
    fireEvent.change(within(dialog).getByLabelText('From the week of'), { target: { value: '2026-10-05' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    const item = engine.tables['body/workout_plan_items'].find((row) => row.exercise === 'Pull-up')
    expect(item).toMatchObject({ target_sets_per_week: 6, reps_per_set: null })
    // It starts this Monday, so this week's meters now include it.
    expect(await screen.findByRole('meter', { name: 'Pull-up' })).toHaveAttribute('aria-valuetext', '0 sets of 6 sets')

    fireEvent.click(screen.getByRole('button', { name: 'Edit Pull-up from Oct 5, 2026' }))
    dialog = await screen.findByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Sets a week'), { target: { value: '8' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(item).toMatchObject({ target_sets_per_week: 8 }))

    fireEvent.click(await screen.findByRole('button', { name: 'Delete Pull-up from Oct 5, 2026' }))
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(item?.deleted_at).toBeTruthy())
  })

  test('a set log with a weight keeps its unit; editing one rewrites it through the engine', async () => {
    const engine = engineWith()
    renderApp('/body', engine)
    await openTab('Workouts')
    fireEvent.click((await screen.findAllByRole('button', { name: 'Edit the Squat sets' }))[0])
    const dialog = await screen.findByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Weight (optional)'), { target: { value: '240' } })
    fireEvent.change(within(dialog).getByLabelText('Weight unit (optional)'), { target: { value: 'kg' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() =>
      expect(engine.tables['body/workout_set_logs'].some((row) => row.weight_value === 240 && row.weight_unit === 'kg')).toBe(true),
    )
  })
})
