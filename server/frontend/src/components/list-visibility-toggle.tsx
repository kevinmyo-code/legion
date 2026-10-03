import { useMutation, useQueryClient } from '@tanstack/react-query'

import { api } from '@/api/client'
import { CHANGES_KEY } from '@/api/queries'
import { runWrite } from '@/api/refusal'
import type { Checklist } from '@/api/types'
import { VisibilityMark } from '@/components/visibility-mark'
import { visibilityOf } from '@/lib/visibility'

/**
 * The privacy toggle in a list's header: the mark itself is the button.
 *
 * One press flips shared to private or back, **not optimistically**: the engine
 * decides who may make a row private (the person who added it, or anyone when
 * nobody is recorded as having added it - ADR 0052), and a refusal arrives as a
 * 403 whose sentence is shown verbatim under a line that says nothing was
 * saved. A mark that flipped and then flipped back would tell a person, for a
 * moment, that a private list was private.
 */
export function ListVisibilityToggle({ checklist }: { checklist: Checklist }) {
  const queryClient = useQueryClient()
  const current = visibilityOf(checklist)
  const next = current === 'shared' ? 'private' : 'shared'

  const change = useMutation({
    mutationFn: () =>
      runWrite('saved', () =>
        api.PATCH('/api/checklists/{checklist_id}', {
          params: { path: { checklist_id: checklist.id } },
          body: { visibility: next },
        }),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: CHANGES_KEY }),
  })

  return (
    <div className="flex flex-col items-start gap-1">
      <button
        type="button"
        className="-m-1 rounded-full p-1 outline-none focus-visible:outline-2 focus-visible:outline-primary disabled:opacity-50 pointer-coarse:min-h-11"
        disabled={change.isPending}
        aria-label={
          current === 'shared'
            ? `"${checklist.name}" is shared with the household. Make it only yours.`
            : `"${checklist.name}" is only yours. Share it with the household.`
        }
        onClick={() => change.mutate()}
      >
        <VisibilityMark visibility={current} />
      </button>
      {change.isError && (
        <p role="alert" className="max-w-64 text-[0.8125rem] text-destructive">
          {change.error.message}
        </p>
      )}
    </div>
  )
}
