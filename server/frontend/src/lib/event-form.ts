import type { Event } from '@/api/types'
import {
  fromEpochDay,
  localZone,
  parseWeekdays,
  parseYmd,
  toEpochDay,
  ymdString,
  zonedInstant,
  zonedParts,
  type Occurrence,
} from '@/lib/recurrence'
import type { Visibility } from '@/lib/visibility'
import { visibilityOf } from '@/lib/visibility'

/**
 * The event sheet's form as plain data, and the two conversions around it: an
 * occurrence (or a blank) into a form, and a form into the body the engine
 * accepts (spec D8).
 *
 * Kept apart from the component so the rules that matter - what a weekly
 * series is stored as, what an all-day row's `starts_at` is, what validation
 * runs before the engine is asked - are plain functions with plain tests, not
 * behaviour a person has to click to find.
 */

export type RepeatChoice = 'never' | 'daily' | 'weekly' | 'monthly' | 'yearly'
export type EndsChoice = 'never' | 'on_date' | 'after'

export interface EventForm {
  title: string
  allDay: boolean
  /** `YYYY-MM-DD`. */
  date: string
  /** `HH:MM`; unused when `allDay`. */
  startTime: string
  /** `HH:MM`, or empty for no end. */
  endTime: string
  repeat: RepeatChoice
  /** "Every N": the sheet has no control for it, but a series made elsewhere
   * (the phone) may be every 2 weeks, and saving must not quietly make it
   * every week. Carried through, shown as a sentence. */
  every: number
  /** Monday = 0 .. Sunday = 6. */
  weekdays: number[]
  ends: EndsChoice
  endsOn: string
  endsAfter: string
  /** Minutes before the start as a string; empty for no reminder. */
  remind: string
  location: string
  notes: string
  visibility: Visibility
}

/** The reminder lead times the engine accepts (`api/event_columns.py`), in the
 * words a person picks from. `''` is no reminder; `'0'` is at the start. */
export const REMINDER_OPTIONS: readonly { value: string; label: string }[] = [
  { value: '', label: 'No reminder' },
  { value: '0', label: 'At the start' },
  { value: '5', label: '5 minutes before' },
  { value: '10', label: '10 minutes before' },
  { value: '15', label: '15 minutes before' },
  { value: '30', label: '30 minutes before' },
  { value: '60', label: '1 hour before' },
  { value: '120', label: '2 hours before' },
  { value: '1440', label: '1 day before' },
]

export const WEEKDAY_NAMES = [
  'MONDAY',
  'TUESDAY',
  'WEDNESDAY',
  'THURSDAY',
  'FRIDAY',
  'SATURDAY',
  'SUNDAY',
] as const

export const WEEKDAY_SHORT = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'] as const

function pad(n: number): string {
  return String(n).padStart(2, '0')
}

function hhmm(minutes: number): string {
  const wrapped = ((minutes % 1440) + 1440) % 1440
  return `${pad(Math.floor(wrapped / 60))}:${pad(wrapped % 60)}`
}

/** A blank form for `day` (viewer-local epoch day), starting at `startMinutes`
 * after local midnight and lasting an hour. */
export function blankForm(day: number, startMinutes: number): EventForm {
  return {
    title: '',
    allDay: false,
    date: ymdString(fromEpochDay(day)),
    startTime: hhmm(startMinutes),
    endTime: hhmm(startMinutes + 60),
    repeat: 'never',
    every: 1,
    weekdays: [],
    ends: 'never',
    endsOn: '',
    endsAfter: '5',
    remind: '',
    location: '',
    notes: '',
    visibility: 'shared',
  }
}

function repeatChoiceOf(kind: string | null | undefined): RepeatChoice {
  switch (kind) {
    case 'DAILY':
      return 'daily'
    case 'WEEKLY':
      return 'weekly'
    case 'MONTHLY_ON_DATE':
      return 'monthly'
    case 'YEARLY':
      return 'yearly'
    default:
      return 'never'
  }
}

/** The form for editing one occurrence: the occurrence's own date and time, and
 * the series' repeat rule (which only "All of them" acts on). */
export function formFromOccurrence(occurrence: Occurrence): EventForm {
  const { event } = occurrence
  const zone = event.all_day ? 'UTC' : localZone()
  const start = zonedParts(new Date(occurrence.startsAt).getTime(), zone)
  const end = occurrence.endsAt ? zonedParts(new Date(occurrence.endsAt).getTime(), zone) : null
  const endsKind = event.repeat_end_kind
  return {
    title: event.title,
    allDay: event.all_day === true,
    date: occurrence.date,
    startTime: event.all_day ? '09:00' : `${pad(start.h)}:${pad(start.min)}`,
    endTime: event.all_day ? '10:00' : end ? `${pad(end.h)}:${pad(end.min)}` : '',
    repeat: repeatChoiceOf(event.repeat_kind),
    every: event.repeat_every != null && event.repeat_every >= 1 ? event.repeat_every : 1,
    weekdays: parseWeekdays(event.repeat_days_of_week) ?? [],
    ends: endsKind === 'ON_DATE' ? 'on_date' : endsKind === 'AFTER_COUNT' ? 'after' : 'never',
    endsOn: event.repeat_end_date ?? '',
    endsAfter: event.repeat_end_count != null ? String(event.repeat_end_count) : '5',
    remind: event.remind_minutes_before != null ? String(event.remind_minutes_before) : '',
    location: event.location ?? '',
    notes: event.notes ?? '',
    visibility: visibilityOf(event),
  }
}

function minutesOf(time: string): number {
  const [h, m] = time.split(':').map(Number)
  return h * 60 + m
}

/** The first problem a person can fix without asking the engine, or null.
 * Mirrors the engine's own rules (title not blank, end after start, a weekly
 * series needs its days); the engine stays the authority and its sentence is
 * shown verbatim when it disagrees. */
export function validateForm(form: EventForm): string | null {
  if (form.title.trim() === '') return 'Give the event a title first.'
  if (form.date === '') return 'Pick a date.'
  if (!form.allDay) {
    if (form.startTime === '') return 'Pick a start time, or turn on All day.'
    if (form.endTime !== '' && minutesOf(form.endTime) <= minutesOf(form.startTime)) {
      return 'The end has to be after the start.'
    }
  }
  if (form.repeat === 'weekly' && form.weekdays.length === 0) {
    return 'Choose at least one day for a weekly repeat.'
  }
  if (form.repeat !== 'never') {
    if (form.ends === 'on_date') {
      if (form.endsOn === '') return 'Pick the date the repeat ends.'
      if (form.endsOn < form.date) return 'The repeat cannot end before it starts.'
    }
    if (form.ends === 'after') {
      const count = Number(form.endsAfter)
      if (!Number.isInteger(count) || count < 1) return 'The repeat has to run at least once.'
    }
  }
  return null
}

/** The writable fields of an event, as the form sets them. */
export interface EventFields {
  title: string
  starts_at: string
  ends_at: string | null
  all_day: boolean
  location: string | null
  notes: string | null
  repeat_kind: string | null
  repeat_every: number | null
  repeat_days_of_week: string | null
  repeat_day: number | null
  repeat_month: number | null
  repeat_end_kind: string | null
  repeat_end_date: string | null
  repeat_end_count: number | null
  remind_minutes_before: number | null
}

/** The instant a form's date and `HH:MM` name in the viewer's zone. */
function instantOf(date: string, time: string): string {
  const [h, m] = time.split(':').map(Number)
  return new Date(zonedInstant(toEpochDay(parseYmd(date)), h, m, localZone())).toISOString()
}

function nullIfBlank(text: string): string | null {
  const trimmed = text.trim()
  return trimmed === '' ? null : trimmed
}

/**
 * The body the engine takes. An all-day row's `starts_at` is UTC midnight of the
 * date it names (the rule `lib/day.ts` reads it back by); a timed one is the
 * viewer's wall clock as an instant. `repeat` is applied only when `repeating`
 * is true: "Just this one" stores a one-off, so its repeat fields are null.
 */
export function fieldsFromForm(form: EventForm, repeating: boolean = form.repeat !== 'never'): EventFields {
  const date = parseYmd(form.date)
  const startsAt = form.allDay ? `${form.date}T00:00:00.000Z` : instantOf(form.date, form.startTime)
  const endsAt = form.allDay || form.endTime === '' ? null : instantOf(form.date, form.endTime)
  const rule = repeating ? form.repeat : 'never'

  const fields: EventFields = {
    title: form.title.trim(),
    starts_at: startsAt,
    ends_at: endsAt,
    all_day: form.allDay,
    location: nullIfBlank(form.location),
    notes: nullIfBlank(form.notes),
    repeat_kind: null,
    repeat_every: null,
    repeat_days_of_week: null,
    repeat_day: null,
    repeat_month: null,
    repeat_end_kind: null,
    repeat_end_date: null,
    repeat_end_count: null,
    remind_minutes_before: form.remind === '' ? null : Number(form.remind),
  }
  if (rule === 'never') return fields

  fields.repeat_every = form.every >= 1 ? form.every : 1
  if (rule === 'daily') fields.repeat_kind = 'DAILY'
  if (rule === 'weekly') {
    fields.repeat_kind = 'WEEKLY'
    fields.repeat_days_of_week = [...form.weekdays]
      .sort((a, b) => a - b)
      .map((day) => WEEKDAY_NAMES[day])
      .join(',')
  }
  if (rule === 'monthly') {
    fields.repeat_kind = 'MONTHLY_ON_DATE'
    fields.repeat_day = date.d
  }
  if (rule === 'yearly') {
    fields.repeat_kind = 'YEARLY'
    fields.repeat_every = null
    fields.repeat_day = date.d
    fields.repeat_month = date.m
  }
  if (form.ends === 'on_date') {
    fields.repeat_end_kind = 'ON_DATE'
    fields.repeat_end_date = form.endsOn
  } else if (form.ends === 'after') {
    fields.repeat_end_kind = 'AFTER_COUNT'
    fields.repeat_end_count = Number(form.endsAfter)
  } else {
    fields.repeat_end_kind = 'NEVER'
  }
  return fields
}

/**
 * The fields for "All of them": the edits applied to the SERIES, not the one
 * occurrence that was pressed.
 *
 * The form's date is the occurrence's, so a changed date is a SHIFT: the series
 * start moves by the number of days the date moved, and keeps the series' own
 * start date otherwise. Without this, pressing Save on the third Tuesday of a
 * series would rewrite the series to start on that Tuesday and silently drop the
 * first two.
 */
export function fieldsForSeries(form: EventForm, occurrence: Occurrence): EventFields {
  const base = fieldsFromForm(form)
  const { event } = occurrence
  if (event.starts_at == null) return base
  const zone = event.all_day ? 'UTC' : localZone()
  const seriesStart = zonedParts(new Date(event.starts_at).getTime(), zone)
  const shift = toEpochDay(parseYmd(form.date)) - toEpochDay(parseYmd(occurrence.date))
  const seriesDay = toEpochDay(seriesStart) + shift
  const seriesDate = ymdString(fromEpochDay(seriesDay))
  const startsAt = form.allDay
    ? `${seriesDate}T00:00:00.000Z`
    : instantOf(seriesDate, form.startTime)
  const endsAt = form.allDay || form.endTime === '' ? null : instantOf(seriesDate, form.endTime)
  return {
    ...base,
    starts_at: startsAt,
    ends_at: endsAt,
    // A monthly or yearly series stays on its own day unless the date moved it.
    repeat_day:
      base.repeat_kind === 'MONTHLY_ON_DATE' || base.repeat_kind === 'YEARLY'
        ? fromEpochDay(seriesDay).d
        : base.repeat_day,
    repeat_month: base.repeat_kind === 'YEARLY' ? fromEpochDay(seriesDay).m : base.repeat_month,
  }
}

/** The `origin_guid` of the one-off an edited occurrence becomes, so a retry
 * finds the row it already made rather than adding a second (spec D4). */
export function occurrenceGuid(event: Pick<Event, 'id'>, date: string): string {
  return `${event.id}:${date}`
}
