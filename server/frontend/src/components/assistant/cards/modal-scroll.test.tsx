import {
  RouterProvider,
  createBrowserHistory,
  createRootRoute,
  createRoute,
  createRouter,
} from '@tanstack/react-router'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import type { Line } from '@/assistant/live-client'
import { appRouterOptions } from '@/router-options'
import { DIAGRAM_CARD } from '@/test/cards-seed'

import { ScreenModalProvider } from './presenter'

/**
 * Kevin, 2026-11-19 ("the diagrams are flickering hard"), found in the real browser
 * (`server/tests/realrun_try_orb_flow.mjs`): a visitor scrolled down /try asks for a diagram; the
 * modal's history entry reached the router as a navigation, and the router scrolled the page under
 * the modal to the top (the docked orb left, the stage showed through the backdrop), then on close
 * the page jumped back. Opening and closing a modal must leave the page where the visitor put it.
 * The app's own router options, a real browser history (jsdom's), the real presenter.
 */

const ORIGINAL_SCROLL_Y = window.scrollY

function Page() {
  const [lines, setLines] = useState<Line[]>([])
  return (
    <ScreenModalProvider lines={lines}>
      <button type="button" onClick={() => setLines([{ id: 1, kind: 'tool', ok: true, text: '', card: DIAGRAM_CARD }])}>
        Ask
      </button>
      {/* A box that scrolls inside the page, as the captions do while an answer streams. */}
      <div data-testid="captions" style={{ height: 40, overflowY: 'auto' }}>
        <p style={{ height: 400 }}>Words.</p>
      </div>
    </ScreenModalProvider>
  )
}

async function tick(ms = 0) {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, ms))
  })
}

afterEach(() => {
  vi.restoreAllMocks()
  Object.defineProperty(window, 'scrollY', { configurable: true, value: ORIGINAL_SCROLL_Y })
  window.history.replaceState({}, '', '/')
})

describe('a page modal and the page scroll', () => {
  test('opening and closing a diagram never scrolls the page under it to the top', async () => {
    const scrolls: unknown[] = []
    vi.spyOn(window, 'scrollTo').mockImplementation((...args: unknown[]) => {
      scrolls.push(args[0])
    })
    window.history.replaceState({}, '', '/try')
    const root = createRootRoute()
    const tryRoute = createRoute({ getParentRoute: () => root, path: '/try', component: Page })
    const router = createRouter({
      ...appRouterOptions,
      routeTree: root.addChildren([tryRoute]),
      history: createBrowserHistory(),
    })
    render(<RouterProvider router={router} />)
    await screen.findByRole('button', { name: 'Ask' })
    await tick(20)
    // The visitor has scrolled down the page to read.
    Object.defineProperty(window, 'scrollY', { configurable: true, value: 640 })
    document.dispatchEvent(new Event('scroll'))
    scrolls.length = 0

    fireEvent.click(screen.getByRole('button', { name: 'Ask' }))
    await screen.findByRole('dialog')
    await tick(20)
    const toTop = (s: unknown) => typeof s === 'object' && s !== null && (s as { top?: number }).top === 0
    expect(scrolls.filter(toTop)).toEqual([])

    fireEvent.click(screen.getByRole('button', { name: 'Close' }))
    await tick(50)
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(scrolls.filter(toTop)).toEqual([])
  })

  test("the phone's back gesture closes it, and the page under it stays where it was", async () => {
    const scrolls: unknown[] = []
    vi.spyOn(window, 'scrollTo').mockImplementation((...args: unknown[]) => {
      scrolls.push(args[0])
    })
    window.history.replaceState({}, '', '/try')
    const root = createRootRoute()
    const tryRoute = createRoute({ getParentRoute: () => root, path: '/try', component: Page })
    const router = createRouter({
      ...appRouterOptions,
      routeTree: root.addChildren([tryRoute]),
      history: createBrowserHistory(),
    })
    render(<RouterProvider router={router} />)
    await screen.findByRole('button', { name: 'Ask' })
    await tick(20)
    Object.defineProperty(window, 'scrollY', { configurable: true, value: 640 })
    document.dispatchEvent(new Event('scroll'))
    fireEvent.click(screen.getByRole('button', { name: 'Ask' }))
    await screen.findByRole('dialog')
    await tick(20)
    scrolls.length = 0
    // The back gesture: the browser pops the modal's entry (no X, no Esc).
    await act(async () => {
      window.history.back()
      await new Promise((resolve) => setTimeout(resolve, 50))
    })
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(scrolls).toEqual([])
    expect(router.state.location.pathname).toBe('/try')
  })

  test('closing a modal leaves every box inside the page where it is (the captions kept up with the answer)', async () => {
    vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
    window.history.replaceState({}, '', '/try')
    const root = createRootRoute()
    const tryRoute = createRoute({ getParentRoute: () => root, path: '/try', component: Page })
    const router = createRouter({
      ...appRouterOptions,
      routeTree: root.addChildren([tryRoute]),
      history: createBrowserHistory(),
    })
    render(<RouterProvider router={router} />)
    await screen.findByRole('button', { name: 'Ask' })
    await tick(20)
    const captions = screen.getByTestId('captions')
    captions.scrollTop = 0
    captions.dispatchEvent(new Event('scroll'))
    fireEvent.click(screen.getByRole('button', { name: 'Ask' }))
    await screen.findByRole('dialog')
    await tick(20)
    // The answer streamed on under the modal; the captions followed it.
    captions.scrollTop = 300
    captions.dispatchEvent(new Event('scroll'))
    fireEvent.click(screen.getByRole('button', { name: 'Close' }))
    await tick(50)
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(captions.scrollTop).toBe(300)
  })
})
