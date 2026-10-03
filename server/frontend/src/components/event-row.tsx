import { useEventSheet } from '@/components/event-sheet-context'
import { TaskCheck } from '@/components/task-check'
import { VisibilityMark } from '@/components/visibility-mark'
import { canvasLine, canvasMetaOf } from '@/lib/canvas'
import { splitCourse } from '@/lib/horizon'
import type { Occurrence } from '@/lib/recurrence'
import { cn } from '@/lib/utils'
import { visibilityOf } from '@/lib/visibility'

/** Time of day for display, in the viewer's own zone. */
export function timeLabel(occurrence: Pick<Occurrence, 'startsAt' | 'event'>): string {
  if (occurrence.event.all_day) return 'All day'
  return new Date(occurrence.startsAt).toLocaleTimeString(undefined, {
    hour: 'numeric',
    minute: '2-digit',
  })
}

/** A row Canvas owns: the poller rewrites it every half hour, so editing the
 * title or time here would be undone, and the sheet is not offered. Only its
 * `done` flag is the household's own. */
export function isCanvasRow(occurrence: Pick<Occurrence, 'event'>): boolean {
  const { event } = occurrence
  return canvasMetaOf(event.structured_meta) !== null || (event.origin_guid ?? '').startsWith('canvas:')
}

/**
 * One event or task row. Shared by Home, the month calendar's day view, the
 * week agenda and the family day agenda, so every place an occurrence renders
 * says who sees it, in words, and opens the same sheet - ADR 0035's
 * one-controller posture applied to a component instead of a write path.
 *
 * It takes an OCCURRENCE, not an event: one repeating series is many rows, and
 * the sheet needs to know which one was pressed ("just this one" is a date).
 *
 * Shared events sit in the pink container with a "Shared" chip; private ones in
 * the plain tonal row with a lock and "Only you". Tasks are never tinted: they
 * carry a checkbox and the same words, so a day of coursework is a list, not a
 * pink wall.
 */
export function EventRow({
  occurrence,
  showCourse = true,
}: {
  occurrence: Occurrence
  showCourse?: boolean
}) {
  const { event } = occurrence
  const sheet = useEventSheet()
  const { course, label } = splitCourse(event.title)
  const canvasMeta = canvasMetaOf(event.structured_meta)
  const canvas = canvasMeta ? canvasLine(event.done ?? false, canvasMeta) : ''
  const visibility = visibilityOf(event)
  const isTask = event.kind === 'task'
  const tinted = !isTask && visibility === 'shared'
  const editable = sheet !== null && !isCanvasRow(occurrence)

  const body = (
    <>
      <span
        className={
          isTask && event.done
            ? 'text-[0.9375rem] text-muted-foreground line-through'
            : 'text-[0.9375rem] font-medium'
        }
      >
        {label}
      </span>
      {showCourse && course && (
        <span className="ml-2 text-[0.8125rem] text-muted-foreground">{course}</span>
      )}
      {event.location && !isTask && (
        <span className="block text-[0.8125rem] opacity-75">{event.location}</span>
      )}
      {occurrence.recurring && <span className="ml-2 text-[0.8125rem] opacity-75">Repeats</span>}
      {canvas && <span className="block text-[0.8125rem] text-muted-foreground">{canvas}</span>}
    </>
  )

  return (
    <li
      className={cn(
        'flex min-h-11 items-start gap-3 rounded-[1.125rem] py-2 pr-2.5 pl-4',
        tinted ? 'bg-shared text-shared-foreground' : 'bg-surface-2',
      )}
    >
      {isTask ? (
        <TaskCheck event={event} className="mt-1" />
      ) : (
        // An event passes whether or not you engage with it (one-today ticket
        // 08). The time sits where a checkbox would, which is what makes the two
        // kinds distinguishable without reading the row.
        <span className="mt-0.5 w-16 shrink-0 text-[0.8125rem] tabular-nums opacity-80">
          {timeLabel(occurrence)}
        </span>
      )}
      {editable ? (
        <button
          type="button"
          className="min-w-0 flex-1 rounded-md text-left outline-none focus-visible:outline-2 focus-visible:outline-primary"
          aria-label={`Edit ${label}`}
          onClick={() => sheet.open({ kind: 'edit', occurrence })}
        >
          {body}
        </button>
      ) : (
        <div className="min-w-0 flex-1">{body}</div>
      )}
      {isTask && (
        <span className="mt-0.5 shrink-0 text-[0.8125rem] tabular-nums text-muted-foreground">
          {timeLabel(occurrence)}
        </span>
      )}
      <VisibilityMark visibility={visibility} onTint={tinted} className="mt-0.5" />
    </li>
  )
}
