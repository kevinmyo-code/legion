import { useQuery } from '@tanstack/react-query'

import { api } from '@/api/client'

/**
 * `GET /api/auth/me` as one hook, shared by the root shell (household name,
 * sign-out) and any screen that needs to know who is signed in.
 *
 * Three states, not two - `me.isPending`, a real 401/403 ("not signed in"),
 * and a transport failure are different sentences (CLAUDE.md section 1:
 * unreadable and empty are different sentences), so this returns a tagged
 * union rather than collapsing "no session" and "could not ask" into one
 * falsy value.
 */
export function useMe() {
  return useQuery({
    queryKey: ['auth', 'me'],
    queryFn: async () => {
      const { data, error, response } = await api.GET('/api/auth/me')
      if (response.status === 401 || response.status === 403) {
        return { signedIn: false as const }
      }
      if (error || !data) {
        throw new Error(`GET /api/auth/me answered ${response.status}`)
      }
      return { signedIn: true as const, me: data }
    },
    retry: false,
  })
}

/** `GET /api/households/me` - the household name for the shell header. Not
 * fetched at all while signed out, so a 403 here never gets read as "no
 * household" for someone who simply has not logged in yet. */
export function useHousehold(enabled: boolean) {
  return useQuery({
    queryKey: ['households', 'me'],
    queryFn: async () => {
      const { data, error, response } = await api.GET('/api/households/me')
      if (error || !data) {
        throw new Error(`GET /api/households/me answered ${response.status}`)
      }
      return data
    },
    enabled,
    retry: false,
  })
}

/**
 * `GET /api/changes?aspects=events,checklists` - one unpaged pull of both
 * tables this ticket's screens need. There is no day-range or "active"
 * filter on either route yet (web-and-households ticket 11, report
 * endpoints, is not built), so Today and Lists both read the whole table and
 * filter locally; at this household's scale (CLAUDE.md: a personal-life ERP
 * for a handful of people, not a fleet) that is a request of a few hundred
 * rows, not the tens of thousands `fleet.obd_samples` has to page around.
 *
 * Shared by both screens under one query key so a tick made on Lists shows
 * up on Today without a second round trip - both call
 * `queryClient.invalidateQueries({ queryKey: CHANGES_KEY })` after a write.
 */
export const CHANGES_KEY = ['changes', 'events-and-checklists'] as const

export function useChanges(enabled: boolean) {
  return useQuery({
    queryKey: CHANGES_KEY,
    queryFn: async () => {
      const { data, error, response } = await api.GET('/api/changes', {
        params: { query: { aspects: ['events', 'checklists'] } },
      })
      if (error || !data) {
        throw new Error(`GET /api/changes answered ${response.status}`)
      }
      return data
    },
    enabled,
    retry: false,
  })
}
