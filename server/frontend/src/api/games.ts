import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '@/api/client'
import { useVisibleInterval } from '@/api/refetch'
import { WriteRefused, refusalMessage } from '@/api/refusal'
import type { components } from '@/api/schema'

/**
 * Tetris races through the engine (retro-games ticket 05, ADR 0060).
 *
 * The engine holds a match only while it lives; both sides poll it once a
 * second during a race. Nothing here is optimistic: an accept, a decline or a
 * score that the engine did not take is said to have not happened.
 */

export type Match = components['schemas']['Match']
export type Opponent = components['schemas']['Opponent']
export type InviteResponse = components['schemas']['InviteResponse']
export type Progress = components['schemas']['ProgressRequest']

export const GAMES_KEY = ['games'] as const
export const MATCH_POLL_MS = 1000

/** Statuses after which nothing more will change. */
export const CLOSED_STATUSES: readonly Match['status'][] = ['ended', 'declined', 'cancelled', 'expired']

export const CANT_READ_OPPONENTS =
  "Can't reach the engine, so the household's members can't be listed. Solo still works."

export function useOpponents() {
  return useQuery({
    queryKey: [...GAMES_KEY, 'opponents'],
    queryFn: async () => {
      const { data, response } = await api.GET('/api/games/opponents/')
      if (!response.ok || !data) throw new Error(`GET /api/games/opponents/ answered ${response.status}`)
      return data
    },
    retry: false,
  })
}

/** Live invites addressed to me. Polled while the Games page is on screen. */
export function usePendingInvites() {
  const interval = useVisibleInterval(5000)
  return useQuery({
    queryKey: [...GAMES_KEY, 'pending'],
    queryFn: async () => {
      const { data, response } = await api.GET('/api/games/matches/pending/')
      if (!response.ok || !data) throw new Error(`GET /api/games/matches/pending/ answered ${response.status}`)
      return data
    },
    retry: false,
    refetchInterval: interval,
  })
}

/** A match and the engine's clock offset (engine now minus this device's now)
 * measured when it answered. */
export interface MatchRead {
  match: Match
  offsetMs: number
}

export function matchKey(id: string) {
  return [...GAMES_KEY, 'match', id] as const
}

function withOffset(match: Match): MatchRead {
  return { match, offsetMs: Date.parse(match.server_now) - Date.now() }
}

/** `GET /api/games/matches/<id>/`, every second until the match closes. A 404
 * is its own state (the match is gone), never an empty match. */
export function useMatch(id: string) {
  return useQuery({
    queryKey: matchKey(id),
    queryFn: async (): Promise<MatchRead | null> => {
      const { data, response } = await api.GET('/api/games/matches/{match_id}/', {
        params: { path: { match_id: id } },
      })
      if (response.status === 404) return null
      if (!response.ok || !data) throw new Error(`GET /api/games/matches/${id}/ answered ${response.status}`)
      return withOffset(data)
    },
    retry: false,
    refetchInterval: (query) => {
      const read = query.state.data
      if (read === null) return false
      if (read && CLOSED_STATUSES.includes(read.match.status)) return false
      return MATCH_POLL_MS
    },
  })
}

async function write<T>(
  failed: string,
  call: () => Promise<{ data?: T; error?: unknown; response: Response }>,
): Promise<T> {
  let result: Awaited<ReturnType<typeof call>>
  try {
    result = await call()
  } catch {
    throw new WriteRefused(`Could not reach the engine. ${failed}`)
  }
  if (!result.response.ok || result.data === undefined) {
    throw new WriteRefused(refusalMessage('changed', result.response.status, result.error))
  }
  return result.data
}

/** The sentence the invite came back with, kept for the match screen to show. */
export function inviteNoticeKey(id: string) {
  return [...GAMES_KEY, 'invite-notice', id] as const
}

export function useInvite() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (opponentUserId: string) =>
      write<InviteResponse>('No invite was made.', () =>
        api.POST('/api/games/matches/', { body: { opponent_user_id: opponentUserId } }),
      ),
    onSuccess: (made) => {
      queryClient.setQueryData(inviteNoticeKey(made.match.id), made.detail)
      queryClient.setQueryData(matchKey(made.match.id), withOffset(made.match))
    },
  })
}

type Transition = 'accept' | 'decline' | 'cancel'

const NOT_DONE: Record<Transition, string> = {
  accept: 'Nothing was accepted.',
  decline: 'Nothing was declined.',
  cancel: 'Nothing was cancelled.',
}

export function useTransition(id: string, verb: Transition) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async () => {
      const params = { params: { path: { match_id: id } } }
      const call =
        verb === 'accept'
          ? () => api.POST('/api/games/matches/{match_id}/accept/', params)
          : verb === 'decline'
            ? () => api.POST('/api/games/matches/{match_id}/decline/', params)
            : () => api.POST('/api/games/matches/{match_id}/cancel/', params)
      return write<Match>(NOT_DONE[verb], call)
    },
    onSuccess: (match) => queryClient.setQueryData(matchKey(id), withOffset(match)),
    onSettled: () => queryClient.invalidateQueries({ queryKey: matchKey(id) }),
  })
}

/** One progress post. Resolves with the match as the engine now holds it. */
export async function postProgress(id: string, progress: Progress): Promise<MatchRead> {
  const match = await write<Match>('Your score was not sent.', () =>
    api.POST('/api/games/matches/{match_id}/progress/', {
      params: { path: { match_id: id } },
      body: progress,
    }),
  )
  return withOffset(match)
}
