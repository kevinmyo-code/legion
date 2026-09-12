/**
 * The third sentence.
 *
 * CLAUDE.md §1 separates two states that look identical on screen and mean
 * opposite things: **empty** (the read worked, there is nothing) and
 * **unreadable** (the read failed). Both are built. There is a third, and it was
 * missing until 2026-09-12:
 *
 * **Stale** - the refetch FAILED but an earlier one succeeded, so the screen is
 * showing older data. TanStack Query serves the last good response in that case
 * and `isError` stays false while cached data exists, so a day view can be hours
 * old and say nothing at all.
 *
 * That matters here more than it would elsewhere. On the same day this was
 * written, `/api/changes` was found returning `events: []` with a cheerful 200
 * for what may have been weeks, and the screen rendered "Nothing on the calendar
 * today" every single day while the database held 467 rows. **Confident and
 * wrong, with nothing on screen able to say otherwise** - and a silently stale
 * day is the same failure wearing a different hat.
 *
 * So this component never decorates. It states, in words, when the data was last
 * read, and says plainly when the last attempt to refresh it failed.
 */

interface FreshnessProps {
  /** `dataUpdatedAt` from the query - epoch ms of the last SUCCESSFUL fetch. */
  updatedAt: number
  /** True while a refetch is in flight. */
  isFetching: boolean
  /** Consecutive failures since the last success. Zero means the last attempt
   * worked; anything above zero with data still on screen is the stale case. */
  failureCount: number
  /** Whatever the failing refetch said, so the sentence can name a cause. */
  error?: Error | null
}

/** "2 minutes ago" and friends, in whole units and never in fractions - a
 * relative time that says "0.4 hours" is harder to read than one that says
 * "24 minutes". */
export function relativeTime(from: number, now: number = Date.now()): string {
  const seconds = Math.max(0, Math.round((now - from) / 1000))
  if (seconds < 45) return 'just now'
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return `${minutes} minute${minutes === 1 ? '' : 's'} ago`
  const hours = Math.round(minutes / 60)
  if (hours < 24) return `${hours} hour${hours === 1 ? '' : 's'} ago`
  const days = Math.round(hours / 24)
  return `${days} day${days === 1 ? '' : 's'} ago`
}

export function Freshness({ updatedAt, isFetching, failureCount, error }: FreshnessProps) {
  // The stale case, and it is the only one that gets emphasis. Data is on
  // screen, it is real, and it is not current - the reader has to be able to
  // tell that without hunting for it.
  if (failureCount > 0) {
    return (
      <p className="rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-xs text-muted-foreground">
        <span className="font-medium text-foreground">
          This is what was on screen {relativeTime(updatedAt)} - not what is there now.
        </span>{' '}
        The last attempt to refresh it did not reach the engine
        {error?.message ? ` (${error.message})` : ''}. Nothing below has been checked since.
      </p>
    )
  }

  if (isFetching) {
    return <p className="text-xs text-muted-foreground">Checking for changes...</p>
  }

  return (
    <p className="text-xs text-muted-foreground">Last read {relativeTime(updatedAt)}.</p>
  )
}
