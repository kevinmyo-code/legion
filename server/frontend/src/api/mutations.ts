import { useMutation, useQueryClient } from '@tanstack/react-query'

import { api } from '@/api/client'
import { CHANGES_KEY } from '@/api/queries'
import type { Changes, ChecklistTick } from '@/api/types'

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
            params: { path: { checklist_id: checklistId, item_id: itemId, day: dayToClear } },
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
    },
  })
}
