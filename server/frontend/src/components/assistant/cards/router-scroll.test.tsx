import {
  Link,
  Outlet,
  RouterProvider,
  createBrowserHistory,
  createRootRoute,
  createRoute,
  createRouter,
} from '@tanstack/react-router'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { useRef, useState } from 'react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import type { Line } from '@/assistant/live-client'
import { MODAL_ENTRY, clearStaleModalEntry } from '@/lib/modal-entry'
import { appRouterOptions } from '@/router-options'
import { DIAGRAM_CARD } from '@/test/cards-seed'

import { ScreenModalProvider } from './presenter'

/**
 * Review of the /try flicker fix (2026-11-19). F1: the "a modal is closing" mark must never outlive
 * the close it was set for, or a later real navigation keeps the old scroll. F2: everywhere but a
 * modal's own open and close, the app scrolls exactly as before: a navigation, Back and Forward
 * included, starts at the top, and the router never puts a box inside a page back (a chat list that
 * pins itself to its newest line stays there). The app's router options, jsdom's history, the real
 * presenter.
 */

const ORIGINAL_SCROLL_Y = window.scrollY

/** A member page: a chat list that keeps itself at its newest line, and a link to another page. */
function Member() {
  const list = useRef<HTMLDivElement>(null)
  return (
    <div>
      <Link to="/try">To the demo</Link>
      <Link to="/notes">Other page</Link>
      <div data-testid="chat" ref={list} style={{ height: 40, overflowY: 'auto' }}>
        <p style={{ height: 400 }}>Lines.</p>
      </div>
    </div>
  )
}

/** A page with the modal: each Ask opens a new diagram. */
function Demo() {
  const [lines, setLines] = useState<Line[]>([])
  return (
    <ScreenModalProvider lines={lines}>
      <button
        type="button"
        onClick={() => setLines((was) => [...was, { id: was.length + 1, kind: 'tool', ok: true, text: '', card: DIAGRAM_CARD }])}
      >
        Ask
      </button>
      <Link to="/notes">Other page</Link>
    </ScreenModalProvider>
  )
}

function app(start: string) {
  window.history.replaceState(window.history.state, '', start)
  const root = createRootRoute({ component: () => <Outlet /> })
  const tree = root.addChildren([
    createRoute({ getParentRoute: () => root, path: '/', component: Member }),
    createRoute({ getParentRoute: () => root, path: '/try', component: Demo }),
    createRoute({ getParentRoute: () => root, path: '/notes', component: () => <p>Other</p> }),
  ])
  const router = createRouter({ ...appRouterOptions, routeTree: tree, history: createBrowserHistory() })
  render(<RouterProvider router={router} />)
  return router
}

async function tick(ms = 30) {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, ms))
  })
}

function scrolled(y: number) {
  Object.defineProperty(window, 'scrollY', { configurable: true, value: y })
  document.dispatchEvent(new Event('scroll'))
}

const toTop = (s: unknown) => typeof s === 'object' && s !== null && (s as { top?: number }).top === 0

function spyScroll() {
  const calls: unknown[] = []
  vi.spyOn(window, 'scrollTo').mockImplementation((...args: unknown[]) => {
    calls.push(args[0])
  })
  return calls
}

async function back() {
  await act(async () => {
    window.history.back()
    await new Promise((resolve) => setTimeout(resolve, 50))
  })
}

async function forward() {
  await act(async () => {
    window.history.forward()
    await new Promise((resolve) => setTimeout(resolve, 50))
  })
}

async function openAndCloseByX() {
  fireEvent.click(screen.getByRole('button', { name: 'Ask' }))
  await screen.findByRole('dialog')
  await tick()
  fireEvent.click(screen.getByRole('button', { name: 'Close' }))
  await tick(60)
}

afterEach(() => {
  vi.restoreAllMocks()
  Object.defineProperty(window, 'scrollY', { configurable: true, value: ORIGINAL_SCROLL_Y })
  window.history.replaceState({}, '', '/')
})

describe('F2: the rest of the app scrolls as before', () => {
  test('a member navigation starts at the top, and so does Back', async () => {
    const calls = spyScroll()
    app('/')
    await screen.findByText('To the demo')
    await tick()
    scrolled(500)
    calls.length = 0
    fireEvent.click(screen.getByText('Other page'))
    await screen.findByText('Other')
    await tick()
    expect(calls.filter(toTop)).toHaveLength(1)
    calls.length = 0
    await back()
    await screen.findByText('To the demo')
    // As on dev: Back starts at the top too (no restored offset, no other scroll call).
    expect(calls).toEqual([expect.objectContaining({ top: 0 })])
  })

  test('the router never puts a chat list back: it stays at its newest line across a navigation and Back', async () => {
    spyScroll()
    app('/')
    const chat = await screen.findByTestId('chat')
    await tick()
    chat.scrollTop = 120
    chat.dispatchEvent(new Event('scroll'))
    fireEvent.click(screen.getByText('Other page'))
    await screen.findByText('Other')
    await tick()
    await back()
    const again = await screen.findByTestId('chat')
    // Newer lines arrived and the list pinned itself to them; nothing sets it back to 120.
    again.scrollTop = 360
    await tick(60)
    expect(again.scrollTop).toBe(360)
  })
})

describe('F1: the modal-closing mark never outlives its close', () => {
  test('a reload with a modal entry on top, then a navigation: the new page starts at the top', async () => {
    const calls = spyScroll()
    // What a reload over an open modal leaves: the modal's entry on top, no modal open.
    window.history.replaceState({ __TSR_index: 1, key: 'k1', __TSR_key: 'k1', [MODAL_ENTRY]: true }, '', '/try')
    clearStaleModalEntry()
    expect((window.history.state as Record<string, unknown>)[MODAL_ENTRY]).toBeUndefined()
    app('/try')
    await screen.findByRole('button', { name: 'Ask' })
    await tick()
    await openAndCloseByX()
    scrolled(400)
    calls.length = 0
    fireEvent.click(screen.getByText('Other page'))
    await screen.findByText('Other')
    await tick()
    expect(calls.filter(toTop)).toHaveLength(1)
  })

  test('Forward onto a closed modal entry, then a navigation: the new page starts at the top', async () => {
    const calls = spyScroll()
    app('/try')
    await screen.findByRole('button', { name: 'Ask' })
    await tick()
    await openAndCloseByX()
    await forward()
    await openAndCloseByX()
    calls.length = 0
    fireEvent.click(screen.getByText('Other page'))
    await screen.findByText('Other')
    await tick()
    expect(calls.filter(toTop)).toHaveLength(1)
  })

  test('two modals in a row, then Back, Back: the page under them never moves, the real Back starts at the top', async () => {
    const calls = spyScroll()
    app('/')
    await screen.findByText('To the demo')
    await tick()
    fireEvent.click(screen.getByText('To the demo'))
    await screen.findByRole('button', { name: 'Ask' })
    await tick()
    scrolled(300)
    calls.length = 0
    // The first closed by its X, the second by the back gesture.
    await openAndCloseByX()
    fireEvent.click(screen.getByRole('button', { name: 'Ask' }))
    await screen.findByRole('dialog')
    await tick()
    await back()
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(calls).toEqual([])
    // The real Back, to the member page.
    await back()
    await screen.findByText('To the demo')
    expect(calls.filter(toTop)).toHaveLength(1)
  })
})

describe('R2-1: a past load never decides where a page opens', () => {
  test("TanStack's stored scroll cache from a previous load (a reload) is dropped and never used", async () => {
    const calls = spyScroll()
    // What a past load left: entries under the keys a fresh counter would hand out again.
    const stale: Record<string, unknown> = {}
    for (let n = 1; n <= 20; n++) stale[`legion-no-restore-${n}`] = { window: { scrollX: 0, scrollY: 777 } }
    window.sessionStorage.setItem('tsr-scroll-restoration-v1_3', JSON.stringify(stale))
    // A fresh page load: modules evaluated again, in main.tsx's order (the cache drop first).
    vi.resetModules()
    await import('@/lib/forget-scroll-cache')
    const rr = await import('@tanstack/react-router')
    const { appRouterOptions: options } = await import('@/router-options')
    expect(window.sessionStorage.getItem('tsr-scroll-restoration-v1_3')).toBeNull()
    window.history.replaceState(window.history.state, '', '/')
    const root = rr.createRootRoute({ component: () => <rr.Outlet /> })
    const tree = root.addChildren([
      rr.createRoute({ getParentRoute: () => root, path: '/', component: () => <rr.Link to="/notes">Away</rr.Link> }),
      rr.createRoute({ getParentRoute: () => root, path: '/notes', component: () => <p>Notes</p> }),
    ])
    const router = rr.createRouter({ ...options, routeTree: tree, history: rr.createBrowserHistory() })
    render(<rr.RouterProvider router={router} />)
    await screen.findByText('Away')
    await tick()
    fireEvent.click(screen.getByText('Away'))
    await screen.findByText('Notes')
    await tick()
    await back()
    await screen.findByText('Away')
    expect(calls.filter((c) => (c as { top?: number }).top === 777)).toEqual([])
    expect(calls.filter(toTop).length).toBeGreaterThanOrEqual(2)
  })
})
