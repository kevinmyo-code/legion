import type { StepsCard } from './types'

const STATUS_WORDS: Record<StepsCard['items'][number]['status'], string> = {
  ok: 'done',
  failed: 'did not finish',
  skipped: 'not run',
}

/** What a procedure did, step by step: the status in words and the time it took. */
export function StepsView({ card }: { card: StepsCard }) {
  return (
    <>
      <ol data-card="steps" className="flex flex-col gap-1">
        {card.items.map((item, index) => (
          <li key={`${index}-${item.label}`} data-card="item" data-status={item.status} className="flex items-baseline gap-3">
            <span data-card="label" className="min-w-0 flex-1 truncate">
              {item.label}
            </span>
            <span data-card="status" className={item.status === 'failed' ? 'text-destructive' : 'text-muted-foreground'}>
              {STATUS_WORDS[item.status]}
            </span>
            <span data-card="value" className="w-[4.5rem] flex-none text-right text-muted-foreground tabular-nums">
              {item.status === 'skipped' ? '' : `${item.ms.toLocaleString('en-US')} ms`}
            </span>
          </li>
        ))}
      </ol>
      <p data-card="total" className="mt-1.5 text-right text-[0.8125rem] text-muted-foreground tabular-nums">
        Total {card.total_ms.toLocaleString('en-US')} ms
      </p>
    </>
  )
}
