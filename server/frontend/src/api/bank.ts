import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '@/api/client'
import { runWriteData } from '@/api/refusal'

/**
 * The bank connection (Plaid, ADR 0057), through the generated client.
 *
 * Every write is non-optimistic and refuses in words that begin with what did
 * not happen. There is deliberately no disconnect or relink call here: a Plaid
 * connection is one of ten for life, so the screen offers none.
 */

export const BANK_KEY = ['ingest', 'plaid'] as const

export function useBankStatus() {
  return useQuery({
    queryKey: BANK_KEY,
    queryFn: async () => {
      const { data, response } = await api.GET('/api/ingest/plaid')
      if (!response.ok || !data) throw new Error(`GET /api/ingest/plaid answered ${response.status}`)
      return data
    },
    retry: false,
  })
}

/** A short-lived token that opens Plaid Link: `create` for the first connection,
 * `update` for signing in again to the same one. */
export function useLinkToken() {
  return useMutation({
    mutationFn: async (mode: 'create' | 'update') => {
      const data = await runWriteData('started', () =>
        mode === 'create'
          ? api.POST('/api/ingest/plaid/link-token')
          : api.POST('/api/ingest/plaid/update-link-token'),
      )
      if (!data) throw new Error('The engine sent no link token.')
      return data.link_token
    },
  })
}

export function useExchangePublicToken() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (publicToken: string) =>
      runWriteData('connected', () =>
        api.POST('/api/ingest/plaid/exchange', { body: { public_token: publicToken } }),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: BANK_KEY }),
  })
}

export function useSyncBank() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async () => {
      const data = await runWriteData('synced', () => api.POST('/api/ingest/plaid/sync'))
      if (!data) throw new Error('The engine sent no sync result.')
      return data
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: BANK_KEY }),
  })
}
