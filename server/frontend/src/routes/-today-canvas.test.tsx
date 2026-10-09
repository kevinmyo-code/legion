import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider, createMemoryHistory, createRouter } from '@tanstack/react-router'
import { render, screen } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'

import type { Event } from '@/api/types'
import { routeTree } from '@/routeTree.gen'

/**
 * Ticket 11's own verification step: "the Today view against real household
 * data shows 'Canvas says submitted' on at least one open task, or the
 * ticket records that none exists today." Kevin traced, 2026-09-28, exactly
 * one live row open while Canvas says submitted: "COSC 3334 Intro to
 * Cybersecurity - Module 2: Discussion - User Authentication". This is that
 * row, reconstructed as the `/api/changes` payload would actually shape it,
 * rendered through the real Today route rather than the formatter in
 * isolation - `canvas.test.ts` already covers the formatter's every case.
 */

afterEach(() => {
  vi.unstubAllGlobals()
})

const HOUSEHOLD_RESPONSE = { id: 'h1', name: 'The Test House', members: [] }
const ME_RESPONSE = { user_id: '3f2b1c88-0000-4000-8000-0123456789ab', email: 'a@b.com', device_name: '' }

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function todayIso(hour: number): string {
  const d = new Date()
  d.setHours(hour, 0, 0, 0)
  return d.toISOString()
}

function canvasEvent(overrides: Partial<Event> = {}): Event {
  return {
    id: crypto.randomUUID(),
    title: 'COSC 3334 Intro to Cybersecurity · Module 2: Discussion - User Authentication',
    starts_at: todayIso(23),
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
    exact: true,
    exact_downgraded: false,
    missed_at: null,
    missed_dismissed_at: null,
    logged_at: null,
    provenance: 'DETERMINISTIC',
    created_at: '2026-09-01T00:00:00Z',
    updated_at: '2026-09-28T00:00:00Z',
    deleted_at: null,
    origin_guid: 'canvas:999',
    kind: 'task',
    structured_meta: {
      canvas_assignment_id: 999,
      canvas_course_id: 1,
      canvas_submitted: true,
      submission_state: 'submitted',
      submitted_at: '2026-09-27T18:00:00Z',
      score: null,
      grade: null,
      late: false,
      missing: false,
      excused: false,
      points_possible: 10,
      read_at: new Date().toISOString(),
    },
    pinned_by: [],
    ...overrides,
  }
}

function renderApp(initialEntry = '/') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [initialEntry] }),
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
}

test('Today shows "Canvas says submitted" on an open, Canvas-submitted task', async () => {
  const event = canvasEvent()
  const fetchMock = vi.fn(async (input: Request) => {
    const url = new URL(input.url)
    if (url.pathname === '/api/auth/me') return json(ME_RESPONSE)
    if (url.pathname === '/api/households/me') return json(HOUSEHOLD_RESPONSE)
    if (url.pathname === '/api/changes') {
      return json({
        server_time: new Date().toISOString(),
        events: [event],
        checklists: [],
        checklist_items: [],
        checklist_ticks: [],
      })
    }
    throw new Error(`unexpected request in test: ${url.pathname}`)
  })
  vi.stubGlobal('fetch', fetchMock)

  renderApp('/')

  // The month calendar's day view (event-row.tsx's own doc comment) renders
  // the same row a second time on the page, so this asserts presence rather
  // than uniqueness.
  const lines = await screen.findAllByText('Canvas says submitted')
  expect(lines.length).toBeGreaterThan(0)
  const [checkbox] = await screen.findAllByLabelText(/Discussion - User Authentication/)
  expect(checkbox).not.toBeChecked()
})
