import { screen, within } from '@testing-library/react'
import { expect, test } from 'vitest'

import { createEngine, makeEvent, todayAt } from '@/test/engine'
import { renderApp } from '@/test/render-app'

/**
 * Honouring a skip "everywhere occurrences render" (web-revamp 08): Home's own
 * "on today" list is the screen a person sees it on. The expansion itself is
 * held to the shared vectors in `lib/recurrence.test.ts`; this is the same rule
 * read through the real route and the real `GET /api/changes`.
 */

function daily() {
  return makeEvent({
    title: 'Water the ferns',
    // Started a week ago, so today is one of its occurrences, not its first.
    starts_at: todayAt(9, 0, -7),
    repeat_kind: 'DAILY',
    repeat_every: 1,
  })
}

function todayKey(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function section(name: string): HTMLElement {
  return screen.getByRole('heading', { name }).closest('section') as HTMLElement
}

test('a repeating event shows on a day it did not start on', async () => {
  const engine = createEngine({ events: [daily()] })
  renderApp('/', engine, 'family')
  await screen.findByRole('heading', { name: 'On today' })
  expect(within(section('On today')).getByText('Water the ferns')).toBeInTheDocument()
})

test('a skipped occurrence is not shown, and the day around it is', async () => {
  const series = daily()
  const engine = createEngine({
    events: [series],
    skips: [
      {
        id: 'skip-1',
        event: series.id,
        skip_date: todayKey(),
        created_at: '2026-01-01T00:00:00Z',
        updated_at: '2026-01-01T00:00:00Z',
        deleted_at: null,
      },
    ],
  })
  renderApp('/', engine, 'family')
  await screen.findByRole('heading', { name: 'On today' })
  expect(within(section('On today')).getByText('Nothing on the calendar today.')).toBeInTheDocument()
  expect(within(section('On today')).queryByText('Water the ferns')).not.toBeInTheDocument()
  // Tomorrow's occurrence is untouched by today's skip.
  const tomorrow = new Date(Date.now() + 86_400_000).toLocaleDateString(undefined, {
    weekday: 'long',
    month: 'short',
    day: 'numeric',
  })
  expect(within(section(tomorrow)).getByText('Water the ferns')).toBeInTheDocument()
})
