import type { components } from '../api/schema'
import type { Reply } from './engine'

/**
 * The fake engine's `/api/games/*` (retro-games ticket 05), following
 * `server/games/views.py` and its sentences in `server/games/matches.py`, so a
 * screen test reads the words the real engine says.
 *
 * Matches are seeded per test and changed by the write routes; a test moves the
 * other player by editing `games.matches` between polls.
 */

type Match = components['schemas']['Match']
type Player = Match['you']

export interface GamesState {
  opponents: { user_id: string; name: string }[]
  matches: Match[]
  /** What the invite says happened to the notification. */
  push: 'sent' | 'no_device' | 'turned_off' | 'failed' | 'not_set_up'
  /** Answers 404 for these ids: the match has been purged. */
  gone: Set<string>
}

export function defaultGames(): GamesState {
  return { opponents: [], matches: [], push: 'sent', gone: new Set() }
}

export const KEVIN = { user_id: '9a1d0c11-0000-4000-8000-00000000cafe', name: 'Kevin' }

export function player(user: { user_id: string; name: string }, fields: Partial<Player> = {}): Player {
  return {
    user_id: user.user_id,
    name: user.name,
    score: 0,
    lines: 0,
    level: 1,
    topped_out: false,
    finished: false,
    updated_at: null,
    board: '',
    ...fields,
  }
}

export function makeMatch(fields: Partial<Match> & Pick<Match, 'id' | 'you' | 'opponent'>): Match {
  const now = new Date()
  return {
    game: 'tetris',
    status: 'invited',
    seed: 42,
    role: 'invitee',
    created_at: now.toISOString(),
    expires_at: new Date(now.getTime() + 120_000).toISOString(),
    start_at: null,
    ends_at: null,
    server_now: now.toISOString(),
    result: null,
    detail: `${fields.opponent.name} invited you to a Tetris race.`,
    ...fields,
  }
}

const PUSH_SENTENCES: Record<GamesState['push'], (name: string) => string> = {
  sent: (n) => `Invited ${n}. A notification was sent to their device.`,
  no_device: (n) =>
    `Invited ${n}. No notification was sent: they have no device signed up for them. The invite still shows in ${n}'s Games for 2 minutes.`,
  turned_off: (n) =>
    `Invited ${n}. No notification was sent: they have Tetris invites turned off. The invite still shows in ${n}'s Games for 2 minutes.`,
  failed: (n) =>
    `Invited ${n}. The notification did not reach any of their devices. The invite still shows in ${n}'s Games for 2 minutes.`,
  not_set_up: (n) =>
    `Invited ${n}. No notification was sent: notifications are not set up on this server. The invite still shows in ${n}'s Games for 2 minutes.`,
}

const NOT_FOUND = 'No such match. It may have closed more than ten minutes ago. Nothing was changed.'

export function handleGames(
  host: { games: GamesState },
  me: { user_id: string; name: string },
  method: string,
  pathname: string,
  body: unknown,
): Reply | undefined {
  if (!pathname.startsWith('/api/games/')) return undefined
  const g = host.games
  const now = new Date().toISOString()
  const fresh = (match: Match): Match => ({ ...match, server_now: now })

  if (method === 'GET' && pathname === '/api/games/opponents/') return { status: 200, body: g.opponents }

  if (method === 'GET' && pathname === '/api/games/matches/pending/') {
    return {
      status: 200,
      body: g.matches.filter((m) => m.status === 'invited' && m.role === 'invitee').map(fresh),
    }
  }

  if (method === 'POST' && pathname === '/api/games/matches/') {
    const target = g.opponents.find((o) => o.user_id === (body as { opponent_user_id?: string }).opponent_user_id)
    if (!target) {
      return {
        status: 400,
        body: { detail: 'No invite was made. That person is not another member of your household.' },
      }
    }
    const match = makeMatch({
      id: crypto.randomUUID(),
      role: 'inviter',
      you: player(me),
      opponent: player(target),
      detail: `Waiting for ${target.name} to accept.`,
    })
    g.matches.push(match)
    return { status: 201, body: { match, push: g.push, detail: PUSH_SENTENCES[g.push](target.name) } }
  }

  const found = pathname.match(/^\/api\/games\/matches\/([^/]+)\/(?:(accept|decline|cancel|progress)\/)?$/)
  if (!found) return undefined
  const [, id, verb] = found
  const match = g.matches.find((m) => m.id === id)
  if (!match || g.gone.has(id)) return { status: 404, body: { detail: NOT_FOUND } }

  if (method === 'GET' && verb === undefined) return { status: 200, body: fresh(match) }

  if (method === 'POST' && (verb === 'accept' || verb === 'decline' || verb === 'cancel')) {
    const needs = verb === 'cancel' ? 'inviter' : 'invitee'
    const done = { accept: 'accepted', decline: 'declined', cancel: 'cancelled' }[verb]
    if (match.role !== needs) {
      return { status: 403, body: { detail: `Nothing was ${done}. Only the person ${needs === 'invitee' ? 'invited' : 'who invited'} can do that.` } }
    }
    if (match.status !== 'invited') {
      return { status: 409, body: { detail: `Nothing was ${done}. ${match.detail}` } }
    }
    if (verb === 'accept') {
      const start = Date.now() + 5000
      Object.assign(match, {
        status: 'playing',
        start_at: new Date(start).toISOString(),
        ends_at: new Date(start + 180_000).toISOString(),
        detail: `Racing ${match.opponent.name}.`,
      })
    } else if (verb === 'decline') {
      Object.assign(match, { status: 'declined', detail: 'You declined. Nothing was played.' })
    } else {
      Object.assign(match, { status: 'cancelled', detail: 'You cancelled the invite. Nothing was played.' })
    }
    return { status: 200, body: fresh(match) }
  }

  if (method === 'POST' && verb === 'progress') {
    if (match.status !== 'playing') {
      return { status: 409, body: { detail: `Nothing was saved. ${match.detail}` } }
    }
    const sent = body as Partial<Player>
    match.you = {
      ...match.you,
      score: sent.score ?? 0,
      lines: sent.lines ?? 0,
      level: sent.level ?? 1,
      topped_out: Boolean(sent.topped_out) || match.you.topped_out,
      finished: Boolean(sent.finished) || match.you.finished,
      updated_at: now,
      board: typeof sent.board === 'string' ? sent.board : match.you.board,
    }
    return { status: 200, body: fresh(match) }
  }

  return undefined
}
