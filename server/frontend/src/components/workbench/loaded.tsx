import type { UseQueryResult } from '@tanstack/react-query'
import type { ReactNode } from 'react'

import { Freshness } from '@/components/freshness'
import { EmptySentence, ErrorSentence } from '@/components/workbench/page'
import { Skeleton } from '@/components/ui/skeleton'

/**
 * One place that says which of FOUR states a panel is in, in four different
 * ways (CLAUDE.md section 1: unreadable and empty are different sentences, and
 * so is stale - `components/freshness.tsx`):
 *
 * - **loading**: skeleton rows. Not a sentence about the data at all.
 * - **could not reach**: the read failed and there is nothing on screen from an
 *   earlier read. "Could not reach the engine, so this is not the real list of
 *   X." Never "none".
 * - **stale**: an earlier read is on screen and the refresh failed. The
 *   `Freshness` line says how old it is and that nothing was re-checked.
 * - **empty**: the read worked and there is nothing. A sentence the caller
 *   writes, specific to the panel, never a chart with bare axes.
 *
 * `render` only ever sees a COMPLETE list: `useRows` fetches to `next: null`
 * or fails, so a partial list is never handed over to be totalled.
 */
export function Loaded<T>({
  query,
  what,
  empty,
  isEmpty = (data) => Array.isArray(data) && data.length === 0,
  quiet = false,
  render,
}: {
  query: UseQueryResult<T, Error>
  /** What was being read, for the could-not-reach sentence: "receipts". */
  what: string
  /** The empty sentence, specific to this panel. */
  empty: ReactNode
  isEmpty?: (data: T) => boolean
  /** Hide the "Last read" line while everything is fine; stale still shows. */
  quiet?: boolean
  render: (data: T) => ReactNode
}) {
  if (query.isPending) {
    return (
      <div className="flex flex-col gap-2" aria-busy="true" aria-label={`Loading ${what}`}>
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-3/4" />
      </div>
    )
  }

  if (query.data === undefined) {
    return (
      <ErrorSentence>
        Could not reach the engine, so this is not the real list of {what}. {query.error?.message}
      </ErrorSentence>
    )
  }

  const stale = query.failureCount > 0 || (query.isError && query.data !== undefined)

  return (
    <div className="flex flex-col gap-3">
      {(stale || !quiet) && (
        <Freshness
          updatedAt={query.dataUpdatedAt}
          isFetching={query.isFetching}
          failureCount={stale ? Math.max(1, query.failureCount) : 0}
          error={query.error}
        />
      )}
      {isEmpty(query.data) ? <EmptySentence>{empty}</EmptySentence> : render(query.data)}
    </div>
  )
}
