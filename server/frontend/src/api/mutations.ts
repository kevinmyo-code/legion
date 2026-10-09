import { useMutation, useQueryClient } from '@tanstack/react-query'

import { api } from '@/api/client'
import { CHANGES_KEY } from '@/api/queries'
import { PURCHASES_KEY } from '@/api/purchases'
import { WriteRefused, runWrite, type WriteVerb } from '@/api/refusal'
import { wire } from '@/api/synced'
import type { Changes, ChecklistTick, Event } from '@/api/types'
import type { EventFields } from '@/lib/event-form'
import { occurrenceGuid } from '@/lib/event-form'
import type { Visibility } from '@/lib/visibility'

/**
 * Ticking a checklist item was taking about a second to register on screen
 * (Kevin, 2026-09-16). The checkbox's own state comes from `tickState`,
 * which reads `checklist_ticks` out of the SAME `CHANGES_KEY` cache both
 * `ItemRow` (`/lists`) and `DueItemRow` (Home) only updated by invalidating
 * after the write resolved - so the checkbox sat frozen through two
 * sequential round trips (the POST/DELETE, then a full `GET /api/changes`
 * refetch of every event, checklist, item and tick in the household) before
 * it could move at all.
 *
 * This patches the cache immediately in `onMutate` so the checkbox moves on
 * the click, rolls back to the real cached state in `onError` and surfaces
 * the failure in words (a tick that silently reverts is worse than one that
 * was slow - the user would believe it took), and still lets the server's
 * own truth win once `onSettled` invalidates the query. Shared by both
 * screens for the same reason `CHANGES_KEY` itself is shared: one write
 * path, not two components quietly drifting.
 */
export function useSetChecklistTick() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async ({
      checklistId,
      itemId,
      ticked,
      today,
      dayToClear,
    }: {
      checklistId: string
      itemId: string
      ticked: boolean
      today: number
      dayToClear: number
    }) => {
      if (ticked) {
        const { error, response } = await api.POST(
          '/api/checklists/{checklist_id}/items/{item_id}/tick',
          {
            params: { path: { checklist_id: checklistId, item_id: itemId } },
            body: { day: today, source: 'USER_REPORTED' },
          },
        )
        if (error) throw new Error(`POST tick answered ${response.status}`)
      } else {
        const { error, response } = await api.DELETE(
          '/api/checklists/{checklist_id}/items/{item_id}/tick/{day}',
          {
            params: {
              path: { checklist_id: checklistId, item_id: itemId, day: dayToClear },
              // The caller's own local day. On the Groceries list a tick is a
              // purchase (ADR 0055) and an untick on the tick's own day removes
              // the bought entry; the engine cannot know this household's local
              // day, so it is told (it would otherwise guess the UTC date).
              query: { today },
            },
          },
        )
        if (error) throw new Error(`DELETE tick answered ${response.status}`)
      }
    },
    onMutate: async ({ itemId, ticked, today, dayToClear }) => {
      await queryClient.cancelQueries({ queryKey: CHANGES_KEY })
      const previous = queryClient.getQueryData<Changes>(CHANGES_KEY)
      if (previous) {
        const ticks = previous.checklist_ticks ?? []
        const day = ticked ? today : dayToClear
        const now = new Date().toISOString()
        // The shape `tickState` and every `isLive` filter on this page
        // actually read: `item`/`day`/`deleted_at` are load-bearing,
        // everything else is furniture the server will overwrite on the
        // next real fetch.
        const nextTicks: ChecklistTick[] = ticked
          ? [
              ...ticks,
              {
                id: `optimistic-${itemId}-${day}`,
                item: itemId,
                day,
                ticked_at: now,
                updated_at: now,
                deleted_at: null,
                sync_id: null,
                value: null,
                source: 'USER_REPORTED',
              },
            ]
          : ticks.map((tick) =>
              tick.item === itemId && tick.day === day && tick.deleted_at === null
                ? { ...tick, deleted_at: now }
                : tick,
            )
        queryClient.setQueryData<Changes>(CHANGES_KEY, { ...previous, checklist_ticks: nextTicks })
      }
      return { previous }
    },
    onError: (_error, _vars, context) => {
      if (context?.previous) queryClient.setQueryData(CHANGES_KEY, context.previous)
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: CHANGES_KEY })
      // A Groceries tick or untick makes or removes a bought entry on the engine.
      void queryClient.invalidateQueries({ queryKey: PURCHASES_KEY })
    },
  })
}

/**
 * Writing events: the five mutations behind the event sheet (web-revamp 09).
 *
 * **None of them is optimistic.** An event write can be refused (a blank title,
 * a reminder the engine does not offer, a 403 for who may make a row private),
 * and an event that appears and then vanishes is worse than one that waits. Each
 * runs through `runWrite`, so a refusal rejects with a sentence that opens with
 * what did not happen and carries the engine's own words, and the sheet stays
 * open showing it. Success invalidates the shared changes query, so every
 * screen that renders an occurrence moves together.
 */

function useEventWrite<V>(write: (vars: V) => Promise<void>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: write,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: CHANGES_KEY }),
  })
}

export type NewEvent = EventFields & {
  kind?: string
  visibility: Visibility
  origin_guid?: string
}

export function useCreateEvent() {
  return useEventWrite(async (event: NewEvent) =>
    runWrite('saved', () => api.POST('/api/events', { body: wire<Event>(event) })),
  )
}

export function useUpdateEvent() {
  return useEventWrite(
    async ({ id, fields }: { id: string; fields: Partial<EventFields> & { visibility?: Visibility; kind?: string } }) =>
      runWrite('saved', () =>
        api.PATCH('/api/events/{id}', { params: { path: { id } }, body: fields }),
      ),
  )
}

export function useDeleteEvent() {
  return useEventWrite(async (id: string) =>
    runWrite('deleted', () => api.DELETE('/api/events/{id}', { params: { path: { id } } })),
  )
}

/** "Not this one": the occurrence on `date` (`YYYY-MM-DD`, the date it shows) is
 * taken out of the series. Idempotent on the engine, so a retry is safe. */
export function useSkipOccurrence() {
  return useEventWrite(async ({ id, date, verb }: { id: string; date: string; verb: WriteVerb }) =>
    runWrite(verb, () =>
      api.POST('/api/events/{id}/skips', {
        params: { path: { id } },
        body: { skip_date: date },
      }),
    ),
  )
}

/**
 * "Just this one" on an edit: skip that date in the series, then POST a one-off
 * carrying the edits, its `origin_guid` the series id and the date, so a retry
 * finds the row it already made instead of adding a second (spec D4).
 *
 * The two calls are two writes, and the second can fail after the first
 * succeeded. Then the occurrence IS out of the series and the changed copy is
 * NOT saved, and saying "nothing was saved" would be false. The sentence says
 * exactly that, and pressing Save again finishes the job: the skip is
 * idempotent and the create is keyed.
 */
export function useEditOccurrence() {
  return useEventWrite(
    async ({
      seriesId,
      date,
      event,
    }: {
      seriesId: string
      date: string
      event: NewEvent
    }) => {
      await runWrite('saved', () =>
        api.POST('/api/events/{id}/skips', {
          params: { path: { id: seriesId } },
          body: { skip_date: date },
        }),
      )
      try {
        await runWrite('saved', () =>
          api.POST('/api/events', {
            body: wire<Event>({ ...event, origin_guid: occurrenceGuid({ id: seriesId }, date) }),
          }),
        )
      } catch (error) {
        // `runWrite` opens with "Nothing was saved." which would contradict the
        // sentence this one starts with; keep only what the engine said.
        const reason = (error instanceof Error ? error.message : 'The engine did not say why.').replace(
          /^Nothing was saved\.\s*/,
          '',
        )
        throw new WriteRefused(
          `The ${date} occurrence was taken out of the repeating series, but the changed copy was not saved. ` +
            `Press Save again to finish. ${reason}`,
        )
      }
    },
  )
}
