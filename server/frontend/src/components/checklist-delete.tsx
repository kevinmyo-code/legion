import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Trash2 } from 'lucide-react'
import { useState } from 'react'

import { api } from '@/api/client'
import { CHANGES_KEY } from '@/api/queries'
import { Button } from '@/components/ui/button'

/**
 * Delete control for a checklist, shared by `/lists` and Home so the two
 * surfaces never grow two different delete flows (web-calendar-and-lists
 * ticket 02).
 *
 * **Offers, never auto-deletes.** A click opens an inline confirmation
 * rather than deleting immediately - ticking the last item on a list must
 * never make it vanish as a side effect, and a mistap needs a moment to
 * back out of.
 *
 * **A delete says what it keeps.** `DELETE /api/checklists/{id}` is soft and
 * deliberately does not cascade to items or ticks (`checklists/views.py`),
 * so the confirmation states that in words - a user who believes deleting
 * Groceries erases the tick history would otherwise never delete it, and
 * would be wrong about what the app did.
 *
 * **Failure path**: on error the control stays in its confirming state and
 * names the failure. Nothing here removes the card optimistically - the
 * card only disappears once the server actually confirms the delete and the
 * `changes` query refetches.
 */
export function DeleteChecklistControl({
  checklistId,
  checklistName,
}: {
  checklistId: string
  checklistName: string
}) {
  const [confirming, setConfirming] = useState(false)
  const queryClient = useQueryClient()

  const remove = useMutation({
    mutationFn: async () => {
      const { error, response } = await api.DELETE('/api/checklists/{checklist_id}', {
        params: { path: { checklist_id: checklistId } },
      })
      if (error) throw new Error(`DELETE checklist answered ${response.status}`)
    },
    onSuccess: () => {
      setConfirming(false)
      void queryClient.invalidateQueries({ queryKey: CHANGES_KEY })
    },
  })

  if (!confirming) {
    return (
      <Button
        variant="ghost"
        size="icon-sm"
        aria-label={`Delete "${checklistName}"`}
        onClick={() => setConfirming(true)}
      >
        <Trash2 className="size-3.5" />
      </Button>
    )
  }

  return (
    <div className="flex flex-col items-end gap-1.5 text-right">
      <p className="text-xs text-muted-foreground">
        The list goes. What you ticked off it is kept.
      </p>
      <div className="flex gap-2">
        <Button
          variant="outline"
          size="sm"
          onClick={() => setConfirming(false)}
          disabled={remove.isPending}
        >
          Cancel
        </Button>
        <Button
          variant="destructive"
          size="sm"
          onClick={() => remove.mutate()}
          disabled={remove.isPending}
        >
          Delete list
        </Button>
      </div>
      {remove.isError && (
        <span className="text-xs text-destructive">Could not delete. {remove.error.message}</span>
      )}
    </div>
  )
}
