import { useMemo, useState, type ReactNode } from 'react'

import { EventSheet } from '@/components/event-sheet'
import {
  EventSheetContext,
  type EventSheetControl,
  type SheetRequest,
} from '@/components/event-sheet-context'

/**
 * Mounts the event sheet once for the whole signed-in shell and hands every
 * screen below it the one control for opening it (`useEventSheet`).
 *
 * The sheet is keyed by the request, so opening it for a different occurrence
 * starts a fresh form rather than carrying the last one's half-typed fields.
 */
export function EventSheetHost({ children }: { children: ReactNode }) {
  const [request, setRequest] = useState<SheetRequest | null>(null)
  const control = useMemo<EventSheetControl>(() => ({ open: setRequest }), [])

  const key =
    request === null
      ? ''
      : request.kind === 'edit'
        ? `edit:${request.occurrence.event.id}:${request.occurrence.date}`
        : `create:${request.day ?? 'today'}:${request.startMinutes ?? 'next'}`

  return (
    <EventSheetContext.Provider value={control}>
      {children}
      {request !== null && <EventSheet key={key} request={request} onClose={() => setRequest(null)} />}
    </EventSheetContext.Provider>
  )
}
