import { fireEvent, screen } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import { CANT_READ_OPPONENTS } from '@/api/games'
import { MATCH_COPY } from '@/screens/games-match'
import { ME, createEngine, seedHousehold } from '@/test/engine'
import { KEVIN, makeMatch, player } from '@/test/engine-games'
import { renderApp } from '@/test/render-app'

/**
 * The PWA's Tetris (retro-games ticket 05): the Games page, an invite both ways,
 * every way an invite can end said in words, and the race against the engine's
 * clock. Rendered against the fake engine, which speaks `server/games`' sentences.
 */

afterEach(() => vi.unstubAllGlobals())

const MATCH_ID = '5c0ffee0-0000-4000-8000-000000000001'

function setUp(games = {}) {
  return createEngine({ ...seedHousehold(), games: { opponents: [KEVIN], ...games } })
}

function secondsFromNow(seconds: number) {
  return new Date(Date.now() + seconds * 1000).toISOString()
}

describe('the Games page', () => {
  test('offers solo and a race with each other member, on the family surface too', async () => {
    renderApp('/games', setUp(), 'family')
    expect(await screen.findByRole('link', { name: /Play Tetris/ })).toHaveAttribute('href', '/games/solo')
    expect(await screen.findByRole('button', { name: 'Play Kevin' })).toBeInTheDocument()
  })

  test('with the engine unreachable it says so, and solo is still there', async () => {
    const engine = setUp()
    engine.refusals['GET /api/games/opponents/'] = { status: 503 }
    renderApp('/games', engine, 'family')
    expect(await screen.findByText(CANT_READ_OPPONENTS)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Play Tetris/ })).toBeInTheDocument()
  })

  test('inviting opens the waiting screen and says what happened to the notification', async () => {
    const engine = setUp({ push: 'no_device' })
    renderApp('/games', engine, 'family')
    fireEvent.click(await screen.findByRole('button', { name: 'Play Kevin' }))
    expect(await screen.findByText('Waiting for Kevin to accept.')).toBeInTheDocument()
    expect(
      screen.getByText(/Invited Kevin\. No notification was sent: they have no device signed up for them\./),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancel invite' })).toBeInTheDocument()
    expect(engine.games.matches).toHaveLength(1)
  })

  test('an invite addressed to me is listed first and opens the match', async () => {
    const engine = setUp({
      matches: [makeMatch({ id: MATCH_ID, you: player({ user_id: ME.user_id, name: ME.name }), opponent: player(KEVIN) })],
    })
    renderApp('/games', engine, 'family')
    const link = await screen.findByRole('link', { name: /Kevin: invited you to a race/ })
    expect(link).toHaveAttribute('href', `/games/match/${MATCH_ID}`)
  })
})

describe('an invite', () => {
  function invited(role: 'inviter' | 'invitee') {
    const me = player({ user_id: ME.user_id, name: ME.name })
    return setUp({
      matches: [
        makeMatch({
          id: MATCH_ID,
          role,
          you: me,
          opponent: player(KEVIN),
          detail: role === 'inviter' ? 'Waiting for Kevin to accept.' : 'Kevin invited you to a Tetris race.',
        }),
      ],
    })
  }

  test('the invitee can accept, and the countdown to the engine start begins', async () => {
    const engine = invited('invitee')
    renderApp(`/games/match/${MATCH_ID}`, engine, 'family')
    expect(await screen.findByText('Kevin invited you to a Tetris race.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Accept' }))
    expect(await screen.findByText(/Starting\. Same pieces for both of you/)).toBeInTheDocument()
    expect(engine.games.matches[0].status).toBe('playing')
  })

  test('a decline is said in words', async () => {
    const engine = invited('invitee')
    renderApp(`/games/match/${MATCH_ID}`, engine, 'family')
    fireEvent.click(await screen.findByRole('button', { name: 'Decline' }))
    expect(await screen.findByText('You declined. Nothing was played.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to Games' })).toBeInTheDocument()
  })

  test('the inviter, polling, is told in words when it is declined', async () => {
    const engine = invited('inviter')
    renderApp(`/games/match/${MATCH_ID}`, engine, 'family')
    expect(await screen.findByText('Waiting for Kevin to accept.')).toBeInTheDocument()
    Object.assign(engine.games.matches[0], { status: 'declined', detail: 'Kevin declined. Nothing was played.' })
    expect(await screen.findByText('Kevin declined. Nothing was played.', {}, { timeout: 4000 })).toBeInTheDocument()
  })

  test('an expired invite says it expired', async () => {
    const engine = invited('inviter')
    Object.assign(engine.games.matches[0], {
      status: 'expired',
      detail: 'The invite expired: it was not accepted within 2 minutes. Nothing was played.',
    })
    renderApp(`/games/match/${MATCH_ID}`, engine, 'family')
    expect(
      await screen.findByText('The invite expired: it was not accepted within 2 minutes. Nothing was played.'),
    ).toBeInTheDocument()
  })

  test('a refused accept says nothing was accepted', async () => {
    const engine = invited('invitee')
    engine.refusals[`POST /api/games/matches/${MATCH_ID}/accept/`] = {
      status: 409,
      body: { detail: 'Nothing was accepted. Kevin cancelled the invite. Nothing was played.' },
    }
    renderApp(`/games/match/${MATCH_ID}`, engine, 'family')
    fireEvent.click(await screen.findByRole('button', { name: 'Accept' }))
    expect(
      await screen.findByText('Nothing was accepted. Kevin cancelled the invite. Nothing was played.'),
    ).toBeInTheDocument()
  })

  test('a purged match is gone, not empty', async () => {
    const engine = invited('invitee')
    engine.games.gone.add(MATCH_ID)
    renderApp(`/games/match/${MATCH_ID}`, engine, 'family')
    expect(await screen.findByText(MATCH_COPY.gone)).toBeInTheDocument()
  })
})

describe('the race', () => {
  function racing(fields = {}) {
    return setUp({
      matches: [
        makeMatch({
          id: MATCH_ID,
          role: 'invitee',
          status: 'playing',
          start_at: secondsFromNow(-10),
          ends_at: secondsFromNow(170),
          you: player({ user_id: ME.user_id, name: ME.name }),
          opponent: player(KEVIN, { score: 1200 }),
          detail: 'Racing Kevin.',
          ...fields,
        }),
      ],
    })
  }

  test('shows my board, the clock and the opponent live score, and posts mine', async () => {
    const engine = racing()
    renderApp(`/games/match/${MATCH_ID}`, engine, 'family')
    expect(await screen.findByRole('img', { name: /Tetris board/ })).toBeInTheDocument()
    expect(screen.getByText('1,200')).toBeInTheDocument()
    expect(screen.getByLabelText('Time left')).toHaveTextContent(/^2:5\d$/)
    expect(screen.getByRole('button', { name: 'Hold' })).toBeEnabled()
    await vi.waitFor(
      () => expect(engine.writes.some((w) => w.pathname === `/api/games/matches/${MATCH_ID}/progress/`)).toBe(true),
      { timeout: 4000 },
    )
  })

  test('a reopened race does not replay: the score stands', async () => {
    const engine = racing({ you: player({ user_id: ME.user_id, name: ME.name }, { score: 800, updated_at: secondsFromNow(-2) }) })
    renderApp(`/games/match/${MATCH_ID}`, engine, 'family')
    expect(await screen.findByText(new RegExp(MATCH_COPY.resumed.slice(0, 40)))).toBeInTheDocument()
    expect(screen.queryByRole('img', { name: /Tetris board/ })).not.toBeInTheDocument()
  })

  test('the result is said in words with both scores', async () => {
    const engine = racing({
      status: 'ended',
      result: 'won',
      detail: 'You won, 1,500 to 1,200.',
      you: player({ user_id: ME.user_id, name: ME.name }, { score: 1500, finished: true }),
    })
    renderApp(`/games/match/${MATCH_ID}`, engine, 'family')
    expect(await screen.findByText('You won, 1,500 to 1,200.')).toBeInTheDocument()
    expect(screen.getByText('1,500')).toBeInTheDocument()
    expect(screen.getByText('1,200')).toBeInTheDocument()
  })
})
