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

export type Card = BarsCard | TimelineCard | CarCard | StepsCard | ChecklistCard | DiagramCard | ResumeCard

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
    default:
      return undefined
  }
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
