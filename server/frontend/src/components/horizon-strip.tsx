import type { HorizonCell } from '@/lib/horizon'

/**
 * The fortnight as a shape rather than a list.
 *
 * One cell per day. The number is unfinished TASKS - the things that must be
 * done - because that is the figure that makes a week hard. Events get a dot
 * rather than a number: three lectures and three deadlines on the same date are
 * not the same kind of busy, and conflating them would hide exactly the cliff
 * this strip exists to reveal.
 *
 * No colour carries meaning on its own. A cell's count is written on it, so the
 * strip degrades to something still readable in greyscale, and the emphasis is
 * weight and a ring rather than hue. That is the same posture CLAUDE.md §4 rule
 * 7 takes about provenance: say it, never imply it with a colour.
 */
export function HorizonStrip({ cells }: { cells: HorizonCell[] }) {
  const busiest = Math.max(1, ...cells.map((cell) => cell.tasks - cell.tasksDone))

  return (
    <div>
      <div className="mb-2 flex items-baseline justify-between">
        <h2 className="text-sm font-semibold text-muted-foreground">The next two weeks</h2>
        <span className="text-xs text-muted-foreground">unfinished, by day</span>
      </div>
      <ol className="flex gap-1">
        {cells.map((cell) => {
          const outstanding = cell.tasks - cell.tasksDone
          const weight = outstanding / busiest
          return (
            <li key={cell.day} className="flex min-w-0 flex-1 flex-col items-center gap-1">
              <span
                className={
                  cell.offset === 0
                    ? 'text-[0.6rem] font-semibold uppercase text-foreground'
                    : 'text-[0.6rem] uppercase text-muted-foreground'
                }
              >
                {cell.date.toLocaleDateString(undefined, { weekday: 'narrow' })}
              </span>
              <div
                className={[
                  'flex h-9 w-full items-center justify-center rounded-md border text-xs tabular-nums',
                  cell.offset === 0 ? 'border-foreground/40' : 'border-border',
                  outstanding > 0 ? 'font-semibold text-foreground' : 'text-muted-foreground',
                ].join(' ')}
                style={
                  outstanding > 0
                    ? { backgroundColor: `color-mix(in oklab, var(--primary) ${8 + weight * 22}%, transparent)` }
                    : undefined
                }
                title={`${cell.date.toLocaleDateString(undefined, { weekday: 'long', month: 'short', day: 'numeric' })} - ${outstanding} unfinished, ${cell.events} on the calendar`}
              >
                {outstanding > 0 ? outstanding : cell.events > 0 ? '·' : ''}
              </div>
              <span className="text-[0.6rem] tabular-nums text-muted-foreground">
                {cell.date.getDate()}
              </span>
            </li>
          )
        })}
      </ol>
    </div>
  )
}
