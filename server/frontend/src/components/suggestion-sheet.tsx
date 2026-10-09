import { Dialog as DialogPrimitive } from 'radix-ui'
import { useState } from 'react'

import { useDeleteEvent, useUpdateEvent } from '@/api/mutations'
import { SUGGESTION_TONE, SuggestionMark } from '@/components/suggestion-mark'
import { Button } from '@/components/ui/button'
import { Dialog, DialogDescription, DialogOverlay, DialogPortal, DialogTitle } from '@/components/ui/dialog'
import { ErrorSentence } from '@/components/workbench/page'
import { dateForEpochDay } from '@/lib/day'
import type { Occurrence } from '@/lib/recurrence'
import { useSurface } from '@/lib/surface'
import { cn } from '@/lib/utils'
import { firstLink, suggestionMeta } from '@/lib/suggestion'
import { timeLabel } from '@/components/event-row'

/**
 * What opens when a suggestion is pressed (ADR 0035: exactly the two things the
 * engine's MCP surface lets the household do with one).
 *
 * - "Add to my plans" turns it into a normal event (`kind` becomes "event"; time,
 *   place and notes stay). Until then it is not the household's plan and nothing
 *   counts it.
 * - "Not interested" deletes it.
 *
 * Neither is optimistic. A refusal keeps the sheet open and says, in the engine's
 * words, what did NOT happen.
 */
export function SuggestionSheet({ occurrence, onClose }: { occurrence: Occurrence; onClose: () => void }) {
  const surface = useSurface()
  const { event } = occurrence
  const update = useUpdateEvent()
  const remove = useDeleteEvent()
  const [busy, setBusy] = useState<null | 'add' | 'drop'>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const meta = suggestionMeta(event)
  // Structured fields win; notes are only searched when there are none.
  const link = meta ? meta.url : firstLink(event.notes)
  const day = dateForEpochDay(occurrence.day).toLocaleDateString(undefined, {
    weekday: 'long',
    month: 'short',
    day: 'numeric',
  })

  async function run(which: 'add' | 'drop', write: () => Promise<unknown>) {
    setBusy(which)
    setProblem(null)
    try {
      await write()
      onClose()
    } catch (error) {
      setProblem(error instanceof Error ? error.message : 'Nothing was changed.')
      setBusy(null)
    }
  }

  return (
    <Dialog open onOpenChange={(open) => !open && busy === null && onClose()}>
      <DialogPortal>
        <DialogOverlay />
        <DialogPrimitive.Content
          data-slot="suggestion-sheet"
          data-surface={surface}
          className={cn(
            'fixed z-50 flex flex-col gap-4 overflow-y-auto bg-popover text-sm text-popover-foreground shadow-lg outline-none duration-200 data-open:animate-in data-closed:animate-out',
            surface === 'workbench'
              ? 'inset-y-0 right-0 w-[440px] max-w-full rounded-l-sheet px-7 pt-7 pb-7 data-open:slide-in-from-right data-closed:slide-out-to-right'
              : 'inset-x-0 bottom-0 max-h-[92dvh] rounded-t-sheet px-5 pt-3 pb-[calc(1.25rem+env(safe-area-inset-bottom))] data-open:slide-in-from-bottom data-closed:slide-out-to-bottom',
          )}
        >
          <SuggestionMark className="self-start" />
          <DialogTitle className="text-[1.375rem] leading-tight font-medium">{event.title}</DialogTitle>
          <DialogDescription className="text-[0.9375rem] text-muted-foreground">
            A suggestion, not a plan. It is not on your list until you add it.
          </DialogDescription>

          <div className={cn('flex flex-col gap-1 rounded-card p-4 text-[0.9375rem]', SUGGESTION_TONE)}>
            <span>
              {day}, {timeLabel(occurrence)}
            </span>
            {meta?.venue && <span className="font-medium">{meta.venue}</span>}
            {meta?.city && <span>{meta.city}</span>}
            {meta?.price && <span>Price: {meta.price}</span>}
            {event.location && <span>{event.location}</span>}
            {event.notes && <span className="whitespace-pre-wrap">{event.notes}</span>}
            {link && (
              <a href={link} target="_blank" rel="noopener noreferrer" className="font-medium underline break-all">
                Open the source page
              </a>
            )}
          </div>

          {problem && <ErrorSentence>{problem}</ErrorSentence>}

          <div className="flex flex-wrap items-center justify-between gap-2">
            <Button type="button" variant="ghost" size="lg" disabled={busy !== null} onClick={onClose}>
              Close
            </Button>
            <div className="flex flex-wrap items-center gap-2">
              <Button
                type="button"
                variant="secondary"
                size="lg"
                disabled={busy !== null}
                onClick={() => void run('drop', () => remove.mutateAsync(event.id))}
              >
                {busy === 'drop' ? 'Removing' : 'Not interested'}
              </Button>
              <Button
                type="button"
                size="lg"
                disabled={busy !== null}
                onClick={() => void run('add', () => update.mutateAsync({ id: event.id, fields: { kind: 'event' } }))}
              >
                {busy === 'add' ? 'Adding' : 'Add to my plans'}
              </Button>
            </div>
          </div>
        </DialogPrimitive.Content>
      </DialogPortal>
    </Dialog>
  )
}
