import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

import { coachingEnergy, coachingTrend, defaultCoach } from '@/test/engine-body-coach'
import { createEngine, type Engine } from '@/test/engine'
import { pressTab, renderApp } from '@/test/render-app'

/**
 * `/body`, the Coach tab (fat-loss ticket 09): the hands path for the coach's
 * voice tools. Every figure the engine computes reads "estimate" beside it; a
 * proposal is accepted or dismissed by tap; the profile, saved meals and waist
 * round-trip through the engine. Synthetic person only.
 */

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date(2026, 9, 7, 12, 0, 0))
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

async function openCoach(engine: Engine) {
  renderApp('/body', engine)
  pressTab(await screen.findByRole('tab', { name: 'Coach' }))
}

function coaching(): Engine {
  return createEngine({ coach: { ...defaultCoach(), energy: coachingEnergy(), trend: coachingTrend() } })
}

describe('energy', () => {
  test('with no profile it names what is missing and shows no figure', async () => {
    const engine = createEngine()
    await openCoach(engine)
    expect(await screen.findByText(/there is no maintenance figure yet/)).toBeInTheDocument()
    expect(screen.getAllByText(/Still needed/)[0]).toHaveTextContent('height, sex, birth year, activity level, a weigh-in')
    expect(screen.queryByText(/^Maintenance$/)).not.toBeInTheDocument()
    expect(engine.unhandled).toEqual([])
  })

  test('every figure carries the word estimate, and the basis is said', async () => {
    await openCoach(coaching())
    const panel = (await screen.findByRole('heading', { name: 'Energy' })).closest('section')!
    expect(await within(panel).findByText('2,500 kcal')).toBeInTheDocument()
    expect(within(panel).getByText('1,716 kcal')).toBeInTheDocument()
    expect(within(panel).getByText('190 g')).toBeInTheDocument()
    // Maintenance, target, protein and the proposal: four estimate words at least.
    expect(within(panel).getAllByText('estimate').length).toBeGreaterThanOrEqual(4)
    expect(within(panel).getByText(/Maintenance is from your own trend, 14 days/)).toBeInTheDocument()
    expect(within(panel).getByText(/Formula alone: 2,972 kcal/)).toBeInTheDocument()
  })

  test('accept writes through the engine and the target changes', async () => {
    const engine = coaching()
    await openCoach(engine)
    fireEvent.click(await screen.findByRole('button', { name: 'Accept' }))
    expect(await screen.findByText(/Your meal target: 2,050 kcal/)).toBeInTheDocument()
    expect(engine.writes.map((w) => w.pathname)).toContain(
      '/api/body/coach/proposals/00000000-0000-4000-8000-00000000c0ac/accept/',
    )
    expect(screen.queryByRole('button', { name: 'Accept' })).not.toBeInTheDocument()
  })

  test('dismiss writes nothing but the dismissal', async () => {
    const engine = coaching()
    await openCoach(engine)
    fireEvent.click(await screen.findByRole('button', { name: 'Dismiss' }))
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Dismiss' })).not.toBeInTheDocument())
    expect(engine.writes.map((w) => w.pathname)).toEqual([
      '/api/body/coach/proposals/00000000-0000-4000-8000-00000000c0ac/dismiss/',
    ])
    expect(screen.getByText(/Your meal target: 2,200 kcal/)).toBeInTheDocument()
  })

  test('a refused accept shows the engine sentence', async () => {
    const engine = coaching()
    engine.refusals['POST /api/body/coach/proposals/*'] = {
      status: 409,
      body: { detail: 'Nothing was accepted. That proposal is already dismissed, so it can no longer be accepted.' },
    }
    await openCoach(engine)
    fireEvent.click(await screen.findByRole('button', { name: 'Accept' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Nothing was accepted.')
  })
})

describe('trend', () => {
  test('draws the trend line with its readings beside the figures', async () => {
    await openCoach(coaching())
    expect(await screen.findByRole('img', { name: /Weight trend over 30 days in lbs/ })).toBeInTheDocument()
    expect(screen.getByText('-0.6 lbs a week (-0.30%)')).toBeInTheDocument()
    expect(screen.getByText('Outside the 0.5 to 1.0% loss band')).toBeInTheDocument()
    expect(screen.getByText(/19.4 lbs to go to 190 lbs, about 32 weeks/)).toBeInTheDocument()
  })

  test('with no weigh-ins it says so and draws nothing', async () => {
    await openCoach(createEngine())
    expect(await screen.findByText(/No weigh-ins yet, so there is no trend/)).toBeInTheDocument()
    expect(screen.queryByRole('img', { name: /Weight trend/ })).not.toBeInTheDocument()
  })
})

describe('profile', () => {
  test('saves the fields and the weekly line switch through one PUT', async () => {
    const engine = createEngine()
    await openCoach(engine)
    fireEvent.change(await screen.findByLabelText('Height, cm'), { target: { value: '178' } })
    fireEvent.change(screen.getByLabelText('Sex (the formula needs it)'), { target: { value: 'male' } })
    fireEvent.change(screen.getByLabelText('Birth year'), { target: { value: '1996' } })
    fireEvent.change(screen.getByLabelText('Activity'), { target: { value: 'moderate' } })
    fireEvent.click(screen.getByRole('switch', { name: 'Monday weight line' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save profile' }))
    await waitFor(() => expect(engine.coach.profile.exists).toBe(true))
    const put = engine.writes.find((w) => w.pathname === '/api/body/coach/profile/')
    expect(put?.body).toMatchObject({
      height_cm: 178,
      sex: 'male',
      birth_year: 1996,
      activity_level: 'moderate',
      target_rate_pct: 0.75,
      protein_g_per_kg: 2,
      weekly_line_on: false,
    })
    expect(await screen.findByText('Saved.')).toBeInTheDocument()
  })

  test('a refused save keeps the form and says what did not happen', async () => {
    const engine = createEngine()
    engine.refusals['PUT /api/body/coach/profile/'] = {
      status: 400,
      body: { detail: 'Nothing was saved. sex must be male or female (the formula needs one of the two); got None.' },
    }
    await openCoach(engine)
    // In the field's own range, so the browser lets it through and the engine is the one to refuse.
    fireEvent.change(await screen.findByLabelText(/Rate, % of bodyweight a week/), { target: { value: '0.5' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save profile' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Nothing was saved. sex must be male or female')
  })
})

describe('saved meals and waist', () => {
  test('save a meal, log it now, delete it', async () => {
    const engine = createEngine()
    await openCoach(engine)
    expect(await screen.findByText('No saved meals yet.')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Usual breakfast' } })
    fireEvent.change(screen.getByLabelText('Calories, kcal (estimate)'), { target: { value: '520' } })
    fireEvent.change(screen.getByLabelText('Protein, g (estimate)'), { target: { value: '38' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save meal' }))
    expect(await screen.findByText('Usual breakfast')).toBeInTheDocument()
    expect(screen.getByText('520 kcal, 38 g protein')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Log Usual breakfast now' }))
    expect(await screen.findByRole('status')).toHaveTextContent(/estimates saved with the meal/)
    expect(engine.coach.logged).toHaveLength(1)

    fireEvent.click(screen.getByRole('button', { name: 'Delete "Usual breakfast"' }))
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    expect(await screen.findByText('No saved meals yet.')).toBeInTheDocument()
  })

  test('a duplicate name is refused in the engine words', async () => {
    const engine = createEngine()
    await openCoach(engine)
    for (let i = 0; i < 2; i += 1) {
      fireEvent.change(await screen.findByLabelText('Name'), { target: { value: 'Lunch' } })
      fireEvent.change(screen.getByLabelText('Calories, kcal (estimate)'), { target: { value: '600' } })
      fireEvent.change(screen.getByLabelText('Protein, g (estimate)'), { target: { value: '40' } })
      fireEvent.click(screen.getByRole('button', { name: 'Save meal' }))
      if (i === 0) await screen.findByText('Lunch')
    }
    expect(await screen.findByRole('alert')).toHaveTextContent('already a saved meal called')
  })

  test('log a waist in cm and see it listed in both units', async () => {
    const engine = createEngine()
    await openCoach(engine)
    expect(await screen.findByText('No waist measured yet.')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Waist'), { target: { value: '90' } })
    fireEvent.change(screen.getByLabelText('Unit'), { target: { value: 'cm' } })
    fireEvent.click(screen.getByRole('button', { name: 'Log waist' }))
    expect(await screen.findByText(/35.4 in \(90 cm\)/)).toBeInTheDocument()
    expect(engine.writes.find((w) => w.pathname === '/api/body/coach/waist/')?.body).toMatchObject({
      waist_cm: 90,
      measured_on: '2026-10-07',
    })
  })
})
