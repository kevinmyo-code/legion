import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import { RECRUITER_DENIED_SCREENS, recruiterRefusal, visitEndsAt } from '@/lib/recruiter'
import { createEngine, seedHousehold, type EngineOptions } from '@/test/engine'
import { renderApp } from '@/test/render-app'

/**
 * A recruiter's demo visit in the normal app (recruiter-demo ticket 13, Kevin's
 * rulings 1 and 2, ADR 0069): the same shell and screens a member uses, on the
 * visitor's own copy of the made-up household, less the screens on the deny
 * list, with a demo banner linking back to `/try`. The engine is the fence;
 * these pin what the browser shows.
 */

afterEach(() => {
  vi.unstubAllGlobals()
})

const EXPIRES = new Date(Date.now() + 2 * 3_600_000).toISOString()

function visit(options: EngineOptions = {}) {
  const engine = createEngine({ ...seedHousehold(), signedIn: false, ...options })
  engine.demo.session = { role: 'recruiter', expires_at: EXPIRES, ref: 'acme' }
  return engine
}

describe('a recruiter in the app', () => {
  test('gets in, with the banner saying until when the copy is theirs', async () => {
    const { router } = renderApp('/', visit(), 'workbench')
    const banner = await screen.findByRole('note', { name: 'Demo visit' })
    await waitFor(() =>
      expect(banner).toHaveTextContent(`Demo: made-up household, yours until ${visitEndsAt(EXPIRES)}.`),
    )
    expect(within(banner).getByRole('link', { name: 'About Kevin' })).toHaveAttribute('href', '/try')
    expect(router.state.location.pathname).toBe('/')
  })

  test.each(['workbench', 'family'] as const)('the %s nav hides the deny-list screens', async (surface) => {
    renderApp('/', visit(), surface)
    const nav = await screen.findByRole('navigation', { name: surface === 'workbench' ? 'Sections' : 'Tabs' })
    for (const hidden of ['Body', 'Settings', 'Games', 'Books']) {
      expect(within(nav).queryByRole('link', { name: hidden })).not.toBeInTheDocument()
    }
    expect(within(nav).getByRole('link', { name: 'Home' })).toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: 'Lists' })).toBeInTheDocument()
    if (surface === 'workbench') {
      for (const shown of ['Money', 'Fleet', 'Places', 'Notes', 'Pantry', 'Bought', 'Calendar']) {
        expect(within(nav).getByRole('link', { name: shown })).toBeInTheDocument()
      }
    }
  })

  test.each(RECRUITER_DENIED_SCREENS.map((s) => s.prefix))(
    '%s reached by URL says why in words, not a broken page',
    async (prefix) => {
      const engine = visit()
      renderApp(prefix, engine, 'workbench')
      expect(await screen.findByRole('heading', { name: 'Not on a demo visit' })).toBeInTheDocument()
      expect(screen.getByText(new RegExp(recruiterRefusal(prefix)!.slice(0, 30)))).toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'Back to Home' })).toBeInTheDocument()
    },
  )

  test('a sub-route of a denied screen is refused too', async () => {
    renderApp('/settings/bank', visit(), 'workbench')
    expect(await screen.findByRole('heading', { name: 'Not on a demo visit' })).toBeInTheDocument()
  })

  test('Money has no Files tab and Home asks for no files ledger', async () => {
    const engine = visit()
    renderApp('/money', engine, 'workbench')
    expect(await screen.findByRole('tab', { name: 'Transactions' })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Files' })).not.toBeInTheDocument()
    expect(engine.calls['GET /api/ingest/files/']).toBeUndefined()
  })

  test('workbench Home does not ask for the files ledger or say a file is held', async () => {
    const engine = visit()
    renderApp('/', engine, 'workbench')
    expect(await screen.findByRole('note', { name: 'Demo visit' })).toBeInTheDocument()
    await screen.findByRole('region', { name: /Worth a look/ })
    expect(engine.calls['GET /api/ingest/files/']).toBeUndefined()
    expect(screen.queryByText(/bank file/)).not.toBeInTheDocument()
  })

  test('family Home has no Tetris pill', async () => {
    renderApp('/', visit(), 'family')
    expect(await screen.findByRole('note', { name: 'Demo visit' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Play Tetris' })).not.toBeInTheDocument()
  })

  test('the assistant inside the app is the members own', async () => {
    renderApp('/', visit(), 'workbench')
    await screen.findByRole('note', { name: 'Demo visit' })
    // The rail entry the members use (`AssistantRailEntry`), named for the companion.
    await waitFor(() => expect(document.querySelector('[aria-controls="assistant-dock"]')).not.toBeNull())
    // No chat in the page itself; the one dock is open beside it on load.
    expect(await screen.findByRole('complementary', { name: /^Conversation with/ })).toBeInTheDocument()
    expect(screen.getAllByRole('log')).toHaveLength(1)
  })
})

describe('a member is untouched', () => {
  test('no banner, every screen in the nav, Files tab present', async () => {
    renderApp('/money', createEngine(seedHousehold()), 'workbench')
    expect(await screen.findByRole('tab', { name: 'Files' })).toBeInTheDocument()
    expect(screen.queryByRole('note', { name: 'Demo visit' })).not.toBeInTheDocument()
    const nav = screen.getByRole('navigation', { name: 'Sections' })
    for (const shown of ['Body', 'Settings', 'Games', 'Books']) {
      expect(within(nav).getByRole('link', { name: shown })).toBeInTheDocument()
    }
  })
})
