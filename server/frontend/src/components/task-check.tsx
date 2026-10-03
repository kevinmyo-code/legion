import { useMutation, useQueryClient } from '@tanstack/react-query'

import { api } from '@/api/client'
import { CHANGES_KEY } from '@/api/queries'
import type { Event } from '@/api/types'
import { Checkbox } from '@/components/ui/checkbox'
import { splitCourse } from '@/lib/horizon'
import { cn } from '@/lib/utils'

/**
 * The done checkbox of a task, and the one write behind it (`PATCH done`).
 * Shared by the agenda rows, the week lane and the month cells, so a task is
 * ticked the same way wherever it appears and a failure says so the same way.
 *
 * Canvas never ticks `done` (that stays the household's), so this is the only
 * thing that moves it. Not optimistic here: the row is a deadline, and a box
 * that ticked and silently un-ticked would read as "done" for a moment it was
 * not. The error is shown beside the box.
 */
export function useToggleDone(event: Pick<Event, 'id'>) {
  const queryClient = useQueryClient()
  return useMutation({
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
}

export function TaskCheck({ event, className }: { event: Event; className?: string }) {
  const toggle = useToggleDone(event)
  const { label } = splitCourse(event.title)
  return (
    <>
      <Checkbox
        className={cn(className)}
        checked={event.done}
        disabled={toggle.isPending}
        onCheckedChange={(checked) => toggle.mutate(checked === true)}
        aria-label={`Mark "${label}" ${event.done ? 'not done' : 'done'}`}
      />
      {toggle.isError && (
        <span role="alert" className="text-[0.8125rem] text-destructive">
          Could not save. {toggle.error.message}
        </span>
      )}
    </>
  )
}
