import { useQuery } from '@tanstack/react-query'

import { api } from '@/api/client'
import type { components } from '@/api/schema'

/**
 * `GET /api/device-logs/conversations/`: the caller's own conversation batches,
 * cut to the member's own lines by the engine, newest first, grouped by device (#160). One request,
 * so the page never shows a half-rendered list.
 *
 * Three states, as everywhere: still asking, the data, and could not reach the
 * engine (a thrown `Error`, which the screen words itself). An empty list is an
 * answer ("the engine holds none"), never a failure.
 */
export type ConversationDevice = components['schemas']['ConversationDevice']
export type ConversationBatch = components['schemas']['ConversationBatch']

/** A non-2xx answer, with its status so the screen can tell a refusal from an outage. */
export class ConversationsError extends Error {
  constructor(readonly status: number) {
    super(`GET /api/device-logs/conversations/ answered ${status}`)
  }
}

export const DEFAULT_PER_DEVICE = 10
export const MAX_PER_DEVICE = 30

export function useConversations(limit: number) {
  return useQuery({
    queryKey: ['device-logs', 'conversations', limit],
    queryFn: async () => {
      const { data, response } = await api.GET('/api/device-logs/conversations/', {
        params: { query: { limit } },
      })
      if (!response.ok || !data) {
        throw new ConversationsError(response.status)
      }
      return data.devices
    },
    retry: false,
  })
}
