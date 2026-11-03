import { useQuery } from '@tanstack/react-query'

import { api } from '@/api/client'
import { sentenceFromBody } from '@/api/refusal'
import type { components } from '@/api/schema'

/**
 * The Claude session's tasks (claude-session ticket 09, ADR 0067), read-only on the web desk.
 *
 * Every row is the signed-in household's (`server/claude_session/views.py`). A delivered page is
 * listed under `artifacts` with a signed link the engine mints on each read and that lives about
 * ten minutes, which is why the list is re-read every minute and on focus rather than kept.
 */

export type ClaudeTask = components['schemas']['ClaudeTaskRow']
export type ClaudeArtifact = components['schemas']['ClaudeArtifact']

export const CLAUDE_KEY = ['claude', 'tasks'] as const

export const CANT_READ_CLAUDE =
  "Can't reach the engine, so the Claude session's tasks can't be listed. Nothing is known about them from here."

/** The engine answered, with a refusal in its own words. Anything else thrown is "can't reach". */
export class ClaudeReadRefused extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ClaudeReadRefused'
  }
}

export function useClaudeTasks() {
  return useQuery({
    queryKey: CLAUDE_KEY,
    queryFn: async (): Promise<ClaudeTask[]> => {
      const { data, error, response } = await api.GET('/api/claude/tasks/', { params: { query: { limit: 50 } } })
      if (!response.ok || !data) {
        throw new ClaudeReadRefused(sentenceFromBody(error) ?? `GET /api/claude/tasks/ answered ${response.status}.`)
      }
      return data
    },
    retry: false,
    // A page link is good for ten minutes; re-read well inside that.
    refetchInterval: 60_000,
    refetchOnWindowFocus: true,
  })
}
