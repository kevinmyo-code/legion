/**
 * The card a tool result may carry (`server/engine_mcp/cards.py`), built by the
 * engine from the tool's own rows and never from model text. The format is
 * shared with the phone: these field names are the server's, unchanged.
 *
 * The schema types `card` as `unknown`, so `asCard` checks the shape before
 * anything draws it; a card it does not recognise draws nothing.
 */

export type Flag = 'unverified' | 'estimate'

interface Common {
  title: string
  scope?: string
  period?: string
  note?: string
}

export interface BarsCard extends Common {
  card: 'bars'
  /** "usd": values are integer cents. "": counts. */
  unit: 'usd' | ''
  points: { label: string; value: number; flag?: Flag }[]
  more: number
}

export interface TimelineItem {
  /** YYYY-MM-DD: on a card over several days, the day this item falls on. Additive. */
  date?: string
  start: string | null
  end: string | null
  all_day: boolean
  title: string
  kind: 'event' | 'todo' | 'suggestion'
}

export interface TimelineCard extends Common {
  card: 'timeline'
  /** YYYY-MM-DD in the household's zone. */
  date: string
  /** "UTC-06:00", never an IANA id. */
  zone: string
  items: TimelineItem[]
  more: number
}

export interface CarCard extends Common {
  card: 'car'
  name: string
  mileage: number | null
  mileage_as_of: string | null
  next_service: { label: string; due_miles?: number; due_date?: string; estimate: true } | null
  recalls: { summary: string }[]
  codes: { code: string; meaning: string }[]
  /**
   * Additive: the parts this card's call read, each with its note. A card from
   * next_service alone has only `service`; one from vehicle_recalls only
   * `recalls`. Absent means every part (an older engine, or car_check).
   */
  parts?: Partial<Record<CarPart, string>>
}

export type CarPart = 'service' | 'codes' | 'recalls'
export const CAR_PARTS: readonly CarPart[] = ['service', 'codes', 'recalls']

/** Whether `card` read `part` (every part when the engine did not say). */
export function hasPart(card: CarCard, part: CarPart): boolean {
  return card.parts === undefined || part in card.parts
}

/**
 * Two cards for the same car from one conversation (next_service, then
 * vehicle_recalls) as one: each part from the newer card when it read that part,
 * else from the older. The phone does the same (`ConversationCards.mergeCar`).
 */
export function mergeCar(older: CarCard, newer: CarCard): CarCard {
  if (newer.parts === undefined) return newer
  const pick = (part: CarPart) => (part in newer.parts! || !hasPart(older, part) ? newer : older)
  const service = pick('service')
  const parts: Partial<Record<CarPart, string>> = {}
  for (const part of CAR_PARTS) {
    const from = pick(part)
    if (hasPart(from, part)) parts[part] = from.parts?.[part] ?? ''
  }
  return {
    ...newer,
    mileage: service.mileage,
    mileage_as_of: service.mileage_as_of,
    next_service: service.next_service,
    codes: pick('codes').codes,
    recalls: pick('recalls').recalls,
    parts,
    note: Object.values(parts).filter(Boolean).join(' ') || undefined,
  }
}

export interface StepsCard extends Common {
  card: 'steps'
  name: string
  items: { label: string; status: 'ok' | 'failed' | 'skipped'; ms: number }[]
  total_ms: number
}

export interface ChecklistCard extends Common {
  card: 'checklist'
  list_name: string
  items: { text: string; done: boolean }[]
  more: number
}

/**
 * An architecture diagram (`show_diagram`, `server/diagrams/catalogue.py`): a picture the engine
 * rendered ahead of time from a fixed catalogue, never drawn from model text. `caption` is the
 * picture in words; `highlight` names the parts the assistant is pointing at, by `nodes` id.
 */
export interface DiagramCard extends Common {
  card: 'diagram'
  project: string
  view: string
  caption: string
  svg: string
  highlight: string[]
  nodes: { id: string; label: string }[]
}

/**
 * Kevin's one-page resume (`show_resume`, `server/demo/tools.py`, recruiters only). Links, never
 * bytes: `image` is the page as a PNG (Android Chrome draws no PDF in a page), null when only the
 * PDF is loaded; `pdf` is the download. Both are the visitor's own content version's files.
 */
export interface ResumeCard extends Common {
  card: 'resume'
  image: string | null
  pdf: string
  alt: string
}

/**
 * The three general kinds (2026-11-15), shared with the phone; one of each, with the words every
 * screen says for it, is in `shared/cards/fixtures.json`. `unit` "usd" means integer cents.
 */
export interface Reading {
  label: string
  /** Null: it could not be read. Said "not read", never drawn as zero. */
  value: number | null
  unit: string
  flag?: Flag
  /** An earlier value of the same reading: a before and after. */
  was?: number | null
  /** A value that is words, not a number ("Closed loop", a VIN); `value` is then null. */
  text?: string
  note?: string
}

export interface ReadingsCard extends Common {
  card: 'readings'
  /** The "as of" time, YYYY-MM-DD or YYYY-MM-DDTHH:MM on the household's clock. */
  at?: string
  items: Reading[]
  more: number
}

export type Status = 'ok' | 'attention' | 'failed' | 'unknown' | 'not_reached'

export interface StatusListCard extends Common {
  card: 'status_list'
  at?: string
  items: { name: string; status: Status; note?: string }[]
  more: number
}

export interface TrendSeries {
  name: string
  /** Oldest first; `at` is YYYY-MM-DD or YYYY-MM-DDTHH:MM. */
  points: { at: string; value: number }[]
  flag?: Flag
}

export interface TrendCard extends Common {
  card: 'trend'
  unit: string
  series: TrendSeries[]
}

export type Card =
  | BarsCard
  | TimelineCard
  | CarCard
  | StepsCard
  | ChecklistCard
  | DiagramCard
  | ResumeCard
  | ReadingsCard
  | StatusListCard
  | TrendCard

/** The cards that open over the whole page (`screen-modal.tsx`) as well as drawing in the chat. */
export type ModalCard = DiagramCard | ResumeCard

export function isModalCard(card: Card | undefined): card is ModalCard {
  return card?.card === 'diagram' || card?.card === 'resume'
}

/** Only an engine path: the card's links must stay on this origin's asset door. */
const ASSET_PATH = /^\/api\/demo\/asset\/[a-z_]+$/

const isObject = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)

const isList = (value: unknown): value is Record<string, unknown>[] =>
  Array.isArray(value) && value.every(isObject)

/** The card, when `value` is one this screen knows how to draw; otherwise undefined. */
export function asCard(value: unknown): Card | undefined {
  if (!isObject(value) || typeof value.title !== 'string') return undefined
  switch (value.card) {
    case 'bars':
      return isList(value.points) && value.points.every((p) => typeof p.label === 'string' && Number.isInteger(p.value))
        ? (value as unknown as BarsCard)
        : undefined
    case 'timeline':
      return typeof value.date === 'string' && typeof value.zone === 'string' && isList(value.items)
        ? (value as unknown as TimelineCard)
        : undefined
    case 'car':
      return typeof value.name === 'string' && isList(value.recalls) && isList(value.codes)
        ? (value as unknown as CarCard)
        : undefined
    case 'steps':
      return typeof value.name === 'string' && isList(value.items) ? (value as unknown as StepsCard) : undefined
    case 'checklist':
      return typeof value.list_name === 'string' && isList(value.items) ? (value as unknown as ChecklistCard) : undefined
    case 'diagram':
      return typeof value.svg === 'string' &&
        value.svg.trimStart().startsWith('<svg') &&
        typeof value.caption === 'string' &&
        typeof value.view === 'string' &&
        Array.isArray(value.highlight) &&
        value.highlight.every((h) => typeof h === 'string') &&
        isList(value.nodes)
        ? (value as unknown as DiagramCard)
        : undefined
    case 'resume':
      return typeof value.pdf === 'string' &&
        ASSET_PATH.test(value.pdf) &&
        (value.image === null || (typeof value.image === 'string' && ASSET_PATH.test(value.image))) &&
        typeof value.alt === 'string'
        ? (value as unknown as ResumeCard)
        : undefined
    case 'readings':
      return isList(value.items) && value.items.every(isReading) && atOk(value.at)
        ? (value as unknown as ReadingsCard)
        : undefined
    case 'status_list':
      return isList(value.items) &&
        value.items.every((i) => typeof i.name === 'string' && STATUSES.includes(i.status as Status)) &&
        atOk(value.at)
        ? (value as unknown as StatusListCard)
        : undefined
    case 'trend':
      return typeof value.unit === 'string' &&
        isList(value.series) &&
        value.series.length >= 1 &&
        value.series.length <= 2 &&
        value.series.every(
          (s) =>
            typeof s.name === 'string' &&
            isList(s.points) &&
            s.points.every((p) => typeof p.at === 'string' && AT.test(p.at) && isNumber(p.value)),
        )
        ? (value as unknown as TrendCard)
        : undefined
    default:
      return undefined
  }
}

const STATUSES: readonly Status[] = ['ok', 'attention', 'failed', 'unknown', 'not_reached']
const AT = /^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2}))?$/

const isNumber = (value: unknown): value is number => typeof value === 'number' && Number.isFinite(value)

const atOk = (value: unknown) => value === undefined || value === null || (typeof value === 'string' && AT.test(value))

function isReading(item: Record<string, unknown>): boolean {
  if (typeof item.label !== 'string' || typeof item.unit !== 'string') return false
  if (item.text !== undefined && item.text !== null && (typeof item.text !== 'string' || item.value !== null)) return false
  const fits = (v: unknown) => v === undefined || v === null || (isNumber(v) && (item.unit !== 'usd' || Number.isInteger(v)))
  return fits(item.value) && fits(item.was)
}

/** Status in words, never a colour or a glyph alone. The engine's `cards.STATUS_WORDS`. */
export const STATUS_WORDS: Record<Status, string> = {
  ok: 'OK',
  attention: 'Needs attention',
  failed: 'Failed',
  unknown: 'Unknown',
  not_reached: 'Not reached',
}

/** "72.5", "203,000": at most three decimals, no trailing zeros. The phone's `CardWords.number`. */
export function numberWords(value: number): string {
  return value.toLocaleString('en-US', { maximumFractionDigits: 3 })
}

/** A value with its unit: "$1,234.56" (usd is cents), "45%", "72.5 °C", "3". */
export function unitWords(value: number, unit: string): string {
  if (unit === 'usd') return centsWords(value)
  if (unit === '') return numberWords(value)
  if (unit === '%') return `${numberWords(value)}%`
  return `${numberWords(value)} ${unit}`
}

/**
 * A reading's value in words: "not read" when it could not be read, then the earlier value, then
 * its flag ("32 g (estimate)"). The phone's `CardWords.reading`.
 */
export function readingWords(item: Reading): string {
  if (item.value === null) return item.text ?? 'not read'
  const was = item.was != null ? ` (was ${unitWords(item.was, item.unit)})` : ''
  const flag = item.flag ? ` (${FLAG_WORDS[item.flag]})` : ''
  return unitWords(item.value, item.unit) + was + flag
}

/** "As of Sun Nov 15, 2026" from a card's `at`; its clock time in words when it carries one. */
export function asOfWords(at: string | undefined): string | null {
  if (!at) return null
  const m = AT.exec(at)
  if (!m) return null
  const day = dayWords(`${m[1]}-${m[2]}-${m[3]}`)
  return m[4] ? `As of ${day}, ${clockWords(`${m[4]}:${m[5]}`)}` : `As of ${day}`
}

/** "Nov 14" from an `at`. */
export function shortDayWords(at: string): string {
  const m = AT.exec(at)
  return m ? `${MONTHS[Number(m[2]) - 1]} ${Number(m[3])}` : at
}

/** "Weight: latest 182.4 lb on Nov 14, low 181 lb, high 186.2 lb." Every value in words. */
export function seriesWords(series: TrendSeries, unit: string): string {
  const name = series.flag ? `${series.name} (${FLAG_WORDS[series.flag]})` : series.name
  if (series.points.length === 0) return `${name}: nothing recorded in this window.`
  const last = series.points[series.points.length - 1]
  const values = series.points.map((p) => p.value)
  const low = Math.min(...values)
  const high = Math.max(...values)
  return `${name}: latest ${unitWords(last.value, unit)} on ${shortDayWords(last.at)}, low ${unitWords(low, unit)}, high ${unitWords(high, unit)}.`
}

/** Minutes since 1970 for an `at`, read as a clock time with no zone: for placing points only. */
export function atMinutes(at: string): number {
  const m = AT.exec(at)
  if (!m) return 0
  return Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3]), Number(m[4] ?? 0), Number(m[5] ?? 0)) / 60_000
}

/** "$1,234.56" from integer cents. Display only: nothing adds these back up. */
export function centsWords(cents: number): string {
  const sign = cents < 0 ? '-' : ''
  const abs = Math.abs(Math.trunc(cents))
  const dollars = Math.floor(abs / 100).toLocaleString('en-US')
  return `${sign}$${dollars}.${String(abs % 100).padStart(2, '0')}`
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']

/** "Wed Nov 4, 2026" from YYYY-MM-DD, read as a calendar date (no zone conversion). */
export function dayWords(iso: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso)
  if (!m) return iso
  const [year, month, day] = [Number(m[1]), Number(m[2]), Number(m[3])]
  const weekday = WEEKDAYS[new Date(Date.UTC(year, month - 1, day)).getUTCDay()]
  return `${weekday} ${MONTHS[month - 1]} ${day}, ${year}`
}

/** "3:30 pm" from "15:30". */
export function clockWords(hhmm: string): string {
  const m = /^(\d{2}):(\d{2})$/.exec(hhmm)
  if (!m) return hhmm
  const hour = Number(m[1])
  const h12 = hour % 12 === 0 ? 12 : hour % 12
  return `${h12}:${m[2]} ${hour < 12 ? 'am' : 'pm'}`
}

export const FLAG_WORDS: Record<Flag, string> = { unverified: 'unverified', estimate: 'estimate' }
