import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider, createMemoryHistory, createRouter } from '@tanstack/react-router'
import { act, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

import { CHANGES_POLL_MS } from '@/api/refetch'
import { routeTree } from '@/routeTree.gen'
import { stubSurface } from '@/test/surface'
import { createEngine, engineFetch, makeEvent, seedHousehold, todayAt, type Engine } from '@/test/engine'

/**
 * Ticket 04: the web keeps refreshing while it is open (spec D13, user story 14).
 *
 * Fake timers drive the 30 s clock. `shouldAdvanceTime` lets Testing Library's own
 * polling still run on real time alongside them, which `findBy*` needs.
 */

const CHANGES = 'GET /api/changes'

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  setVisibility('visible')
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  setVisibility('visible')
})

function setVisibility(state: 'visible' | 'hidden') {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state })
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => state === 'hidden' })
  // Bubbles, as the real event does: TanStack listens for it on `window`.
  document.dispatchEvent(new Event('visibilitychange', { bubbles: true }))
}

function renderApp(engine: Engine, initialEntry = '/', surface: 'family' | 'workbench' = 'family') {
  stubSurface(surface)
  vi.stubGlobal('fetch', engineFetch(engine))
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [initialEntry] }),
  })
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
}

async function advance(ms: number) {
  // Testing Library switches React's act environment off while a `findBy*` is
  // polling, and a timer advance that lands in that window would warn that act is
  // unsupported. The advance is wrapped in act on purpose (it flushes the renders
  // the fired timers cause), so say plainly that act is supported here.
  const env = globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }
  const previous = env.IS_REACT_ACT_ENVIRONMENT
  env.IS_REACT_ACT_ENVIRONMENT = true
  try {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(ms)
    })
  } finally {
    env.IS_REACT_ACT_ENVIRONMENT = previous
  }
}

describe('polling', () => {
  test('refetches every 30 seconds while the page is visible', async () => {
    const engine = createEngine(seedHousehold())
    renderApp(engine)
    await screen.findAllByText('Soccer pickup')
    expect(engine.calls[CHANGES]).toBe(1)

    await advance(CHANGES_POLL_MS - 2_000)
    expect(engine.calls[CHANGES]).toBe(1)

    await advance(3_000)
    await waitFor(() => expect(engine.calls[CHANGES]).toBe(2))

    await advance(CHANGES_POLL_MS)
    await waitFor(() => expect(engine.calls[CHANGES]).toBe(3))
  })

  test('does not refetch while the page is hidden', async () => {
    const engine = createEngine(seedHousehold())
    renderApp(engine)
    await screen.findAllByText('Soccer pickup')
    expect(engine.calls[CHANGES]).toBe(1)

    setVisibility('hidden')
    await advance(10 * 60_000)
    expect(engine.calls[CHANGES]).toBe(1)
  })

  test('refetches immediately on becoming visible again, without waiting out an interval', async () => {
    const engine = createEngine(seedHousehold())
    renderApp(engine)
    await screen.findAllByText('Soccer pickup')

    setVisibility('hidden')
    await advance(10 * 60_000)
    expect(engine.calls[CHANGES]).toBe(1)

    setVisibility('visible')
    await waitFor(() => expect(engine.calls[CHANGES]).toBe(2))
  })

  test('a row added elsewhere shows up on its own, within one interval', async () => {
    const engine = createEngine(seedHousehold())
    renderApp(engine)
    await screen.findAllByText('Soccer pickup')
    expect(screen.queryByText('Parent-teacher night')).not.toBeInTheDocument()

    // Kevin adds an event on the phone.
    engine.events.push(makeEvent({ title: 'Parent-teacher night', starts_at: todayAt(19, 0) }))

    await advance(CHANGES_POLL_MS + 500)
    expect((await screen.findAllByText('Parent-teacher night')).length).toBeGreaterThan(0)
  })
})

describe('when a refresh fails', () => {
  test('the rows stay on screen and the freshness line says they are old', async () => {
    const engine = createEngine(seedHousehold())
    renderApp(engine, '/', 'workbench')
    await screen.findAllByText('Soccer pickup')
    expect(screen.getByText(/Last read just now\./)).toBeInTheDocument()

    engine.changesFailing = true
    await advance(CHANGES_POLL_MS + 500)
    await waitFor(() => expect(engine.calls[CHANGES]).toBe(2))

    // Still there: a failed refresh must not turn a real day into an error page.
    expect((await screen.findAllByText('Soccer pickup')).length).toBeGreaterThan(0)
    expect(screen.queryByText(/Could not reach the engine, so this is not today's real list/)).not.toBeInTheDocument()
    // And honest about it, in words, with the age of what is on screen.
    expect(await screen.findByText(/not what is there now/)).toBeInTheDocument()

    // The age is the age of the last GOOD read, not of the failed attempt: a second
    // failed refresh a half-minute later makes it a minute old, not "just now".
    await advance(CHANGES_POLL_MS)
    await waitFor(() => expect(engine.calls[CHANGES]).toBe(3))
    expect(
      await screen.findByText(/This is what was on screen 1 minute ago - not what is there now\./),
    ).toBeInTheDocument()
    expect(screen.getAllByText('Soccer pickup').length).toBeGreaterThan(0)
  })

  test('the stale line goes away when the next refresh works', async () => {
    const engine = createEngine(seedHousehold())
    renderApp(engine, '/', 'workbench')
    await screen.findAllByText('Soccer pickup')

    engine.changesFailing = true
    await advance(CHANGES_POLL_MS + 500)
    expect(await screen.findByText(/not what is there now/)).toBeInTheDocument()

    engine.changesFailing = false
    await advance(CHANGES_POLL_MS)
    await waitFor(() => expect(screen.queryByText(/not what is there now/)).not.toBeInTheDocument())
    expect(screen.getByText(/Last read just now\./)).toBeInTheDocument()
  })

  test('a first load that fails still says unreachable, never an empty day', async () => {
    const engine = createEngine(seedHousehold())
    engine.changesFailing = true
    renderApp(engine, '/', 'workbench')

    expect(
      await screen.findByText(/Could not reach the engine, so this is not today's real list\./),
    ).toBeInTheDocument()
    expect(screen.queryByText('Nothing on the calendar today.')).not.toBeInTheDocument()
  })
})
