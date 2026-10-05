import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '@/api/client'
import { CHANGES_POLL_MS, useVisibleInterval } from '@/api/refetch'
import { WriteRefused, refusalMessage, type WriteVerb } from '@/api/refusal'
import { wire } from '@/api/synced'
import type { Purchase } from '@/api/types'
import type { Visibility } from '@/lib/visibility'

/**
 * The bought log's reads and writes (purchase-log 07), over the generated
 * client. One key prefix, so a write or a Groceries tick refreshes every screen
 * that shows an entry.
 */
export const PURCHASES_KEY = ['purchases'] as const

/** Entries a Groceries list compares its lines against, and the most the
 * workbench table shows. The engine's own ceiling is 500. */
export const PURCHASES_LIMIT = 500

/** The newest entries and, with `q`, only those that match it (the engine's own
 * matcher: every word of `q`, plurals folded). Newest first. */
export function usePurchases({
  q,
  limit,
  source,
  enabled = true,
}: {
  q?: string
  limit?: number
  source?: 'GROCERIES_BACKFILL' | 'GROCERIES_TICK' | 'MANUAL'
  enabled?: boolean
}) {
  const interval = useVisibleInterval(CHANGES_POLL_MS)
  const query = (q ?? '').trim()
  return useQuery({
    queryKey: [...PURCHASES_KEY, 'list', query, limit ?? 'default', source ?? 'all'],
    queryFn: async () => {
      const { data, error, response } = await api.GET('/api/purchases/', {
        params: {
          query: {
            ...(query ? { q: query } : {}),
            ...(limit ? { limit } : {}),
            ...(source ? { source } : {}),
          },
        },
      })
      if (error || !data) throw new Error(`GET /api/purchases answered ${response.status}`)
      return data
    },
    enabled,
    retry: false,
    refetchInterval: interval,
    refetchOnWindowFocus: 'always',
  })
}

/** What the bought log says when it cannot be reached: a read that failed is not
 * an empty log (CLAUDE.md section 1). */
export const CANT_READ_LOG =
  "Can't reach the bought log right now. This is not an empty log; it just can't be read."

/**
 * One write to the log. Resolves with the entry only on a 2xx. A dead network
 * and a refusal both reject with a sentence that opens with what did NOT happen,
 * so a screen can show it verbatim and keep what the person typed.
 */
async function boughtWrite<T>(
  verb: WriteVerb,
  call: () => Promise<{ data?: T; error?: unknown; response: Response }>,
): Promise<T | undefined> {
  let result: Awaited<ReturnType<typeof call>>
  try {
    result = await call()
  } catch {
    throw new WriteRefused(`Can't reach the bought log right now. Nothing was ${verb}.`)
  }
  if (!result.response.ok) {
    throw new WriteRefused(refusalMessage(verb, result.response.status, result.error))
  }
  return result.data
}

export interface PurchaseFields {
  item: string
  bought_on: number
  store: string | null
  price_cents: number | null
  quantity_note: string | null
  visibility: Visibility
}

/** A new entry. The `sync_id` is made once per attempt-set by the form, so a
 * retry after a lost answer finds the entry it already made (the engine answers
 * 200 with it) rather than logging a second. */
export function useLogPurchase() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ fields, syncId }: { fields: PurchaseFields; syncId: string }) => {
      const entry = await boughtWrite<Purchase>('logged', () =>
        api.POST('/api/purchases/', { body: wire<Purchase>({ ...fields, sync_id: syncId }) }),
      )
      return entry
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: PURCHASES_KEY }),
  })
}

export function useUpdatePurchase() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ id, fields }: { id: string; fields: PurchaseFields }) =>
      boughtWrite<Purchase>('changed', () =>
        api.PATCH('/api/purchases/{purchase_id}', {
          params: { path: { purchase_id: id } },
          body: fields,
        }),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: PURCHASES_KEY }),
  })
}

export function useDeletePurchase() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (id: string) => {
      await boughtWrite<never>('deleted', () =>
        api.DELETE('/api/purchases/{purchase_id}', { params: { path: { purchase_id: id } } }),
      )
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: PURCHASES_KEY }),
  })
}
