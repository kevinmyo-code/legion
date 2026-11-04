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

function Items({ items }: { items: TimelineItem[] }) {
  return (
    <ul data-card="timeline" className="flex flex-col gap-1.5">
      {items.map((item, index) => (
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
  )
}

/** Items under their own day, in the engine's order (by day, all day first). */
function byDay(items: TimelineItem[], first: string): [string, TimelineItem[]][] {
  const days: [string, TimelineItem[]][] = []
  for (const item of items) {
    const day = item.date ?? first
    const last = days[days.length - 1]
    if (last && last[0] === day) last[1].push(item)
    else days.push([day, [item]])
  }
  return days
}

/**
 * One day, or a window of days: a time column and the title, all-day first.
 * Over several days (items carry `date`) each day gets its own heading.
 */
export function TimelineView({ card }: { card: TimelineCard }) {
  const several = card.items.some((item) => item.date !== undefined) || card.period !== undefined
  return (
    <>
      <p data-card="day" className="-mt-1 mb-2 text-[0.8125rem] text-muted-foreground">
        {several ? `Times in ${card.zone}` : `${dayWords(card.date)}, times in ${card.zone}`}
      </p>
      {card.items.length === 0 ? (
        <p data-card="empty" className="text-muted-foreground">
          {several ? 'Nothing on file for these days.' : 'Nothing on file for this day.'}
        </p>
      ) : several ? (
        <div className="flex flex-col gap-2">
          {byDay(card.items, card.date).map(([day, items]) => (
            <section key={day} aria-label={dayWords(day)} data-card="dayblock">
              <h4 data-card="dayhead" className="mb-1 text-[0.8125rem] font-semibold">
                {dayWords(day)}
              </h4>
              <Items items={items} />
            </section>
          ))}
        </div>
      ) : (
        <Items items={card.items} />
      )}
      <More count={card.more} />
    </>
  )
}
