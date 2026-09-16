import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider, createMemoryHistory, createRouter } from '@tanstack/react-router'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'

import { routeTree } from '@/routeTree.gen'

/**
 * Kevin reported ticking a checkbox on the web taking about a second to
 * register. The cause: the checkbox's own checked state came from
 * `checklist_ticks` in the SAME `CHANGES_KEY` cache the tick mutation only
 * updated by invalidating after the write resolved, so the checkbox sat
 * frozen through the POST AND the refetch that followed it. These two
 * tests are the point of `useSetChecklistTick`'s optimistic cache patch -
 * the checkbox has to move before the network settles, and has to un-move
 * and say why when the write actually fails.
 */

afterEach(() => {
  vi.unstubAllGlobals()
})

const HOUSEHOLD_RESPONSE = { id: 'h1', name: 'The Test House', members: [] }
const ME_RESPONSE = { user_id: '3f2b1c88-0000-4000-8000-0123456789ab', email: 'a@b.com', device_name: '' }

const CHECKLIST_ID = 'c1'
const ITEM_ID = 'i1'

function changesResponse() {
  return {
    server_time: '2026-01-01T00:00:00Z',
    events: [],
    checklists: [
      {
        id: CHECKLIST_ID,
        name: 'Groceries',
        schedule_kind: null,
        schedule_every: null,
        schedule_days_of_week: null,
        sort_order: 0,
        archived: false,
        created_at: '2026-01-01T00:00:00Z',
        updated_at: '2026-01-01T00:00:00Z',
        deleted_at: null,
        sync_id: null,
      },
    ],
    checklist_items: [
      {
        id: ITEM_ID,
        checklist: CHECKLIST_ID,
        text: 'Milk',
        sort_order: 0,
        created_at: '2026-01-01T00:00:00Z',
        updated_at: '2026-01-01T00:00:00Z',
        deleted_at: null,
        sync_id: null,
        measure_unit: null,
        measure_target: null,
        measure_direction: null,
      },
    ],
    checklist_ticks: [],
  }
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function renderApp(initialEntry = '/lists') {
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

test('a tick shows as done immediately, before the POST and the refetch resolve', async () => {
  let resolveTick!: () => void
  const tickPending = new Promise<void>((resolve) => {
    resolveTick = resolve
  })

  const fetchMock = vi.fn(async (input: Request) => {
    const url = new URL(input.url)
    if (url.pathname === '/api/auth/me') return json(ME_RESPONSE)
    if (url.pathname === '/api/households/me') return json(HOUSEHOLD_RESPONSE)
    if (url.pathname === '/api/changes') return json(changesResponse())
    if (url.pathname === `/api/checklists/${CHECKLIST_ID}/items/${ITEM_ID}/tick`) {
      await tickPending
      return json({}, 204)
    }
    throw new Error(`unexpected request in test: ${url.pathname}`)
  })
  vi.stubGlobal('fetch', fetchMock)

  renderApp('/lists')

  const checkbox = await screen.findByLabelText('Mark "Milk" done')
  expect(checkbox).not.toBeChecked()

  fireEvent.click(checkbox)

  // The POST has not resolved yet (`resolveTick` has not been called) - if
  // the checkbox only moved after the network settled, this would still
  // read unchecked here.
  await waitFor(() => expect(checkbox).toBeChecked())

  resolveTick()
  await waitFor(() => expect(fetchMock.mock.calls.length).toBeGreaterThan(3))
  expect(checkbox).toBeChecked()
})

test('a failed tick rolls back the checkbox and says it could not save', async () => {
  const fetchMock = vi.fn(async (input: Request) => {
    const url = new URL(input.url)
    if (url.pathname === '/api/auth/me') return json(ME_RESPONSE)
    if (url.pathname === '/api/households/me') return json(HOUSEHOLD_RESPONSE)
    if (url.pathname === '/api/changes') return json(changesResponse())
    if (url.pathname === `/api/checklists/${CHECKLIST_ID}/items/${ITEM_ID}/tick`) {
      return json({ detail: 'nope' }, 500)
    }
    throw new Error(`unexpected request in test: ${url.pathname}`)
  })
  vi.stubGlobal('fetch', fetchMock)

  renderApp('/lists')

  const checkbox = await screen.findByLabelText('Mark "Milk" done')
  fireEvent.click(checkbox)

  // The optimistic tick and its rollback both happen synchronously fast
  // here (the mock POST rejects with no delay), so what is actually
  // checkable from outside is the settled state: back to unchecked, with
  // the failure stated in words rather than the row just silently
  // reverting.
  await waitFor(() => expect(checkbox).not.toBeChecked())
  expect(await screen.findByText(/Could not save\./)).toBeInTheDocument()
})
