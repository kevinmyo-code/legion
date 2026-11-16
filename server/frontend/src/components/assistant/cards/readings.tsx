import { More } from './parts'
import {
  asOfWords,
  levelWords,
  meterWords,
  readingWords,
  readingsHeadline,
  splitWords,
  STATUS_WORDS,
  statusHeadline,
  type Headline,
  type Reading,
  type ReadingsCard,
  type StatusListCard,
  type StatusRow,
} from './types'

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

/** The card's big line: what it answers at a glance. The accent only when something needs attention. */
export function HeadlineView({ headline }: { headline: Headline | null }) {
  if (!headline) return null
  return (
    <div data-card="headline" className="mb-2">
      {headline.label && <p className="text-[0.75rem] uppercase tracking-wide text-muted-foreground">{headline.label}</p>}
      <p className={`text-2xl font-semibold tabular-nums ${headline.attention ? 'text-destructive' : 'text-foreground'}`}>
        {headline.value}
      </p>
    </div>
  )
}

const pct = (value: number, min: number, max: number) => Math.min(100, Math.max(0, ((value - min) / (max - min || 1)) * 100))

/** Hatching for an estimate or an unverified figure, so it never looks measured. */
function Hatch({ id }: { id: string }) {
  return (
    <defs>
      <pattern id={id} width="4" height="4" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
        <rect width="4" height="4" fill="currentColor" opacity="0.12" />
        <line x1="0" y1="0" x2="0" y2="4" stroke="currentColor" strokeWidth="1.5" />
      </pattern>
    </defs>
  )
}

/** A reading on its scale: track, value, and its mark as a tick with its label under it. */
function Meter({ item, index }: { item: Reading; index: number }) {
  if (!item.scale || item.value === null) return null
  const { min, max } = item.scale
  const fill = pct(item.value, min, max)
  const id = `hatch-r-${index}`
  return (
    <div data-card="meter" className="mt-1">
      <svg aria-hidden="true" viewBox="0 0 100 4" preserveAspectRatio="none" className={`h-2 w-full ${item.attention ? 'text-destructive' : 'text-foreground'}`}>
        {item.flag && <Hatch id={id} />}
        <rect width="100" height="4" className="fill-border" />
        <rect width={fill} height="4" fill={item.flag ? `url(#${id})` : 'currentColor'} />
        {item.mark && <rect x={pct(item.mark.value, min, max) - 0.4} width="0.8" height="4" className="fill-foreground" />}
      </svg>
      {item.mark && <p className="text-right text-[0.75rem] italic text-muted-foreground">{item.mark.label}</p>}
    </div>
  )
}

/** One whole in parts, each a share of the bar in a step of the ink, hatched when estimates. */
function Split({ item, index }: { item: Reading; index: number }) {
  if (!item.split) return null
  const total = item.split.parts.reduce((sum, p) => sum + p.value, 0)
  const tones = [1, 0.62, 0.34, 0.18]
  let x = 0
  return (
    <div data-card="split" className="mt-1">
      {total > 0 && (
        <svg aria-hidden="true" viewBox="0 0 100 4" preserveAspectRatio="none" className="h-2.5 w-full text-foreground">
          {item.flag && <Hatch id={`hatch-s-${index}`} />}
          {item.split.parts.map((p, i) => {
            const w = (p.value / total) * 100
            const at = x
            x += w
            return (
              <rect
                key={p.label}
                x={at}
                width={Math.max(0, w - (i < item.split!.parts.length - 1 ? 0.6 : 0))}
                height="4"
                opacity={tones[i % tones.length]}
                fill={item.flag ? `url(#hatch-s-${index})` : 'currentColor'}
              />
            )
          })}
        </svg>
      )}
      <p data-card="split-words" className="text-[0.8125rem] text-muted-foreground">
        {splitWords(item)}
      </p>
    </div>
  )
}

/** Labelled values with units: a snapshot of measurements. An unread value says "not read". */
export function ReadingsView({ card }: { card: ReadingsCard }) {
  return (
    <>
      <HeadlineView headline={readingsHeadline(card)} />
      <AsOf at={card.at} />
      <dl data-card="readings" className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1.5">
        {card.items.map((item, index) => (
          <div key={`${index}-${item.label}`} data-card="item" className="contents">
            <dt className="text-muted-foreground">{item.label}</dt>
            <dd data-card="value" data-flag={item.flag} className={`tabular-nums ${item.attention ? 'text-destructive' : ''}`}>
              {readingWords(item)}
              <Meter item={item} index={index} />
              <Split item={item} index={index} />
              {item.note && <span className="block text-[0.8125rem] text-muted-foreground">{item.note}</span>}
            </dd>
          </div>
        ))}
      </dl>
      <More count={card.more} />
    </>
  )
}

/** A dot before a status: ink for OK, the accent for attention, a ring when not known. */
function StatusMark({ status }: { status: StatusRow['status'] }) {
  const flagged = status === 'attention' || status === 'failed'
  const unknown = status === 'unknown' || status === 'not_reached'
  return (
    <svg aria-hidden="true" data-card="mark" viewBox="0 0 10 10" className={`mr-2 inline-block h-2.5 w-2.5 flex-none ${flagged ? 'text-destructive' : unknown ? 'text-muted-foreground' : 'text-foreground'}`}>
      {unknown ? <circle cx="5" cy="5" r="4" fill="none" stroke="currentColor" strokeWidth="1.5" /> : <circle cx="5" cy="5" r="5" fill="currentColor" />}
    </svg>
  )
}

/** An amount against its limit: ink to the limit, the accent past it, the mark dashed. */
function StatusMeter({ row }: { row: StatusRow }) {
  const meter = row.meter
  if (!meter) return null
  const top = Math.max(meter.value, meter.max, meter.mark?.value ?? 0)
  const at = (v: number) => pct(v, 0, top)
  const flagged = row.status === 'attention' || row.status === 'failed'
  return (
    <div data-card="meter" className="mt-1">
      <svg aria-hidden="true" viewBox="0 0 100 4" preserveAspectRatio="none" className="h-2 w-full">
        <rect width={at(meter.max)} height="4" className="fill-border" />
        <rect width={Math.min(at(meter.value), at(meter.max))} height="4" className={flagged ? 'fill-destructive' : 'fill-foreground'} />
        {meter.value > meter.max && (
          <rect x={at(meter.max)} width={at(meter.value) - at(meter.max)} height="4" className="fill-destructive" />
        )}
        <rect x={at(meter.max) - 0.4} width="0.8" height="4" className="fill-foreground" />
        {meter.mark && <rect x={at(meter.mark.value) - 0.4} width="0.8" height="4" className="fill-muted-foreground" />}
      </svg>
      <p className="flex justify-between gap-3 text-[0.8125rem]">
        <span data-card="meter-words">{meterWords(meter)}</span>
        {meter.mark && <span className="italic text-muted-foreground">{meter.mark.label}</span>}
      </p>
    </div>
  )
}

/** A rank on a named scale: `of` squares, the first `value` filled. Said as "Level 2 of 4". */
function Level({ row }: { row: StatusRow }) {
  const level = row.level
  if (!level) return null
  const flagged = row.status === 'attention' || row.status === 'failed'
  return (
    <span data-card="level" role="img" aria-label={levelWords(level)} className="mt-1 flex gap-0.5">
      {Array.from({ length: level.of }, (_, i) => (
        <span
          key={i}
          className={`h-2 w-4 ${i < level.value ? (flagged ? 'bg-destructive' : 'bg-foreground') : 'bg-border'}`}
        />
      ))}
    </span>
  )
}

/** Rows with a status said in words; a colour, where there is one, only repeats the word. */
export function StatusListView({ card }: { card: StatusListCard }) {
  return (
    <>
      <HeadlineView headline={statusHeadline(card)} />
      <AsOf at={card.at} />
      <ul data-card="status-list" className="flex flex-col gap-1.5">
        {card.items.map((item, index) => (
          <li key={`${index}-${item.name}`} data-card="item" data-status={item.status}>
            <div className="flex items-baseline justify-between gap-3">
              <span data-card="label" className="flex min-w-0 items-baseline">
                <StatusMark status={item.status} />
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
            <Level row={item} />
            <StatusMeter row={item} />
            {item.note && <p className="text-[0.8125rem] text-muted-foreground">{item.note}</p>}
          </li>
        ))}
      </ul>
      <More count={card.more} />
    </>
  )
}
