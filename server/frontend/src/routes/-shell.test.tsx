import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  RouterProvider,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
} from '@tanstack/react-router'
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { CalendarDays, House, Wallet } from 'lucide-react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import { WorkbenchOnly } from '@/components/bigger-screen'
import { AppShell } from '@/components/app-shell'
import { NAV, visibleNav, type NavItem } from '@/lib/nav'
import { routeTree } from '@/routeTree.gen'
import { createEngine, engineFetch, seedHousehold } from '@/test/engine'
import { stubSurface } from '@/test/surface'

/**
 * The shell split (ticket 03). Two surfaces render two different trees, so
 * these tests assert ABSENCE with `queryBy*`: a CSS-hidden element would still
 * be found and would fail them, which is the point.
 */

afterEach(() => {
  vi.unstubAllGlobals()
})

function renderApp(initialEntry: string, engine = createEngine(seedHousehold())) {
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
  return engine
}

describe('the family surface', () => {
  test('renders the tab bar and no rail', async () => {
    stubSurface('family')
    renderApp('/lists')

    const tabs = await screen.findByRole('navigation', { name: 'Tabs' })
    expect(within(tabs).getByRole('link', { name: 'Home' })).toBeInTheDocument()
    expect(within(tabs).getByRole('link', { name: 'Lists' })).toHaveAttribute('aria-current', 'page')
    expect(screen.queryByRole('navigation', { name: 'Sections' })).not.toBeInTheDocument()
  })

  test('builds a tab for every route that exists, and Settings is one of them', async () => {
    stubSurface('family')
    renderApp('/')

    const tabs = await screen.findByRole('navigation', { name: 'Tabs' })
    const labels = within(tabs)
      .getAllByRole('link')
      .map((link) => link.textContent)
    expect(labels).toEqual(['Home', 'Lists', 'Calendar', 'Settings'])
  })

  test('says the household name and, when it cannot, says it could not reach the engine', async () => {
    stubSurface('family')
    renderApp('/')
    expect(await screen.findByText('The Test House')).toBeInTheDocument()
    expect(screen.queryByText('Could not reach the engine.')).not.toBeInTheDocument()
  })

  test('a household that cannot be read says so in words, not an empty header', async () => {
    stubSurface('family')
    const engine = createEngine(seedHousehold())
    engine.householdFailing = true
    renderApp('/', engine)
    expect(await screen.findByText('Could not reach the engine.')).toBeInTheDocument()
    expect(screen.getByText('LEGION')).toBeInTheDocument()
  })
})

describe('the workbench surface', () => {
  test('renders the rail and no tab bar', async () => {
    stubSurface('workbench')
    renderApp('/lists')

    const rail = await screen.findByRole('navigation', { name: 'Sections' })
    expect(within(rail).getByRole('link', { name: 'Home' })).toBeInTheDocument()
    expect(within(rail).getByRole('link', { name: 'Lists' })).toHaveAttribute('aria-current', 'page')
    expect(screen.queryByRole('navigation', { name: 'Tabs' })).not.toBeInTheDocument()
  })

  test('lists exactly the built areas, and no area whose screen does not exist yet', async () => {
    stubSurface('workbench')
    renderApp('/')

    const rail = await screen.findByRole('navigation', { name: 'Sections' })
    const labels = within(rail)
      .getAllByRole('link')
      .map((link) => link.textContent)
    // Derived from the table, not hand-listed: every ticket that builds a screen
    // flips its `built` flag, and a hard-coded list here would have to change in
    // each of them. What this guards is the rule, not the roster.
    expect(labels).toEqual(visibleNav(NAV, 'workbench').map((item) => item.label))
    expect(labels).toContain('Home')
    expect(labels).toContain('Lists')
    for (const item of NAV.filter((entry) => !entry.built)) {
      expect(screen.queryByRole('link', { name: item.label })).not.toBeInTheDocument()
    }
  })

  test('crossing 1024 px swaps one tree for the other without a reload', async () => {
    const device = stubSurface('family')
    renderApp('/')
    expect(await screen.findByRole('navigation', { name: 'Tabs' })).toBeInTheDocument()

    act(() => device.set('workbench'))
    expect(await screen.findByRole('navigation', { name: 'Sections' })).toBeInTheDocument()
    expect(screen.queryByRole('navigation', { name: 'Tabs' })).not.toBeInTheDocument()

    act(() => device.set('family'))
    expect(await screen.findByRole('navigation', { name: 'Tabs' })).toBeInTheDocument()
    expect(screen.queryByRole('navigation', { name: 'Sections' })).not.toBeInTheDocument()
  })
})

describe('the nav table', () => {
  const everything: NavItem[] = NAV.map((item) => ({ ...item, built: true }))

  test('an unbuilt item is never visible on either surface', () => {
    for (const surface of ['family', 'workbench'] as const) {
      expect(visibleNav(NAV, surface).every((item) => item.built)).toBe(true)
    }
    // Whatever is unbuilt in the real table today, and Calendar made unbuilt on
    // purpose, so the rule is checked whichever screens have shipped.
    const gap = NAV.map((item) => (item.to === '/calendar' ? { ...item, built: false } : item))
    const unbuilt = gap.filter((item) => !item.built).map((item) => item.label)
    expect(unbuilt).toContain('Calendar')
    for (const surface of ['family', 'workbench'] as const) {
      const shown = visibleNav(gap, surface).map((item) => item.label)
      for (const label of unbuilt) expect(shown).not.toContain(label)
    }
  })

  test('with everything built the family gets four tabs and the workbench the full rail', () => {
    expect(visibleNav(everything, 'family').map((item) => item.label)).toEqual([
      'Home',
      'Lists',
      'Calendar',
      'Settings',
    ])
    expect(visibleNav(everything, 'workbench').map((item) => item.label)).toEqual([
      'Home',
      'Calendar',
      'Lists',
      'Bought',
      'Money',
      'Pantry',
      'Body',
      'Fleet',
      'Places',
      'Notes',
      'Settings',
    ])
  })

  test('the shell renders an unbuilt item nowhere, and a built one everywhere it is listed', async () => {
    vi.stubGlobal('fetch', engineFetch(createEngine()))
    const nav: NavItem[] = [
      { to: '/', label: 'Home', icon: House, surfaces: ['family', 'workbench'], built: true },
      { to: '/calendar', label: 'Calendar', icon: CalendarDays, surfaces: ['family', 'workbench'], built: false },
      { to: '/money', label: 'Money', icon: Wallet, surfaces: ['workbench'], built: true },
    ]
    const root = createRootRoute({
      component: () => (
        <AppShell nav={nav}>
          <p>page body</p>
        </AppShell>
      ),
    })
    const index = createRoute({ getParentRoute: () => root, path: '/', component: () => null })
    const router = createRouter({
      routeTree: root.addChildren([index]),
      history: createMemoryHistory({ initialEntries: ['/'] }),
    })
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    const device = stubSurface('family')
    render(
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    )

    await screen.findByText('page body')
    // Family: Home only. Calendar is unbuilt, Money is workbench-only.
    expect(screen.getByRole('link', { name: 'Home' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Calendar' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Money' })).not.toBeInTheDocument()

    act(() => device.set('workbench'))
    expect(await screen.findByRole('link', { name: 'Money' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Calendar' })).not.toBeInTheDocument()
  })
})

describe('a workbench-only route', () => {
  function renderWorkbenchOnly() {
    vi.stubGlobal('fetch', engineFetch(createEngine()))
    const root = createRootRoute()
    const money = createRoute({
      getParentRoute: () => root,
      path: '/money',
      component: () => (
        <WorkbenchOnly>
          <table aria-label="Transactions">
            <tbody>
              <tr>
                <td>Trader Joe's</td>
              </tr>
            </tbody>
          </table>
        </WorkbenchOnly>
      ),
    })
    const home = createRoute({ getParentRoute: () => root, path: '/', component: () => <p>home page</p> })
    const router = createRouter({
      routeTree: root.addChildren([home, money]),
      history: createMemoryHistory({ initialEntries: ['/money'] }),
    })
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    )
  }

  test('at family width shows the bigger-screen card, a way home, and no table at all', async () => {
    stubSurface('family')
    renderWorkbenchOnly()

    expect(await screen.findByText('This page is made for a bigger screen.')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(screen.queryByText("Trader Joe's")).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('link', { name: 'Go to Home' }))
    expect(await screen.findByText('home page')).toBeInTheDocument()
  })

  test('at workbench width shows the page itself', async () => {
    stubSurface('workbench')
    renderWorkbenchOnly()

    expect(await screen.findByRole('table', { name: 'Transactions' })).toBeInTheDocument()
    expect(screen.queryByText('This page is made for a bigger screen.')).not.toBeInTheDocument()
  })
})

describe('the theme toggle in the shell', () => {
  test('cycles System, Light, Dark and puts the dark class on the page', async () => {
    stubSurface('family', false)
    renderApp('/')

    const toggle = await screen.findByRole('button', { name: /^Theme: System/ })
    expect(document.documentElement).not.toHaveClass('dark')

    fireEvent.click(toggle)
    expect(screen.getByRole('button', { name: /^Theme: Light/ })).toBeInTheDocument()
    expect(document.documentElement).not.toHaveClass('dark')

    fireEvent.click(screen.getByRole('button', { name: /^Theme: Light/ }))
    expect(screen.getByRole('button', { name: /^Theme: Dark/ })).toBeInTheDocument()
    expect(document.documentElement).toHaveClass('dark')

    fireEvent.click(screen.getByRole('button', { name: /^Theme: Dark/ }))
    expect(screen.getByRole('button', { name: /^Theme: System/ })).toBeInTheDocument()
    expect(document.documentElement).not.toHaveClass('dark')
  })
})
