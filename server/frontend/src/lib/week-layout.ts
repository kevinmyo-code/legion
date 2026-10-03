import { localZone, zonedParts, type Occurrence } from '@/lib/recurrence'

/**
 * Laying a day's occurrences out on the week view's hour grid.
 *
 * The grid runs 6:00 to midnight (spec D10: "hour rows 6:00 to 23:00", the 23:00
 * row ending at midnight). Two kinds of occurrence never go on it:
 *
 * - **All-day rows**, which have no time.
 * - **Tasks**, which are deadlines, not durations. Nine coursework tasks all due
 *   at `11:59 PM` would be nine blocks piled on the last minute of the grid; they
 *   sit in the all-day lane with their time written on them instead.
 *
 * What is left is timed events. Events that overlap in time sit SIDE BY SIDE,
 * each taking an equal share of the column, the way every calendar draws them:
 * a cluster is a run of events each overlapping the next, columns are handed out
 * greedily to the first one free, and the cluster's width is split by the most
 * columns any moment of it needed.
 */

export const GRID_START_MINUTES = 6 * 60
export const GRID_END_MINUTES = 24 * 60
/** The shortest block drawn: a 5 minute call is still a thing you can press. */
export const MIN_BLOCK_MINUTES = 30
/** An event with no end is drawn as an hour. */
export const DEFAULT_MINUTES = 60

export interface Placed {
  occurrence: Occurrence
  /** Minutes after local midnight, clamped to the grid. */
  startMinutes: number
  endMinutes: number
  /** Zero-based column within its cluster, and how many the cluster has. */
  column: number
  columns: number
}

/** Whether an occurrence belongs on the hour grid rather than the all-day lane. */
export function isTimed(occurrence: Occurrence): boolean {
  return occurrence.event.all_day !== true && occurrence.event.kind !== 'task'
}

function minutesOfDay(iso: string, zone: string): number {
  const p = zonedParts(new Date(iso).getTime(), zone)
  return p.h * 60 + p.min
}

export function layoutTimed(occurrences: readonly Occurrence[], zone: string = localZone()): Placed[] {
  const blocks = occurrences
    .filter(isTimed)
    .map((occurrence) => {
      const rawStart = minutesOfDay(occurrence.startsAt, zone)
      let rawEnd = rawStart + DEFAULT_MINUTES
      if (occurrence.endsAt !== null) {
        const endMinutes = minutesOfDay(occurrence.endsAt, zone)
        // An end on a later date than the start runs to midnight on this one.
        const spansMidnight = endMinutes < rawStart || endMinutes === rawStart
        rawEnd = spansMidnight ? 1440 : endMinutes
      }
      const startMinutes = Math.min(Math.max(rawStart, GRID_START_MINUTES), GRID_END_MINUTES - MIN_BLOCK_MINUTES)
      const endMinutes = Math.min(Math.max(rawEnd, startMinutes + MIN_BLOCK_MINUTES), GRID_END_MINUTES)
      return { occurrence, startMinutes, endMinutes }
    })
    .sort(
      (a, b) =>
        a.startMinutes - b.startMinutes ||
        b.endMinutes - a.endMinutes ||
        a.occurrence.event.title.localeCompare(b.occurrence.event.title),
    )

  const placed: Placed[] = []
  let cluster: Placed[] = []
  let columnEnds: number[] = []
  let clusterEnd = -1

  const closeCluster = () => {
    const columns = Math.max(columnEnds.length, 1)
    for (const block of cluster) block.columns = columns
    placed.push(...cluster)
    cluster = []
    columnEnds = []
  }

  for (const block of blocks) {
    if (cluster.length > 0 && block.startMinutes >= clusterEnd) closeCluster()
    let column = columnEnds.findIndex((end) => end <= block.startMinutes)
    if (column === -1) {
      column = columnEnds.length
      columnEnds.push(block.endMinutes)
    } else {
      columnEnds[column] = block.endMinutes
    }
    cluster.push({ ...block, column, columns: 1 })
    clusterEnd = Math.max(clusterEnd, block.endMinutes)
  }
  if (cluster.length > 0) closeCluster()
  return placed
}

/** "9 AM" for a grid row label. */
export function hourLabel(hour: number): string {
  const d = new Date(2000, 0, 1, hour, 0)
  return d.toLocaleTimeString(undefined, { hour: 'numeric' })
}
