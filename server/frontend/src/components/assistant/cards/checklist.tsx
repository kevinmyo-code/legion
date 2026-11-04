import { Check } from 'lucide-react'

import { More } from './parts'
import type { ChecklistCard } from './types'

/** One list with its tick state for the day the engine was asked about. Read only. */
export function ChecklistView({ card }: { card: ChecklistCard }) {
  return (
    <>
      {card.list_name !== card.title && (
        <p data-card="list" className="-mt-1 mb-2 text-[0.8125rem] text-muted-foreground">
          {card.list_name}
        </p>
      )}
      <ul data-card="checklist" className="flex flex-col gap-1">
        {card.items.map((item, index) => (
          <li key={`${index}-${item.text}`} data-card="item" data-done={item.done} className="flex items-center gap-2">
            <span
              aria-hidden="true"
              data-card="box"
              className="grid size-4 flex-none place-items-center rounded-sm border border-outline"
            >
              {item.done && <Check className="size-3" />}
            </span>
            <span data-card="label" className={item.done ? 'text-muted-foreground line-through' : undefined}>
              {item.text}
            </span>
            <span className="sr-only">{item.done ? ', ticked' : ', not ticked'}</span>
          </li>
        ))}
      </ul>
      <More count={card.more} />
    </>
  )
}
