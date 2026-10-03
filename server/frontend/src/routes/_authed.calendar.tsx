import { createFileRoute } from '@tanstack/react-router'

import { useChanges } from '@/api/queries'
import { Skeleton } from '@/components/ui/skeleton'
import { CalendarFamily } from '@/screens/calendar-family'
import { CalendarWorkbench } from '@/screens/calendar-workbench'
import { useSurface } from '@/lib/surface'

export const Route = createFileRoute('/_authed/calendar')({
  component: Calendar,
})

/**
 * `/calendar`: a family variant (the month and a day's agenda) or a workbench
 * variant (week and month), picked by viewport. Both read the one changes query
 * Home and Lists read, so a tick made anywhere shows on every screen, and both
 * leave the three states a read can be in as three different sentences:
 * still asking, could not reach the engine (nothing cached), and stale (a failed
 * refresh with older data still on screen, the `Freshness` line).
 */
function Calendar() {
  const changes = useChanges(true)
  const surface = useSurface()

  if (changes.isPending) {
    return (
      <div className="flex flex-col gap-3">
        <Skeleton className="h-8 w-40" />
        <Skeleton className="h-72 w-full" />
      </div>
    )
  }

  // A failed BACKGROUND refetch also sets `isError` with the last good data
  // attached, so the unreachable banner is for "nothing to show", never `isError`
  // alone; stale data is the `Freshness` line.
  if (changes.isError && changes.data === undefined) {
    return (
      <div className="mx-auto max-w-lg rounded-card bg-destructive-container p-4 text-sm text-destructive-container-foreground">
        Could not reach the engine, so this is not the real calendar. {changes.error.message}
      </div>
    )
  }

  const freshness = {
    updatedAt: changes.dataUpdatedAt,
    isFetching: changes.isFetching,
    failureCount: changes.failureCount,
    error: changes.error,
  }
  return surface === 'workbench' ? (
    <CalendarWorkbench changes={changes.data} freshness={freshness} />
  ) : (
    <CalendarFamily changes={changes.data} freshness={freshness} />
  )
}
