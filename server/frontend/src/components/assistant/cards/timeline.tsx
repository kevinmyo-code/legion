import { More } from './parts'
import { clockWords, dayWords, type TimelineCard, type TimelineItem } from './types'

const KIND_WORDS: Record<TimelineItem['kind'], string | null> = {
  event: null,
  todo: 'to do',
  suggestion: 'suggestion, not a plan',
}

function when(item: TimelineItem): string {
  if (item.all_day || !item.start) return 'All day'
  return item.end ? `${clockWords(item.start)} to ${clockWords(item.end)}` : clockWords(item.start)
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
              <span data-card="time" className="w-[7.5rem] flex-none text-muted-foreground tabular-nums">
                {when(item)}
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
