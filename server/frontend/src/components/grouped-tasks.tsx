import { EventRow } from '@/components/event-row'
import { groupOccurrencesByCourse } from '@/lib/horizon'
import type { Occurrence } from '@/lib/recurrence'

/** A day's tasks, grouped by course. Nine rows that all read `11:59 PM` are nine
 * rows whose times say nothing; the course is the only thing that separates them
 * at a glance, so it becomes a heading instead of a prefix repeated nine times. */
export function GroupedTasks({ tasks }: { tasks: Occurrence[] }) {
  const groups = groupOccurrencesByCourse(tasks)
  if (groups.length <= 1) {
    return (
      <ul className="flex flex-col gap-1.5">
        {tasks.map((occurrence) => (
          <EventRow key={`${occurrence.event.id}:${occurrence.date}`} occurrence={occurrence} />
        ))}
      </ul>
    )
  }
  return (
    <div className="flex flex-col gap-3">
      {groups.map((group) => (
        <div key={group.course ?? 'none'}>
          {group.course && (
            <h4 className="mb-1 px-1.5 text-[0.8125rem] font-medium text-muted-foreground">{group.course}</h4>
          )}
          <ul className="flex flex-col gap-1.5">
            {group.items.map((occurrence) => (
              <EventRow
                key={`${occurrence.event.id}:${occurrence.date}`}
                occurrence={occurrence}
                showCourse={false}
              />
            ))}
          </ul>
        </div>
      ))}
    </div>
  )
}
