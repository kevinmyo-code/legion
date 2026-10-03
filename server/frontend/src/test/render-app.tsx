import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider, createMemoryHistory, createRouter } from '@tanstack/react-router'
import { fireEvent, render } from '@testing-library/react'
import { vi } from 'vitest'

import { routeTree } from '@/routeTree.gen'
import type { Surface } from '@/lib/surface'

import { engineFetch, type Engine } from './engine'
import { stubSurface } from './surface'

/**
 * Render the real app, at one surface, against a fake engine.
 *
 * What every screen test starts with: stub the viewport, answer `fetch` from the
 * engine (so the request path under test is the production one up to the wire),
 * and mount the generated route tree at `path`. Nothing about a screen is
 * mocked - a test finds text and presses buttons, as a person does.
 */
export function renderApp(path: string, engine: Engine, surface: Surface = 'workbench') {
  const device = stubSurface(surface)
  vi.stubGlobal('fetch', engineFetch(engine))
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [path] }),
  })
  const view = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { ...view, device, queryClient }
}

/** Radix tabs switch on `mousedown`, not `click`, so a test has to press the way a mouse does. */
export function pressTab(tab: HTMLElement) {
  fireEvent.mouseDown(tab, { button: 0 })
  fireEvent.mouseUp(tab, { button: 0 })
  fireEvent.click(tab)
}
