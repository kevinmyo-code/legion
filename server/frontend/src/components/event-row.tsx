import { useMutation, useQueryClient } from '@tanstack/react-query'

import { api } from '@/api/client'
import { CHANGES_KEY } from '@/api/queries'
import type { Event } from '@/api/types'
import { Checkbox } from '@/components/ui/checkbox'
import { splitCourse } from '@/lib/horizon'

/**
 * One event or task row. Pulled out of `_authed.index.tsx` so the month
 * calendar's day view (ticket 01) can show the exact same row Today does,
 * rather than a second implementation three inches away - ADR 0035's
 * one-controller posture applied to a component instead of a write path.
 */
export function EventRow({ event, showCourse = true }: { event: Event; showCourse?: boolean }) {
  const queryClient = useQueryClient()
  const toggleDone = useMutation({
    mutationFn: async (done: boolean) => {
      const { error, response } = await api.PATCH('/api/events/{id}', {
        params: { path: { id: event.id } },
        body: { done },
      })
      if (error) {
        throw new Error(`PATCH /api/events/${event.id} answered ${response.status}`)
      }
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: CHANGES_KEY }),
  })

  const { course, label } = splitCourse(event.title)
  const time = event.all_day
    ? 'All day'
    : event.starts_at
      ? new Date(event.starts_at).toLocaleTimeString(undefined, {
          hour: 'numeric',
          minute: '2-digit',
        })
      : ''

  return (
    <li className="flex items-start gap-3 py-2">
      {event.kind === 'task' ? (
        <Checkbox
          className="mt-0.5"
          checked={event.done}
          disabled={toggleDone.isPending}
          onCheckedChange={(checked) => toggleDone.mutate(checked === true)}
          aria-label={`Mark "${label}" ${event.done ? 'not done' : 'done'}`}
        />
      ) : (
        // An event passes whether or not you engage with it (one-today ticket
        // 08). No checkbox, and the gap where one would be is deliberate - it is
        // what makes the two kinds distinguishable without reading the row.
        <span className="mt-0.5 w-4 shrink-0" aria-hidden="true" />
      )}
      <div className="min-w-0 flex-1">
        <span
          className={
            event.kind === 'task' && event.done
              ? 'text-sm text-muted-foreground line-through'
              : 'text-sm'
          }
        >
          {label}
        </span>
        {showCourse && course && (
          <span className="ml-2 text-xs text-muted-foreground">{course}</span>
        )}
      </div>
      <span className="shrink-0 text-xs tabular-nums text-muted-foreground">{time}</span>
      {toggleDone.isError && (
        <span className="text-xs text-destructive">Could not save. {toggleDone.error.message}</span>
      )}
    </li>
  )
}
