import { createContext, useContext } from 'react'

import type { Occurrence } from '@/lib/recurrence'

/**
 * What a screen can ask the event sheet to do, without owning it.
 *
 * The sheet is mounted ONCE, in the signed-in shell (`event-sheet-host.tsx`),
 * so the "+" on Home, a row on the agenda and an empty slot on the week view all
 * open the same component rather than three copies that drift. A screen holds
 * only this: ask for a blank event on some day (and, from the week view, at some
 * time), or ask to edit one occurrence.
 */
export type SheetRequest =
  | {
      kind: 'create'
      /** Viewer-local epoch day to prefill; today when absent. */
      day?: number
      /** Minutes after local midnight to prefill; the next whole hour when absent. */
      startMinutes?: number
    }
  | { kind: 'edit'; occurrence: Occurrence }

export interface EventSheetControl {
  open: (request: SheetRequest) => void
}

export const EventSheetContext = createContext<EventSheetControl | null>(null)

/** `null` outside the shell (a component rendered alone): callers then render
 * their rows read-only rather than assume a sheet exists to open. */
export function useEventSheet(): EventSheetControl | null {
  return useContext(EventSheetContext)
}
