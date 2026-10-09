import { ExternalLink, MoreHorizontal } from 'lucide-react'

import { useEventSheet } from '@/components/event-sheet-context'
import { SUGGESTION_TONE, SuggestionMark } from '@/components/suggestion-mark'
import { SuggestionPinToggle, SuggestionPinnedLine } from '@/components/suggestion-pin'
import { TaskCheck } from '@/components/task-check'
import { Button } from '@/components/ui/button'
import { VisibilityMark } from '@/components/visibility-mark'
import { canvasLine, canvasMetaOf } from '@/lib/canvas'
import { splitCourse } from '@/lib/horizon'
import type { Occurrence } from '@/lib/recurrence'
import { isSuggestion, suggestionMeta } from '@/lib/suggestion'
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
 *
 * A suggestion with an http(s) page is the one exception to "opens the sheet":
 * its body is a link to that page in a new tab, and the sheet (Add to my plans,
 * Not interested) sits behind its own "..." button. Without such a page it opens
 * the sheet like any other row. Every suggestion also says who wants to go and
 * carries the signed-in member's own "I want to go" toggle, both outside the
 * link.
 */
export function EventRow({
  occurrence,
  showCourse = true,
  detail,
}: {
  occurrence: Occurrence
  showCourse?: boolean
  /** For a TASK: a line under the title that stands in for the time column on the
   * right. A narrow screen has no room for a title, a time and a mark side by
   * side, and an overdue row needs the DAY it was due more than the clock. */
  detail?: string
}) {
  const { event } = occurrence
  const sheet = useEventSheet()
  const { course, label } = splitCourse(event.title)
  const canvasMeta = canvasMetaOf(event.structured_meta)
  const canvas = canvasMeta ? canvasLine(event.done ?? false, canvasMeta) : ''
  const visibility = visibilityOf(event)
  const isTask = event.kind === 'task'
  const suggestion = isSuggestion(event)
  const tinted = !isTask && !suggestion && visibility === 'shared'
  const editable = sheet !== null && !isCanvasRow(occurrence)
  // A suggestion with an http(s) page (structured_meta.url, already guarded in
  // suggestionMeta): the row body becomes a real link to it (Kevin, 2026-10-09),
  // and the sheet with its two actions moves behind a separate "..." button.
  const pageUrl = suggestion ? (suggestionMeta(event)?.url ?? null) : null

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
      {suggestion && suggestionMeta(event)?.price && (
        <span className="block text-[0.8125rem] opacity-75">Price: {suggestionMeta(event)?.price}</span>
      )}
      {event.location && !isTask && (
        <span className="block text-[0.8125rem] opacity-75">{event.location}</span>
      )}
      {occurrence.recurring && <span className="ml-2 text-[0.8125rem] opacity-75">Repeats</span>}
      {isTask && detail !== undefined && (
        <span className="block text-[0.8125rem] text-muted-foreground tabular-nums">{detail}</span>
      )}
      {canvas && <span className="block text-[0.8125rem] text-muted-foreground">{canvas}</span>}
      {pageUrl && (
        <span className="mt-0.5 flex items-center gap-1 text-[0.8125rem] font-medium underline">
          <ExternalLink className="size-3.5" aria-hidden="true" />
          Open event page
        </span>
      )}
    </>
  )

  const mainClass = cn(
    'min-w-0 rounded-md text-left outline-none focus-visible:outline-2 focus-visible:outline-primary',
    suggestion ? 'w-full' : 'flex-1',
  )
  const main = pageUrl ? (
    <a href={pageUrl} target="_blank" rel="noopener noreferrer" className={mainClass} aria-label={`Open event page for ${label}`}>
      {body}
    </a>
  ) : editable ? (
    <button
      type="button"
      className={mainClass}
      aria-label={`Edit ${label}`}
      onClick={() => sheet.open({ kind: 'edit', occurrence })}
    >
      {body}
    </button>
  ) : (
    <div className={cn('min-w-0', suggestion ? 'w-full' : 'flex-1')}>{body}</div>
  )

  return (
    <li
      className={cn(
        'flex min-h-11 items-start gap-3 rounded-[1.125rem] py-2 pr-2.5 pl-4',
        suggestion ? SUGGESTION_TONE : tinted ? 'bg-shared text-shared-foreground' : 'bg-surface-2',
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
      {suggestion ? (
        // The pin (2026-10-09) sits OUTSIDE the link and the sheet button, so
        // pressing it never opens the event page or the sheet.
        <div className="flex min-w-0 flex-1 flex-col items-start gap-1.5">
          {main}
          <SuggestionPinnedLine event={event} />
          <SuggestionPinToggle event={event} />
        </div>
      ) : (
        main
      )}
      {isTask && detail === undefined && (
        <span className="mt-0.5 shrink-0 text-[0.8125rem] tabular-nums text-muted-foreground">
          {timeLabel(occurrence)}
        </span>
      )}
      {suggestion ? (
        <SuggestionMark className="mt-0.5" />
      ) : (
        <VisibilityMark visibility={visibility} onTint={tinted} className="mt-0.5" />
      )}
      {pageUrl && editable && (
        <Button
          type="button"
          variant="ghost"
          size="icon-sm"
          className="-my-1 shrink-0"
          aria-label={`Add or dismiss ${label}`}
          title="Add to my plans, or Not interested"
          onClick={() => sheet.open({ kind: 'edit', occurrence })}
        >
          <MoreHorizontal aria-hidden="true" />
        </Button>
      )}
    </li>
  )
}
