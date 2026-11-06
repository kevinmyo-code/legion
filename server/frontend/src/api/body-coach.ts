import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '@/api/client'
import { WriteRefused, refusalMessage, type WriteVerb } from '@/api/refusal'
import type { components } from '@/api/schema'

/**
 * The body coach (fat-loss tickets 03 to 09): `/api/body/coach/*`, over the
 * generated client. One key prefix, so any write refreshes every coach figure,
 * and an accepted proposal also refreshes the meal targets the Meals tab reads.
 *
 * Every figure here is computed on the engine (the same code the phone and the
 * engine tools read); the web only shows it, with the engine's own words.
 */

export type CoachProfile = components['schemas']['Profile']
export type CoachProfileUpdate = components['schemas']['ProfileUpdate']
export type Energy = components['schemas']['Energy']
export type Trend = components['schemas']['Trend']
export type Waist = components['schemas']['Waist']
export type SavedMeal = components['schemas']['SavedMeal']
export type SavedMealCreate = components['schemas']['SavedMealCreate']
export type WeeklyLine = components['schemas']['WeeklyLine']

export const COACH_KEY = ['body-coach'] as const

async function read<T>(
  path: string,
  call: () => Promise<{ data?: T; error?: unknown; response: Response }>,
): Promise<T> {
  const { data, error, response } = await call()
  if (error || data === undefined) throw new Error(`GET ${path} answered ${response.status}`)
  return data
}

export function useCoachProfile() {
  return useQuery({
    queryKey: [...COACH_KEY, 'profile'],
    queryFn: () => read('/api/body/coach/profile/', () => api.GET('/api/body/coach/profile/')),
    retry: false,
  })
}

export function useEnergy() {
  return useQuery({
    queryKey: [...COACH_KEY, 'energy'],
    queryFn: () => read('/api/body/coach/energy/', () => api.GET('/api/body/coach/energy/')),
    retry: false,
  })
}

export function useTrend(days = 90) {
  return useQuery({
    queryKey: [...COACH_KEY, 'trend', days],
    queryFn: () =>
      read('/api/body/coach/trend/', () => api.GET('/api/body/coach/trend/', { params: { query: { days } } })),
    retry: false,
  })
}

export function useSavedMeals() {
  return useQuery({
    queryKey: [...COACH_KEY, 'saved-meals'],
    queryFn: () => read('/api/body/coach/saved-meals/', () => api.GET('/api/body/coach/saved-meals/')),
    retry: false,
  })
}

export function useWeeklyLine() {
  return useQuery({
    queryKey: [...COACH_KEY, 'weekly-line'],
    queryFn: () => read('/api/body/coach/weekly-line/', () => api.GET('/api/body/coach/weekly-line/')),
    retry: false,
  })
}

/** A coach write. Resolves only on a 2xx; anything else rejects with a sentence
 * that opens with what did NOT happen, so a screen can show it verbatim. */
async function coachWrite<T>(
  verb: WriteVerb,
  call: () => Promise<{ data?: T; error?: unknown; response: Response }>,
): Promise<T | undefined> {
  let result: Awaited<ReturnType<typeof call>>
  try {
    result = await call()
  } catch {
    throw new WriteRefused(`Can't reach the engine right now. Nothing was ${verb}.`)
  }
  if (!result.response.ok) {
    throw new WriteRefused(refusalMessage(verb, result.response.status, result.error))
  }
  return result.data
}

function useCoachMutation<A, T>(fn: (args: A) => Promise<T>, alsoRefresh: readonly unknown[][] = []) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: fn,
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: COACH_KEY })
      for (const key of alsoRefresh) await queryClient.invalidateQueries({ queryKey: key })
    },
  })
}

export function useSaveProfile() {
  return useCoachMutation((body: CoachProfileUpdate) =>
    coachWrite('saved', () => api.PUT('/api/body/coach/profile/', { body })),
  )
}

export function useLogWaist() {
  return useCoachMutation((body: { waist_in?: number; waist_cm?: number; measured_on?: string }) =>
    coachWrite('saved', () => api.POST('/api/body/coach/waist/', { body })),
  )
}

export function useDeleteWaist() {
  return useCoachMutation((id: string) =>
    coachWrite('deleted', () =>
      api.DELETE('/api/body/coach/waist/{waist_id}/', { params: { path: { waist_id: id } } }),
    ),
  )
}

export function useSaveMeal() {
  return useCoachMutation((body: SavedMealCreate) =>
    coachWrite('saved', () => api.POST('/api/body/coach/saved-meals/', { body })),
  )
}

export function useDeleteSavedMeal() {
  return useCoachMutation((id: string) =>
    coachWrite('deleted', () =>
      api.DELETE('/api/body/coach/saved-meals/{meal_id}/', { params: { path: { meal_id: id } } }),
    ),
  )
}

/** Logging a saved meal writes a `meal_logs` row, so the Meals tab's rows refresh too. */
export function useLogSavedMeal() {
  return useCoachMutation(
    (id: string) =>
      coachWrite('logged', () =>
        api.POST('/api/body/coach/saved-meals/{meal_id}/log/', { params: { path: { meal_id: id } } }),
      ),
    [['rows', 'body/meal_logs']],
  )
}

/** Accepting writes a `meal_targets` row, so the Meals tab's targets refresh too. */
export function useDecideProposal() {
  return useCoachMutation(
    ({ id, accept }: { id: string; accept: boolean }): Promise<{ words: string } | undefined> =>
      accept
        ? coachWrite('changed', () =>
            api.POST('/api/body/coach/proposals/{proposal_id}/accept/', {
              params: { path: { proposal_id: id } },
            }),
          )
        : coachWrite('changed', () =>
            api.POST('/api/body/coach/proposals/{proposal_id}/dismiss/', {
              params: { path: { proposal_id: id } },
            }),
          ),
    [['rows', 'body/meal_targets']],
  )
}
