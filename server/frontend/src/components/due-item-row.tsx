import { useSetChecklistTick } from '@/api/mutations'
import { VisibilityMark } from '@/components/visibility-mark'
import { Checkbox } from '@/components/ui/checkbox'
import { todayEpochDay } from '@/lib/day'
import type { DueItem } from '@/lib/today'
import { visibilityOf } from '@/lib/visibility'

/**
 * One checklist item due today, tickable. Shared by the workbench Home and the
 * family Home so a tick is the same optimistic write (`useSetChecklistTick`) and
 * fails in the same words wherever it is pressed.
 */
export function DueItemRow({ due }: { due: DueItem }) {
  const today = todayEpochDay()
  // Every item here comes from `itemsDueOn`, which only ever includes a
  // checklist with a real schedule - `tickState`'s own rule for a scheduled
  // list is that `dayToClear` is always today, so this never needs to look
  // up a different day the way a plain list's item can.
  const setTick = useSetChecklistTick()

  return (
    <li className="flex min-h-11 items-center gap-3 rounded-[1.125rem] bg-surface-2 py-1.5 pr-3 pl-4">
      <Checkbox
        checked={due.tickedToday}
        disabled={setTick.isPending}
        onCheckedChange={(checked) =>
          setTick.mutate({
            checklistId: due.checklist.id,
            itemId: due.item.id,
            ticked: checked === true,
            today,
            dayToClear: today,
          })
        }
        aria-label={`Mark "${due.item.text}" ${due.tickedToday ? 'not done' : 'done'} for today`}
      />
      <span
        className={
          due.tickedToday
            ? 'flex-1 text-[0.9375rem] text-muted-foreground line-through'
            : 'flex-1 text-[0.9375rem] font-medium'
        }
      >
        {due.item.text}
      </span>
      <span className="shrink-0 text-[0.8125rem] text-muted-foreground">{due.checklist.name}</span>
      <VisibilityMark visibility={visibilityOf(due.checklist)} />
      {setTick.isError && (
        <span className="text-[0.8125rem] text-destructive">Could not save. {setTick.error.message}</span>
      )}
    </li>
  )
}
