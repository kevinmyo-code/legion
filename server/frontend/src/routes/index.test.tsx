import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider, createMemoryHistory, createRouter } from '@tanstack/react-router'
import { render, screen } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'

import { routeTree } from '@/routeTree.gen'

/**
 * The scaffold's smoke test: the `/` route, the generated client and the
 * session middleware, exercised together against a faked transport.
 *
 * `fetch` is stubbed rather than MSW-mocked because what is being proved here
 * is the wiring, not the protocol - one round trip, one body, one assertion.
 * Ticket 05's screens are where a request-level mock starts earning its keep.
 */

afterEach(() => {
  vi.unstubAllGlobals()
})

function renderApp() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: ['/'] }),
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
}

test('the home route shows who the engine says you are', async () => {
  const me = {
    user_id: '3f2b1c88-0000-4000-8000-0123456789ab',
    email: 'kevinmyo@gmail.com',
    device_name: 'Kevin desk browser',
  }
  // The parameter is declared even though the body ignores it: without it the
  // mock's `calls` tuple types as empty and the URL assertion below cannot be
  // written without a cast through `unknown`.
  const fetchMock = vi.fn(
    async (_input: Request) =>
      new Response(JSON.stringify(me), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
  )
  vi.stubGlobal('fetch', fetchMock)

  renderApp()

  expect(await screen.findByText(/kevinmyo@gmail\.com/)).toBeInTheDocument()
  expect(screen.getByText(/Kevin desk browser/)).toBeInTheDocument()

  // The URL the client built is part of what this test is for: it comes from
  // the generated `paths` type, so a path renamed on the server shows up here
  // rather than as a 404 in a browser.
  const [requested] = fetchMock.mock.calls[0]
  expect(new URL(requested.url).pathname).toBe('/api/auth/me')
})

test('a 401 reads as not signed in, never as an error', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () =>
      new Response(JSON.stringify({ detail: 'No device token.' }), {
        status: 401,
        headers: { 'Content-Type': 'application/json' },
      }),
    ),
  )

  renderApp()

  expect(await screen.findByText('Not signed in')).toBeInTheDocument()
})
