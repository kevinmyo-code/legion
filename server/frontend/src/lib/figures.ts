/**
 * Formatting for the figures the aspect screens print.
 *
 * Money is `Long` cents on the server (CLAUDE.md section 4 rule 3) and stays an
 * integer in this app until the moment it is drawn: cents are summed as
 * integers and divided by 100 only inside `formatMoney`, never before. A sum of
 * floats is how a reconciled total starts disagreeing with its own lines.
 *
 * Dates have two shapes and they must not be confused, which is the bug class
 * `lib/day.ts` exists for. A `date-time` is an instant and is shown in the
 * viewer's own zone. A bare `date` (`2026-10-03`, a sleep night, a service day)
 * names a calendar day, so it is read as that local day and never as a UTC
 * instant - `new Date('2026-10-03')` is UTC midnight and reads as the 2nd for
 * anyone west of Greenwich.
 */

export function formatMoney(cents: number, currency = 'USD'): string {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(cents / 100)
}

/** Parse `YYYY-MM-DD` as that calendar day in the viewer's zone. */
export function parseDay(day: string): Date {
  const [year, month, date] = day.split('-').map(Number)
  return new Date(year, month - 1, date)
}

const SHORT_DATE = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
const SHORT_DATE_TIME = new Intl.DateTimeFormat('en-US', {
  month: 'short',
  day: 'numeric',
  year: 'numeric',
  hour: 'numeric',
  minute: '2-digit',
})

/** A bare calendar date, e.g. "Oct 3, 2026". */
export function formatDay(day: string): string {
  return SHORT_DATE.format(parseDay(day))
}

const NO_YEAR = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric' })

/** "Oct 3", for a chart axis or a tight column. */
export function formatShortDay(isoOrDay: string): string {
  return NO_YEAR.format(/^\d{4}-\d{2}-\d{2}$/.test(isoOrDay) ? parseDay(isoOrDay) : new Date(isoOrDay))
}

/** An instant, in the viewer's own zone. */
export function formatInstant(iso: string): string {
  return SHORT_DATE_TIME.format(new Date(iso))
}

export function formatInstantDate(iso: string): string {
  return SHORT_DATE.format(new Date(iso))
}

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

/** `YYYY-MM-DD` of the viewer's local day for an instant (never a UTC slice). */
export function localDay(iso: string | Date): string {
  const date = typeof iso === 'string' ? new Date(iso) : iso
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/** Today as a local `YYYY-MM-DD`. */
export function todayDay(): string {
  return localDay(new Date())
}

/** An instant as the value a `datetime-local` input wants, in the viewer's zone. */
export function toLocalInput(iso: string | Date): string {
  const date = typeof iso === 'string' ? new Date(iso) : iso
  return `${localDay(date)}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** What a `datetime-local` input holds, back to an instant. */
export function fromLocalInput(value: string): string {
  return new Date(value).toISOString()
}

/** The Monday on or before a local day, as `YYYY-MM-DD`. */
export function weekStartOf(day: string): string {
  const date = parseDay(day)
  const sinceMonday = (date.getDay() + 6) % 7
  date.setDate(date.getDate() - sinceMonday)
  return localDay(date)
}

/** `1 night`, `2 nights`. */
export function plural(count: number, one: string, many = `${one}s`): string {
  return `${count} ${count === 1 ? one : many}`
}

/** 435 minutes as "7 h 15 min". */
export function formatMinutes(minutes: number): string {
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  if (hours === 0) return `${rest} min`
  return rest === 0 ? `${hours} h` : `${hours} h ${rest} min`
}

/** A number as written: whole stays whole, otherwise one decimal at most. */
export function formatNumber(value: number, maxFractionDigits = 1): string {
  return new Intl.NumberFormat('en-US', { maximumFractionDigits: maxFractionDigits }).format(value)
}

/** A client-minted identity for a row the server keys by `origin_guid`. */
export function newGuid(): string {
  return crypto.randomUUID()
}

/**
 * Dollars typed into a field, as integer cents, without floating-point
 * arithmetic: "19.99" is read digit by digit, never as 19.99 * 100 (which is
 * 1998.9999999999998). Returns null for anything that is not a plain amount of
 * at most two decimal places, so the form can say so instead of rounding a
 * figure the person did not type.
 */
export function dollarsToCents(text: string): number | null {
  const match = text.trim().match(/^(-?)(\d+)(?:\.(\d{1,2}))?$/)
  if (!match) return null
  const [, sign, whole, fraction = ''] = match
  const cents = Number(whole) * 100 + Number(fraction.padEnd(2, '0'))
  return sign === '-' ? -cents : cents
}

/** Integer cents as the plain amount a field holds: 1999 becomes "19.99". */
export function centsToDollars(cents: number): string {
  const sign = cents < 0 ? '-' : ''
  const abs = Math.abs(cents)
  return `${sign}${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, '0')}`
}
