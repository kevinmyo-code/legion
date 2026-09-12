import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider, createMemoryHistory, createRouter } from '@tanstack/react-router'
import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'

import { routeTree } from '@/routeTree.gen'

/**
 * The scaffold's own smoke test proved routing + the generated client +
 * Vite's proxy work end to end (`GET /api/auth/me`). This one proves the
 * same thing for the write this ticket adds on top: a real sign-in, against
 * a faked transport, ending on the Today screen behind `/_authed`.
 */

afterEach(() => {
  vi.unstubAllGlobals()
})

function renderApp(initialEntry = '/login') {
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

test('a correct email and password signs in and lands on Today', async () => {
  const fetchMock = vi.fn(async (input: Request) => {
    const url = new URL(input.url)
    if (url.pathname === '/api/auth/session/login') {
      return new Response(
        JSON.stringify({ user_id: '3f2b1c88-0000-4000-8000-0123456789ab', email: 'a@b.com', device_name: '' }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      )
    }
    if (url.pathname === '/api/auth/me') {
      return new Response(
        JSON.stringify({ user_id: '3f2b1c88-0000-4000-8000-0123456789ab', email: 'a@b.com', device_name: '' }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      )
    }
    if (url.pathname === '/api/households/me') {
      return new Response(JSON.stringify({ id: 'h1', name: 'The Test House', members: [] }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    }
    if (url.pathname === '/api/changes') {
      return new Response(JSON.stringify({ server_time: '2026-01-01T00:00:00Z' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    }
    throw new Error(`unexpected request in test: ${url.pathname}`)
  })
  vi.stubGlobal('fetch', fetchMock)

  renderApp('/login')

  fireEvent.change(await screen.findByLabelText('Email'), { target: { value: 'a@b.com' } })
  fireEvent.change(screen.getByLabelText('Password'), {
    target: { value: 'correct horse battery staple' },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

  expect(await screen.findByText('Nothing due today.')).toBeInTheDocument()
  // Both the mobile header and the desktop rail render the household name at
  // once in JSDOM (there is no real viewport to apply the `md:hidden` /
  // `hidden md:flex` split), so this asserts presence rather than a single
  // match.
  expect(screen.getAllByText('The Test House').length).toBeGreaterThan(0)

  const loginCall = fetchMock.mock.calls.find(
    ([request]) => new URL(request.url).pathname === '/api/auth/session/login',
  )
  expect(loginCall).toBeDefined()
})

test('a wrong password says so and does not navigate anywhere', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () =>
      new Response(JSON.stringify({ detail: 'Wrong email or password.' }), {
        status: 401,
        headers: { 'Content-Type': 'application/json' },
      }),
    ),
  )

  renderApp('/login')

  fireEvent.change(await screen.findByLabelText('Email'), { target: { value: 'a@b.com' } })
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'wrong' } })
  fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

  expect(await screen.findByText('Wrong email or password.')).toBeInTheDocument()
  expect(screen.queryByText('Nothing due today.')).not.toBeInTheDocument()
})

test('visiting Today while signed out redirects to /login rather than showing a blank shell', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () =>
      new Response(JSON.stringify({ detail: 'No device token.' }), {
        status: 401,
        headers: { 'Content-Type': 'application/json' },
      }),
    ),
  )

  renderApp('/')

  expect(await screen.findByLabelText('Email')).toBeInTheDocument()
  expect(screen.getByLabelText('Password')).toBeInTheDocument()
})

test('a transport failure on Today reads as unreachable, never as an empty day', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: Request) => {
      const url = new URL(input.url)
      if (url.pathname === '/api/auth/me') {
        return new Response(
          JSON.stringify({ user_id: '3f2b1c88-0000-4000-8000-0123456789ab', email: 'a@b.com', device_name: '' }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        )
      }
      if (url.pathname === '/api/households/me') {
        throw new TypeError('network error')
      }
      if (url.pathname === '/api/changes') {
        throw new TypeError('network error')
      }
      throw new Error(`unexpected request in test: ${url.pathname}`)
    }),
  )

  renderApp('/')

  expect(
    await screen.findByText(/Could not reach the engine, so this is not today's real list\./),
  ).toBeInTheDocument()
  expect(screen.queryByText('Nothing due today.')).not.toBeInTheDocument()
})
