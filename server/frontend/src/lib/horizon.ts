import type { Event, EventSkip } from '@/api/types'
import { dateForEpochDay, epochDay } from '@/lib/day'
import { occurrencesBetween, type Occurrence } from '@/lib/recurrence'

/**
 * The week's SHAPE, not its rows.
 *
 * `docs/design/today.md` read Cozi and concluded that Today should show today
 * and tomorrow only, because a week of rows is a wall of rows. That research is
 * sound and it studied the wrong user: Cozi is a family calendar, and Kevin's
 * data is coursework with deadline cliffs. Read from the live engine on
 * 2026-09-12, the next fortnight looked like this (`e` events, `t` tasks):
 *
 * ```
 * today  +1   +2   +3   +4   +5   +6   +7   +8   +9  +10  +11  +12  +13
 *  1e    9t    -   3e   1t   3e   2t    -   4t    -  3e   2t   3e   1t
 * ```
 *
 * Nine deadlines land on one day. **The two-day screen was honest that day only
 * because tomorrow happened to be the cliff**; by Monday those nine are past,
 * the next four are six days out, and Today renders nothing at all while being
 * perfectly truthful.
 *
 * So the answer is not more rows - nine identical `11:59 PM` rows carry no
 * information in their times either. It is a horizon you can read the shape of:
 * one cell per day, carrying a count. A cliff then looks like a cliff without a
 * single extra deadline on screen, and it costs one strip of vertical space
 * rather than seven sections.
 */

/** How far the strip looks. Two weeks covers a semester's rhythm - the 2026-09-12
 * read showed clusters at +1, +4, +6, +8, +11 and +13 - without becoming a month
 * view nobody scans. */
export const HORIZON_DAYS = 14

export interface HorizonCell {
  /** Local epoch day, the same unit `todayEpochDay()` and a tick's `day` use. */
  day: number
  /** Days from today: 0 is today, 1 tomorrow. */
  offset: number
  date: Date
  /** Things that must be DONE. This is the number that matters. */
  tasks: number
  /** Things that merely happen. Counted separately: a day with three lectures
   * and a day with three deadlines are not the same kind of busy. */
  events: number
  /** Tasks already ticked - carried so a finished day can read as finished
   * rather than as empty. */
  tasksDone: number
}

interface DayCounts {
  tasks: number
  events: number
  tasksDone: number
}

/**
 * Sorts every live, anchored occurrence into one bucket per day in `days`. The
 * one shared core both `buildHorizon` and `buildMonth` run through, so the
 * all-day UTC-midnight recovery, the repeat expansion with its skips
 * (`lib/recurrence.ts`) and the task/event split are solved in exactly one place
 * rather than twice, three inches apart.
 *
 * Rows with no `starts_at` are excluded rather than bucketed at an arbitrary
 * day - the rule `occurrencesBetween` applies, and the same one the phone's
 * `activeByKindInLocalWindow` applies: a row with no anchor cannot be placed in
 * a window.
 */
function bucketByDay(
  days: number[],
  events: Event[],
  skips: readonly EventSkip[] | undefined,
): Map<number, DayCounts> {
  const buckets = new Map<number, DayCounts>(
    days.map((day) => [day, { tasks: 0, events: 0, tasksDone: 0 }]),
  )
  if (days.length === 0) return buckets
  const occurrences = occurrencesBetween(events, skips, Math.min(...days), Math.max(...days))
  for (const { event, day } of occurrences) {
    const bucket = buckets.get(day)
    if (!bucket) continue
    if (event.kind === 'task') {
      bucket.tasks += 1
      if (event.done) bucket.tasksDone += 1
    } else {
      bucket.events += 1
    }
  }
  return buckets
}

/**
 * One cell per day from today, inclusive, for [HORIZON_DAYS] days.
 */
export function buildHorizon(
  today: number,
  events: Event[],
  days = HORIZON_DAYS,
  skips?: readonly EventSkip[],
): HorizonCell[] {
  const cellDays: number[] = []
  for (let offset = 0; offset < days; offset += 1) cellDays.push(today + offset)
  const buckets = bucketByDay(cellDays, events, skips)
  return cellDays.map((day) => ({
    day,
    offset: day - today,
    date: dateForEpochDay(day),
    ...buckets.get(day)!,
  }))
}

export interface MonthCell extends DayCounts {
  /** Local epoch day, matching `HorizonCell.day`. */
  day: number
  date: Date
  /** False for a padding day from the previous or next month, kept so the
   * grid can draw full weeks without a jagged first or last row - the same
   * shape a paper calendar has. A padding day is still bucketed against real
   * events: "0 tasks on Aug 31" has to stay a true statement even when Aug 31
   * is rendered dim, at the tail of August's row. */
  inMonth: boolean
}

/**
 * Full calendar weeks (Sunday-start) covering `monthAnchor`'s month, padded
 * with the trailing days of the prior month and the leading days of the next
 * so every row has seven cells. Reuses `bucketByDay`, so an all-day row lands
 * on the date it was actually written for - the same trap `buildHorizon` and
 * the phone's `activeByKindInLocalWindow` both had to solve.
 */
export function buildMonth(monthAnchor: Date, events: Event[], skips?: readonly EventSkip[]): MonthCell[] {
  const year = monthAnchor.getFullYear()
  const month = monthAnchor.getMonth()
  const firstOfMonth = epochDay(new Date(year, month, 1))
  const firstWeekday = dateForEpochDay(firstOfMonth).getDay() // 0 = Sunday
  const daysInMonth = new Date(year, month + 1, 0).getDate()
  const lastOfMonth = firstOfMonth + daysInMonth - 1
  const lastWeekday = dateForEpochDay(lastOfMonth).getDay()

  const start = firstOfMonth - firstWeekday
  const end = lastOfMonth + (6 - lastWeekday)

  const cellDays: number[] = []
  for (let day = start; day <= end; day += 1) cellDays.push(day)

  const buckets = bucketByDay(cellDays, events, skips)
  return cellDays.map((day) => {
    const date = dateForEpochDay(day)
    return { day, date, inMonth: date.getMonth() === month, ...buckets.get(day)! }
  })
}

/**
 * Tasks that are past their moment and still not done.
 *
 * **Deliberately kept, not hidden.** A deadline that slid is still work, and a
 * Today screen that quietly drops it is telling the same kind of lie as one that
 * renders an unread error as an empty day. They are shown apart from today's
 * own work so the distinction stays legible.
 *
 * Bounded by [OVERDUE_WINDOW_DAYS] rather than running to the beginning of time:
 * there are 92 past events in this household, and a coursework deadline from
 * five weeks ago is history, not a task list.
 */
export const OVERDUE_WINDOW_DAYS = 14

export function overdueOccurrences(
  today: number,
  events: Event[],
  skips?: readonly EventSkip[],
): Occurrence[] {
  return occurrencesBetween(
    events.filter((event) => event.kind === 'task' && !event.done),
    skips,
    today - OVERDUE_WINDOW_DAYS,
    today - 1,
  ).sort((a, b) => b.startsAt.localeCompare(a.startsAt))
}

/** The same rows as `overdueOccurrences`, as bare events. */
export function overdueTasks(today: number, events: Event[], skips?: readonly EventSkip[]): Event[] {
  return overdueOccurrences(today, events, skips).map((occurrence) => occurrence.event)
}

export interface CourseGroup {
  /** The course, or `null` for a task whose title carries no course prefix. */
  course: string | null
  items: Event[]
}

/**
 * Splits `COSC 4320 Software Engineering · Module 2: Assignment 2` into its
 * course and its actual task.
 *
 * The separator is the middle dot the Canvas import writes. A title without one
 * is returned whole with a null course rather than guessed at - a heuristic that
 * chopped on the first colon or dash would mangle
 * `Module 2: Assignment 2 - Waterfall Model`, which contains both.
 */
export function splitCourse(title: string): { course: string | null; label: string } {
  const index = title.indexOf('·')
  if (index === -1) return { course: null, label: title.trim() }
  const course = title.slice(0, index).trim()
  const label = title.slice(index + 1).trim()
  if (!course || !label) return { course: null, label: title.trim() }
  return { course, label }
}

/**
 * Groups a day's tasks by course, preserving first-seen order.
 *
 * Nine rows that all read `11:59 PM` are nine rows whose times carry no
 * information. The course is the only thing that distinguishes them at a glance,
 * so it becomes the heading rather than a repeated prefix on every line.
 */
export function groupByCourse(events: Event[]): CourseGroup[] {
  const groups: CourseGroup[] = []
  const byCourse = new Map<string, CourseGroup>()
  for (const event of events) {
    const { course } = splitCourse(event.title)
    const key = course ?? ' none'
    let group = byCourse.get(key)
    if (!group) {
      group = { course, items: [] }
      byCourse.set(key, group)
      groups.push(group)
    }
    group.items.push(event)
  }
  return groups
}

/**
 * "9 due tomorrow" as a sentence, because a count is a fact the screen should
 * state rather than one the reader derives by counting rows.
 *
 * Returns null when there is nothing to say - an absent sentence, never a
 * "0 due" one, which would read as a claim about a day nobody asked about.
 */
export function loadSentence(cell: HorizonCell | undefined): string | null {
  if (!cell || cell.tasks === 0) return null
  const when = cell.offset === 0 ? 'today' : cell.offset === 1 ? 'tomorrow' : null
  const outstanding = cell.tasks - cell.tasksDone
  const noun = cell.tasks === 1 ? 'thing' : 'things'
  if (outstanding === 0) {
    return when ? `All ${cell.tasks} done ${when}.` : `All ${cell.tasks} done.`
  }
  const head = `${cell.tasks} ${noun} due${when ? ` ${when}` : ''}`
  return cell.tasksDone > 0 ? `${head}, ${cell.tasksDone} already done.` : `${head}.`
}

/** The next days carrying unfinished work, for the desktop's "next up" list.
 * Today and tomorrow are excluded - they are already rendered in full above it,
 * and repeating them would be the wall of rows this whole module avoids. */
export function nextUp(cells: HorizonCell[], limit = 4): HorizonCell[] {
  return cells
    .filter((cell) => cell.offset >= 2)
    .filter((cell) => cell.tasks > cell.tasksDone)
    .slice(0, limit)
}
