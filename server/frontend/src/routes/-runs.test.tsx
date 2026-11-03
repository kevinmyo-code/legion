import { screen, within } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import { CANT_READ_RUNS } from '@/api/procedures'
import { RUNS_COPY } from '@/screens/runs'
import { createEngine, seedHousehold } from '@/test/engine'
import { RUN_MODEL, RUN_OK, RUN_PARTIAL, seedProcedures } from '@/test/engine-procedures'
import { renderApp } from '@/test/render-app'

/**
 * The run inspector and branch report (procedures ticket 14): runs listed, empty,
 * unreadable, one run's timeline, the branch report, and a recruiter's visit.
 * Rendered against the fake engine (`src/test/engine-procedures.ts`).
 */

afterEach(() => vi.unstubAllGlobals())

const withRuns = () => createEngine({ ...seedHousehold(), procedures: seedProcedures() })

function visit(procedures = {}) {
  const engine = createEngine({ ...seedHousehold(), signedIn: false, procedures: { ...seedProcedures(), ...procedures } })
  engine.demo.session = { role: 'recruiter', expires_at: new Date(Date.now() + 3_600_000).toISOString(), ref: 'test' }
  return engine
}

describe('the runs list', () => {
  test('lists each run with its status in a word, and links to it', async () => {
    renderApp('/runs', withRuns())
    const ok = await screen.findByRole('link', { name: /^car_check v1, OK/ })
    expect(ok).toHaveAttribute('href', `/runs/${RUN_OK}`)
    expect(screen.getByRole('link', { name: /^car_check v1, Partial/ })).toHaveAttribute('href', `/runs/${RUN_PARTIAL}`)
    expect(screen.getByText(/Head unit · Mia/)).toBeInTheDocument()
    expect(screen.getByText(/2 steps · 2\.3 s/)).toBeInTheDocument()
  })

  test('is in the desk rail', async () => {
    renderApp('/runs', withRuns())
    expect(await screen.findByRole('link', { name: 'Runs' })).toHaveAttribute('href', '/runs')
  })

  test('links each procedure to its branch report and says which never ran here', async () => {
    renderApp('/runs', withRuns())
    const money = await screen.findByRole('link', { name: /money_check v1/ })
    expect(screen.getByRole('link', { name: /^car_check v1.*How the car is/ })).toHaveAttribute(
      'href',
      '/runs/report/car_check?version=1',
    )
    expect(money).toHaveAttribute('href', '/runs/report/money_check?version=1')
    expect(within(money).getByText(/Not run here yet\./)).toBeInTheDocument()
  })

  test('with no runs it says so in words, not as a blank list', async () => {
    renderApp('/runs', createEngine({ ...seedHousehold() }))
    expect(await screen.findByText(RUNS_COPY.empty)).toBeInTheDocument()
    expect(screen.getByText(RUNS_COPY.noProcedures)).toBeInTheDocument()
  })

  test('unreadable is a different sentence from empty', async () => {
    const engine = withRuns()
    engine.refusals['GET /api/procedures/runs'] = { status: 503, body: { detail: 'The engine is restarting.' } }
    renderApp('/runs', engine)
    expect(await screen.findByText(new RegExp(`${CANT_READ_RUNS.slice(0, 40)}.*The engine is restarting\\.`))).toBeInTheDocument()
    expect(screen.queryByText(RUNS_COPY.empty)).toBeNull()
  })

  test('works at phone width', async () => {
    renderApp('/runs', withRuns(), 'family')
    expect(await screen.findByRole('link', { name: /^car_check v1, OK/ })).toBeInTheDocument()
  })
})

describe('one run', () => {
  test('shows the steps in order with kind, tool, outcome, branch and time, and what did not run', async () => {
    renderApp(`/runs/${RUN_PARTIAL}`, withRuns())
    const steps = await screen.findByRole('list', { name: 'Steps' })
    const items = within(steps).getAllByRole('listitem')
    expect(items).toHaveLength(2)
    expect(within(items[0]).getByText('vehicle')).toBeInTheDocument()
    expect(within(items[0]).getByText(/tool · read_records · 40 ms/)).toBeInTheDocument()
    expect(within(items[0]).getByText('Code chose')).toBeInTheDocument()
    expect(within(items[1]).getByText('Failed')).toBeInTheDocument()
    expect(within(items[1]).getByText(/could not read service_history/)).toBeInTheDocument()
    expect(within(items[1]).getByText(/The run stopped here/)).toBeInTheDocument()
    expect(screen.getByText('Did not run, because the run stopped first: has_codes, codes, answer.')).toBeInTheDocument()
  })

  test('a step a model steered says a model chose, and its cost is an estimate', async () => {
    renderApp(`/runs/${RUN_MODEL}`, withRuns())
    expect(await screen.findByText('A model chose')).toBeInTheDocument()
    expect(screen.getByText(/Estimate: \$0\.000022, not a billed figure/)).toBeInTheDocument()
  })

  test('a run the engine will not show says so in its words', async () => {
    renderApp('/runs/7a1e0000-0000-4000-8000-0000000000ff', withRuns())
    expect(await screen.findByText(/This run could not be read\. There is no such run in this household\./)).toBeInTheDocument()
  })
})

describe('the branch report', () => {
  test('lists every step and branch with counts, never-taken marked with a word', async () => {
    renderApp('/runs/report/car_check?version=1', withRuns())
    expect(await screen.findByRole('heading', { name: 'Branch report: car_check v1' })).toBeInTheDocument()
    expect(screen.getByText(/2 branches never taken: has_codes:pass, codes:next/)).toBeInTheDocument()
    const report = screen.getByRole('list', { name: 'Branch report' })
    const steps = within(report).getAllByRole('listitem')
    expect(steps).toHaveLength(5)
    const check = steps[2]
    expect(within(check).getByText('Never taken')).toBeInTheDocument()
    expect(within(check).getByText(/ends the run/)).toBeInTheDocument()
    expect(within(steps[1]).getByText(/Failed 1, refused 0/)).toBeInTheDocument()
    expect(within(steps[3]).getByText('Never reached.')).toBeInTheDocument()
    expect(screen.getAllByText('Never taken')).toHaveLength(2)
  })

  test('an unknown procedure says so in words', async () => {
    renderApp('/runs/report/nope', withRuns())
    expect(await screen.findByText(/There is no procedure 'nope' this household can see\./)).toBeInTheDocument()
  })
})

describe("a recruiter's visit", () => {
  test('sees the inspector, worded for the visit', async () => {
    renderApp('/runs', visit())
    expect(await screen.findByText(RUNS_COPY.introVisit)).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: /^car_check v1, OK/ })).toBeInTheDocument()
    expect(screen.queryByText(/not part of the demo/)).toBeNull()
  })

  test('with no runs yet it says how to make one', async () => {
    renderApp('/runs', visit({ runs: [] }))
    expect(await screen.findByText(RUNS_COPY.emptyVisit)).toBeInTheDocument()
  })
})
