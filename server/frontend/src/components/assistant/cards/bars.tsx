import { FlagWord, More } from './parts'
import { centsWords, FLAG_WORDS, type BarsCard } from './types'

/** Thin horizontal bars: label and value as text on one line, the bar under it. */
export function BarsView({ card }: { card: BarsCard }) {
  const max = Math.max(1, ...card.points.map((p) => Math.abs(p.value)))
  const words = (value: number) => (card.unit === 'usd' ? centsWords(value) : value.toLocaleString('en-US'))
  return (
    <>
      <ul data-card="bars" className="flex flex-col gap-2">
        {card.points.map((point) => (
          <li key={point.label} data-card="bar">
            <div className="flex items-baseline justify-between gap-3">
              <span data-card="label" className="min-w-0 truncate">
                {point.label}
              </span>
              <span data-card="value" className="flex-none tabular-nums">
                {words(point.value)}
                {point.flag && <FlagWord>{FLAG_WORDS[point.flag]}</FlagWord>}
              </span>
            </div>
            <div aria-hidden="true" data-card="track" className="mt-1 h-1.5 rounded-full bg-surface-3">
              <div
                data-card="fill"
                data-flag={point.flag}
                className="h-full rounded-full bg-primary"
                style={{ width: `${Math.max(2, (Math.abs(point.value) / max) * 100)}%` }}
              />
            </div>
          </li>
        ))}
      </ul>
      <More count={card.more} />
    </>
  )
}
