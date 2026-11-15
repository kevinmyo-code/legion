import { More } from './parts'
import { asOfWords, readingWords, STATUS_WORDS, type ReadingsCard, type StatusListCard } from './types'

/** "As of ..." when the card carries its time; nothing when it does not. */
export function AsOf({ at }: { at?: string }) {
  const words = asOfWords(at)
  if (!words) return null
  return (
    <p data-card="at" className="-mt-1 mb-2 text-[0.8125rem] text-muted-foreground">
      {words}
    </p>
  )
}

/** Labelled values with units: a snapshot of measurements. An unread value says "not read". */
export function ReadingsView({ card }: { card: ReadingsCard }) {
  return (
    <>
      <AsOf at={card.at} />
      <dl data-card="readings" className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1.5">
        {card.items.map((item, index) => (
          <div key={`${index}-${item.label}`} data-card="item" className="contents">
            <dt className="text-muted-foreground">{item.label}</dt>
            <dd data-card="value" data-flag={item.flag} className="tabular-nums">
              {readingWords(item)}
              {item.note && <span className="block text-[0.8125rem] text-muted-foreground">{item.note}</span>}
            </dd>
          </div>
        ))}
      </dl>
      <More count={card.more} />
    </>
  )
}

/** Rows with a status said in words; a colour, where there is one, only repeats the word. */
export function StatusListView({ card }: { card: StatusListCard }) {
  return (
    <>
      <AsOf at={card.at} />
      <ul data-card="status-list" className="flex flex-col gap-1.5">
        {card.items.map((item, index) => (
          <li key={`${index}-${item.name}`} data-card="item" data-status={item.status}>
            <div className="flex items-baseline justify-between gap-3">
              <span data-card="label" className="min-w-0">
                {item.name}
              </span>
              <span
                data-card="status"
                className={
                  item.status === 'failed' || item.status === 'attention'
                    ? 'flex-none font-medium text-destructive'
                    : 'flex-none text-muted-foreground'
                }
              >
                {STATUS_WORDS[item.status]}
              </span>
            </div>
            {item.note && <p className="text-[0.8125rem] text-muted-foreground">{item.note}</p>}
          </li>
        ))}
      </ul>
      <More count={card.more} />
    </>
  )
}
