import {
  workoutPlanItems,
  workoutPlans,
  workoutSetLogs,
  type WorkoutPlan,
  type WorkoutPlanItem,
  type WorkoutSetLog,
} from '@/api/aspects'
import { useRows, wire } from '@/api/synced'
import { Skeleton } from '@/components/ui/skeleton'
import { Meter } from '@/components/workbench/charts'
import { CrudPanel } from '@/components/workbench/crud-panel'
import { ErrorSentence, Panel } from '@/components/workbench/page'
import type { Values } from '@/components/workbench/record-form'
import { inForceOn, weekProgress, workoutDaysThisWeek } from '@/lib/body'
import {
  formatDay,
  formatInstant,
  formatNumber,
  fromLocalInput,
  plural,
  todayDay,
  toLocalInput,
  weekStartOf,
} from '@/lib/figures'

/**
 * Workouts: this week against the plan, the plan itself, and the sets logged.
 *
 * The plan is history like every other target here: a plan row and a plan item
 * each start on an `effective_from_week` date, and the latest one not after this
 * week's Monday is the one in force. "This week" is Monday to Sunday in the
 * viewer's own calendar.
 */

const UNITS = [
  { value: '', label: 'No weight' },
  { value: 'lbs', label: 'lbs' },
  { value: 'kg', label: 'kg' },
]

function WeekPanel() {
  const plans = useRows(workoutPlans)
  const items = useRows(workoutPlanItems)
  const sets = useRows(workoutSetLogs)
  const today = todayDay()
  const monday = weekStartOf(today)

  // The panel is only as complete as the least complete of the three reads. If
  // any is still arriving it waits; if any failed with nothing to show it says
  // so, rather than measuring sets against a plan it could not read.
  const queries = [plans, items, sets]
  const failed = queries.find((query) => query.data === undefined && !query.isPending)

  return (
    <Panel
      title="This week"
      description={`Monday ${formatDay(monday)} to Sunday, in your own calendar.`}
    >
      {queries.some((query) => query.isPending) ? (
        <Skeleton className="h-24 w-full" aria-busy="true" aria-label="Loading this week" />
      ) : failed ? (
        <ErrorSentence>
          Could not reach the engine, so this is not the real picture of this week. {failed.error?.message}
        </ErrorSentence>
      ) : (
        <WeekBody plans={plans.data ?? []} items={items.data ?? []} sets={sets.data ?? []} today={today} />
      )}
    </Panel>
  )
}

function WeekBody({
  plans,
  items,
  sets,
  today,
}: {
  plans: WorkoutPlan[]
  items: WorkoutPlanItem[]
  sets: WorkoutSetLog[]
  today: string
}) {
  const monday = weekStartOf(today)
  const plan = inForceOn(plans, monday, (row) => row.effective_from_week)
  const progress = weekProgress(items, sets, today)
  const days = workoutDaysThisWeek(sets, today)

  if (plan === null && progress.length === 0) {
    return (
      <p className="rounded-control bg-surface-2 px-4 py-3 text-[0.9375rem] text-muted-foreground">
        No workout plan is in force this week, so there is nothing to measure sets against. Add a plan below.
        {days > 0 && ` You have logged sets on ${plural(days, 'day')} this week.`}
      </p>
    )
  }

  return (
    <div className="flex flex-col gap-4">
      {plan ? (
        <Meter
          label="Workout days"
          value={days}
          target={plan.sessions_per_week}
          valueText={plural(days, 'day')}
          targetText={`${plural(plan.sessions_per_week, 'session')} planned`}
        />
      ) : (
        <p className="text-[0.9375rem] text-muted-foreground">
          No weekly session count is planned. Sets were logged on {plural(days, 'day')} this week.
        </p>
      )}
      {progress.length === 0 ? (
        <p className="text-[0.9375rem] text-muted-foreground">
          The plan has no exercises in force this week, so sets are not measured per exercise.
        </p>
      ) : (
        progress.map((row) => (
          <Meter
            key={row.exercise}
            label={row.repsPerSet ? `${row.exercise} (${row.repsPerSet} reps a set)` : row.exercise}
            value={row.setsDone}
            target={row.targetSets}
            valueText={plural(row.setsDone, 'set')}
            targetText={plural(row.targetSets, 'set')}
          />
        ))
      )}
    </div>
  )
}

const WEEK_HINT = 'The Monday the change starts. The latest one not after a week is the one in force that week.'

export function WorkoutsTab() {
  return (
    <div className="flex flex-col gap-6">
      <WeekPanel />
      <CrudPanel
        table={workoutSetLogs}
        title="Sets logged"
        description="Each row is one exercise: how many sets, and optionally reps and weight."
        addLabel="Log sets"
        what="workout sets"
        empty="No sets logged yet. Log your first and this week's meters will start to move."
        sort={(a, b) => b.logged_at.localeCompare(a.logged_at)}
        limit={20}
        columns={[
          { header: 'When', cell: (row) => formatInstant(row.logged_at) },
          { header: 'Exercise', cell: (row) => <span className="font-medium">{row.exercise}</span> },
          {
            header: 'Sets',
            align: 'right',
            cell: (row) => (row.reps ? `${row.sets} x ${row.reps}` : String(row.sets)),
          },
          {
            header: 'Weight',
            align: 'right',
            cell: (row) =>
              row.weight_value === null || row.weight_value === undefined
                ? 'Not logged'
                : `${formatNumber(row.weight_value)} ${row.weight_unit ?? ''}`.trim(),
          },
        ]}
        fields={[
          { name: 'exercise', label: 'Exercise', kind: 'text', required: true, placeholder: 'Squat' },
          { name: 'sets', label: 'Sets', kind: 'number', required: true, step: '1', min: '1' },
          { name: 'reps', label: 'Reps per set', kind: 'number', step: '1', min: '1' },
          { name: 'weight_value', label: 'Weight', kind: 'number', step: '0.5', min: '0' },
          { name: 'weight_unit', label: 'Weight unit', kind: 'select', options: UNITS },
          { name: 'logged_at', label: 'When', kind: 'datetime', required: true },
        ]}
        identity={(row) => row.origin_guid}
        initial={(row): Values => ({
          exercise: row?.exercise ?? '',
          sets: row ? String(row.sets) : '',
          reps: row?.reps ? String(row.reps) : '',
          weight_value: row?.weight_value != null ? String(row.weight_value) : '',
          weight_unit: row?.weight_unit ?? '',
          logged_at: toLocalInput(row?.logged_at ?? new Date()),
        })}
        toBody={(values, _row, guid) => {
          const hasWeight = values.weight_value.trim() !== ''
          return wire<WorkoutSetLog>({
            exercise: values.exercise.trim(),
            sets: Math.round(Number(values.sets)),
            reps: values.reps.trim() === '' ? null : Math.round(Number(values.reps)),
            weight_value: hasWeight ? Number(values.weight_value) : null,
            weight_unit: hasWeight ? values.weight_unit || 'lbs' : null,
            logged_at: fromLocalInput(values.logged_at),
            trust_tier: 'REPORTED',
            origin_guid: guid,
          })
        }}
        rowLabel={(row) => `the ${row.exercise} sets`}
        deleteConsequence="These sets are removed for everyone in the household."
      />
      <CrudPanel
        table={workoutPlans}
        title="Weekly plan"
        description="How many sessions a week. A new plan starts on its own Monday."
        addLabel="Add a plan"
        what="workout plans"
        empty="No plan yet. Without one, the week shows sets but nothing to measure them against."
        sort={(a, b) => b.effective_from_week.localeCompare(a.effective_from_week)}
        columns={[
          { header: 'From the week of', cell: (row) => formatDay(row.effective_from_week) },
          { header: 'Sessions a week', align: 'right', cell: (row) => row.sessions_per_week },
        ]}
        fields={[
          { name: 'sessions_per_week', label: 'Sessions a week', kind: 'number', required: true, step: '1', min: '0' },
          { name: 'effective_from_week', label: 'From the week of', kind: 'date', required: true, hint: WEEK_HINT },
        ]}
        identity={(row) => row.origin_guid}
        initial={(row): Values => ({
          sessions_per_week: row ? String(row.sessions_per_week) : '',
          effective_from_week: row?.effective_from_week ?? weekStartOf(todayDay()),
        })}
        toBody={(values, _row, guid) =>
          wire<WorkoutPlan>({
            sessions_per_week: Math.round(Number(values.sessions_per_week)),
            effective_from_week: values.effective_from_week,
            origin_guid: guid,
          })
        }
        rowLabel={(row) => `the plan from ${formatDay(row.effective_from_week)}`}
        deleteConsequence="This plan is removed. Weeks it covered fall back to the one before it."
      />
      <CrudPanel
        table={workoutPlanItems}
        title="Planned exercises"
        description="Sets a week for each exercise. Each row starts on its own Monday."
        addLabel="Add an exercise"
        what="planned exercises"
        empty="No exercises planned yet."
        sort={(a, b) => a.exercise.localeCompare(b.exercise) || b.effective_from_week.localeCompare(a.effective_from_week)}
        columns={[
          { header: 'Exercise', cell: (row) => <span className="font-medium">{row.exercise}</span> },
          { header: 'Sets a week', align: 'right', cell: (row) => row.target_sets_per_week },
          { header: 'Reps a set', align: 'right', cell: (row) => row.reps_per_set ?? 'Not set' },
          { header: 'From the week of', cell: (row) => formatDay(row.effective_from_week) },
        ]}
        fields={[
          { name: 'exercise', label: 'Exercise', kind: 'text', required: true, placeholder: 'Squat' },
          { name: 'target_sets_per_week', label: 'Sets a week', kind: 'number', required: true, step: '1', min: '1' },
          { name: 'reps_per_set', label: 'Reps a set', kind: 'number', step: '1', min: '1' },
          { name: 'effective_from_week', label: 'From the week of', kind: 'date', required: true, hint: WEEK_HINT },
        ]}
        identity={(row) => row.origin_guid}
        initial={(row): Values => ({
          exercise: row?.exercise ?? '',
          target_sets_per_week: row ? String(row.target_sets_per_week) : '',
          reps_per_set: row?.reps_per_set ? String(row.reps_per_set) : '',
          effective_from_week: row?.effective_from_week ?? weekStartOf(todayDay()),
        })}
        toBody={(values, _row, guid) =>
          wire<WorkoutPlanItem>({
            exercise: values.exercise.trim(),
            target_sets_per_week: Math.round(Number(values.target_sets_per_week)),
            reps_per_set: values.reps_per_set.trim() === '' ? null : Math.round(Number(values.reps_per_set)),
            effective_from_week: values.effective_from_week,
            origin_guid: guid,
          })
        }
        rowLabel={(row) => `${row.exercise} from ${formatDay(row.effective_from_week)}`}
        deleteConsequence="This row is removed. Weeks it covered fall back to the one before it."
      />
    </div>
  )
}
