import { More } from './parts'
import { clockWords, dayWords, type TimelineCard, type TimelineItem } from './types'

const KIND_WORDS: Record<TimelineItem['kind'], string | null> = {
  event: null,
  todo: 'to do',
  suggestion: 'suggestion, not a plan',
}

function When({ item }: { item: TimelineItem }) {
  if (item.all_day || !item.start) return <>All day</>
  return (
    <>
      {clockWords(item.start)}
      {item.end && <span className="block text-[0.8125rem]">to {clockWords(item.end)}</span>}
    </>
  )
}

/** One day: a time column and the title. All-day first (the engine sorts them so). */
export function TimelineView({ card }: { card: TimelineCard }) {
  return (
    <>
      <p data-card="day" className="-mt-1 mb-2 text-[0.8125rem] text-muted-foreground">
        {dayWords(card.date)}, times in {card.zone}
      </p>
      {card.items.length === 0 ? (
        <p data-card="empty" className="text-muted-foreground">
          Nothing on file for this day.
        </p>
      ) : (
        <ul data-card="timeline" className="flex flex-col gap-1.5">
          {card.items.map((item, index) => (
            <li key={`${index}-${item.title}`} data-card="item" data-item-kind={item.kind} className="flex gap-3">
              <span data-card="time" className="w-[4.75rem] flex-none whitespace-nowrap text-muted-foreground tabular-nums">
                <When item={item} />
              </span>
              <span className="min-w-0">
                <span data-card="label">{item.title}</span>
                {KIND_WORDS[item.kind] && (
                  <span data-card="kind" className="text-muted-foreground">
                    {' '}
                    ({KIND_WORDS[item.kind]})
                  </span>
                )}
              </span>
            </li>
          ))}
        </ul>
      )}
      <More count={card.more} />
    </>
  )
}
