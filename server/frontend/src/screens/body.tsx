import { sleepLogs, sleepTargets, bodyweightLogs, type BodyweightLog, type SleepLog, type SleepTarget } from '@/api/aspects'
import { useRows, wire } from '@/api/synced'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Bars, Meter, Sparkline } from '@/components/workbench/charts'
import { CrudPanel } from '@/components/workbench/crud-panel'
import { Loaded } from '@/components/workbench/loaded'
import { PageHeader } from '@/components/workbench/page'
import type { FieldSpec, Values } from '@/components/workbench/record-form'
import { MealsTab } from '@/screens/body-food'
import { WorkoutsTab } from '@/screens/body-workouts'
import { inForceOn } from '@/lib/body'
import {
  formatInstant,
  formatDay,
  formatMinutes,
  formatNumber,
  formatShortDay,
  fromLocalInput,
  todayDay,
  toLocalInput,
} from '@/lib/figures'

/**
 * `/body`: bodyweight, sleep, meals against targets, and workouts. Every table
 * here is a table the web may write, so each panel is a list with a form.
 *
 * Trust, as this screen carries it:
 *
 * - A target is a number someone CHOSE, printed plain. A logged macro on a meal
 *   is an ESTIMATE (the model's guess from the description, CLAUDE.md section 4
 *   rule 5) and carries the word beside it (`body-food.tsx`).
 * - An entry typed here is `REPORTED`: the person said so, nothing measured it.
 *   Editing an entry the engine marked `PROVEN` says it becomes reported,
 *   because a proven number someone retyped is not proven any more.
 * - Charts are a sparkline and bars, drawn beside the numbers they plot, never
 *   alone, and never drawn at all with nothing to plot: the panel says so in a
 *   sentence.
 */

const UNITS = [
  { value: 'lbs', label: 'lbs' },
  { value: 'kg', label: 'kg' },
]

// ---- Weight ---------------------------------------------------------------

const WEIGHT_FIELDS: FieldSpec[] = [
  { name: 'weight_value', label: 'Weight', kind: 'number', required: true, step: '0.1', min: '0' },
  { name: 'weight_unit', label: 'Unit', kind: 'select', required: true, options: UNITS },
  { name: 'logged_at', label: 'When', kind: 'datetime', required: true },
]

function WeightTrend({ rows }: { rows: BodyweightLog[] }) {
  // Newest first on the way in; the line wants oldest first. Entries in a
  // different unit from the newest are not on the line - the chart never mixes
  // pounds and kilograms into one slope - and the panel says how many.
  const newest = rows[0]
  const sameUnit = rows.filter((row) => row.weight_unit === newest.weight_unit)
  const otherUnit = rows.length - sameUnit.length
  const points = sameUnit
    .slice(0, 30)
    .reverse()
    .map((row) => ({ label: formatShortDay(row.logged_at), value: row.weight_value }))

  return (
    <div className="flex flex-wrap items-center gap-x-8 gap-y-3 rounded-card bg-surface-2 p-4">
      <div>
        <p className="text-[2rem] leading-none font-medium tabular-nums">
          {formatNumber(newest.weight_value)} {newest.weight_unit}
        </p>
        <p className="mt-1 text-[0.8125rem] text-muted-foreground">Latest, {formatInstant(newest.logged_at)}</p>
      </div>
      <div className="min-w-64 flex-1">
        {points.length >= 2 ? (
          <>
            <Sparkline
              points={points}
              unit={newest.weight_unit}
              description={`Bodyweight over the last ${points.length} entries in ${newest.weight_unit}, from ${points[0].value} to ${points[points.length - 1].value}`}
            />
            <p className="text-[0.8125rem] text-muted-foreground">
              Last {points.length} entries in {newest.weight_unit}, oldest on the left.
              {otherUnit > 0 && ` ${otherUnit} older ${otherUnit === 1 ? 'entry is' : 'entries are'} in another unit and not on this line.`}
            </p>
          </>
        ) : (
          <p className="text-[0.9375rem] text-muted-foreground">
            One entry so far, so there is no trend to draw yet.
          </p>
        )}
      </div>
    </div>
  )
}

function WeightPanel() {
  return (
    <CrudPanel
      table={bodyweightLogs}
      title="Bodyweight"
      description="Entries typed here are reported by you; nothing on the web measures them."
      addLabel="Log weight"
      what="bodyweight entries"
      empty="No weight logged yet. Add the first entry and a trend will appear once there are two."
      sort={(a, b) => b.logged_at.localeCompare(a.logged_at)}
      limit={10}
      above={(rows) => (rows.length > 0 ? <WeightTrend rows={rows} /> : null)}
      columns={[
        { header: 'When', cell: (row) => formatInstant(row.logged_at) },
        {
          header: 'Weight',
          align: 'right',
          cell: (row) => `${formatNumber(row.weight_value)} ${row.weight_unit}`,
        },
        { header: 'Source', cell: (row) => (row.trust_tier === 'PROVEN' ? 'Proven' : 'Reported') },
      ]}
      fields={WEIGHT_FIELDS}
      identity={(row) => row.origin_guid}
      initial={(row): Values => ({
        weight_value: row ? String(row.weight_value) : '',
        weight_unit: row?.weight_unit ?? 'lbs',
        logged_at: toLocalInput(row?.logged_at ?? new Date()),
      })}
      toBody={(values, _row, guid) =>
        wire<BodyweightLog>({
          weight_value: Number(values.weight_value),
          weight_unit: values.weight_unit,
          logged_at: fromLocalInput(values.logged_at),
          trust_tier: 'REPORTED',
          origin_guid: guid,
        })
      }
      formExtra={(row) =>
        row?.trust_tier === 'PROVEN' ? (
          <p className="rounded-control bg-surface-3 px-4 py-3 text-[0.8125rem]">
            This entry is proven. Saving a change makes it reported, because a proven number that was retyped is
            not proven any more.
          </p>
        ) : null
      }
      rowLabel={(row) => `the ${formatNumber(row.weight_value)} ${row.weight_unit} entry`}
      deleteConsequence="This entry is removed for everyone in the household."
    />
  )
}

// ---- Sleep ----------------------------------------------------------------

const DURATION_FIELDS: FieldSpec[] = [
  { name: 'hours', label: 'Hours', kind: 'number', required: true, min: '0', max: '24', step: '1' },
  { name: 'minutes', label: 'Minutes', kind: 'number', required: true, min: '0', max: '59', step: '1' },
]

function splitMinutes(total: number | undefined): { hours: string; minutes: string } {
  if (total === undefined) return { hours: '', minutes: '0' }
  return { hours: String(Math.floor(total / 60)), minutes: String(total % 60) }
}

function minutesOf(values: Values): number {
  return Math.round(Number(values.hours)) * 60 + Math.round(Number(values.minutes))
}

const SLEEP_FIELDS: FieldSpec[] = [
  { name: 'sleep_date', label: 'Night of', kind: 'date', required: true, hint: 'The date you woke up on.' },
  ...DURATION_FIELDS,
  {
    name: 'quality',
    label: 'Quality',
    kind: 'select',
    options: [
      { value: '', label: 'Not rated' },
      ...[1, 2, 3, 4, 5].map((n) => ({ value: String(n), label: `${n} of 5` })),
    ],
  },
  { name: 'notes', label: 'Notes', kind: 'textarea' },
]

function SleepGlance() {
  const logs = useRows(sleepLogs)
  const targets = useRows(sleepTargets)
  return (
    <Loaded
      query={logs}
      what="sleep entries"
      quiet
      empty="No sleep logged yet. Add a night below and a chart will appear."
      render={(rows) => {
        const recent = [...rows].sort((a, b) => b.sleep_date.localeCompare(a.sleep_date)).slice(0, 14)
        const last = recent[0]
        const target =
          targets.data === undefined ? null : inForceOn(targets.data, last.sleep_date, (row) => row.effective_from_date)
        const points = [...recent].reverse().map((row) => ({
          label: formatShortDay(row.sleep_date),
          value: Math.round((row.duration_minutes / 60) * 10) / 10,
        }))
        return (
          <div className="flex flex-col gap-4 rounded-card bg-surface-2 p-4">
            {target ? (
              <Meter
                label={`Night of ${formatDay(last.sleep_date)}`}
                value={last.duration_minutes}
                target={target.target_minutes}
                valueText={formatMinutes(last.duration_minutes)}
                targetText={`${formatMinutes(target.target_minutes)} target`}
              />
            ) : (
              <p className="text-[0.9375rem]">
                Night of {formatDay(last.sleep_date)}: {formatMinutes(last.duration_minutes)}.{' '}
                <span className="text-muted-foreground">
                  {targets.isPending
                    ? 'Reading your sleep target.'
                    : targets.data === undefined
                      ? 'Could not reach the engine, so the sleep target is not shown.'
                      : 'No sleep target is set for that night.'}
                </span>
              </p>
            )}
            {points.length >= 2 ? (
              <>
                <Bars
                  points={points}
                  unit="h"
                  target={target ? Math.round((target.target_minutes / 60) * 10) / 10 : undefined}
                  targetLabel="target"
                  description={`Hours slept on the last ${points.length} logged nights`}
                />
                <p className="text-[0.8125rem] text-muted-foreground">
                  Hours slept on the last {points.length} logged nights. A night with no entry has no bar: it is
                  not counted as zero.
                </p>
              </>
            ) : (
              <p className="text-[0.8125rem] text-muted-foreground">
                One night logged so far, so there is no chart yet.
              </p>
            )}
          </div>
        )
      }}
    />
  )
}

function SleepPanel() {
  return (
    <div className="flex flex-col gap-6">
      <CrudPanel
        table={sleepLogs}
        title="Sleep"
        description="One entry per night, reported by you."
        addLabel="Log a night"
        what="sleep entries"
        empty="No sleep logged yet."
        sort={(a, b) => b.sleep_date.localeCompare(a.sleep_date)}
        limit={14}
        above={() => <SleepGlance />}
        columns={[
          { header: 'Night of', cell: (row) => formatDay(row.sleep_date) },
          { header: 'Slept', align: 'right', cell: (row) => formatMinutes(row.duration_minutes) },
          { header: 'Quality', cell: (row) => (row.quality ? `${row.quality} of 5` : 'Not rated') },
          { header: 'Notes', cell: (row) => row.notes ?? '' },
        ]}
        fields={SLEEP_FIELDS}
        identity={(row) => row.origin_guid}
        initial={(row): Values => ({
          sleep_date: row?.sleep_date ?? todayDay(),
          ...splitMinutes(row?.duration_minutes),
          quality: row?.quality ? String(row.quality) : '',
          notes: row?.notes ?? '',
        })}
        toBody={(values, row, guid) =>
          wire<SleepLog>({
            sleep_date: values.sleep_date,
            duration_minutes: minutesOf(values),
            quality: values.quality === '' ? null : Number(values.quality),
            notes: values.notes.trim() === '' ? null : values.notes.trim(),
            logged_at: row?.logged_at ?? new Date().toISOString(),
            trust_tier: 'REPORTED',
            origin_guid: guid,
          })
        }
        rowLabel={(row) => `the night of ${formatDay(row.sleep_date)}`}
        deleteConsequence="This night is removed for everyone in the household."
      />
      <CrudPanel
        table={sleepTargets}
        title="Sleep target"
        description="How long you want to sleep. A new target starts on its own date; earlier nights keep the target they had."
        addLabel="Set a target"
        what="sleep targets"
        empty="No sleep target set yet. Without one, nights are shown with no target line."
        sort={(a, b) => b.effective_from_date.localeCompare(a.effective_from_date)}
        columns={[
          { header: 'From', cell: (row) => formatDay(row.effective_from_date) },
          { header: 'Target', align: 'right', cell: (row) => formatMinutes(row.target_minutes) },
        ]}
        fields={[
          ...DURATION_FIELDS,
          { name: 'effective_from_date', label: 'Starts on', kind: 'date', required: true },
        ]}
        identity={(row) => row.origin_guid}
        initial={(row): Values => ({
          ...splitMinutes(row?.target_minutes),
          effective_from_date: row?.effective_from_date ?? todayDay(),
        })}
        toBody={(values, _row, guid) =>
          wire<SleepTarget>({
            target_minutes: minutesOf(values),
            effective_from_date: values.effective_from_date,
            origin_guid: guid,
          })
        }
        rowLabel={(row) => `the sleep target from ${formatDay(row.effective_from_date)}`}
        deleteConsequence="This target is removed. Nights it covered fall back to the one before it."
      />
    </div>
  )
}

// ---- Screen ---------------------------------------------------------------

export function BodyScreen() {
  return (
    <div>
      <PageHeader
        title="Body"
        subtitle="Weight, sleep, meals against the targets you set, and workouts."
      />
      <Tabs defaultValue="weight">
        <TabsList aria-label="Body sections">
          <TabsTrigger value="weight">Weight</TabsTrigger>
          <TabsTrigger value="sleep">Sleep</TabsTrigger>
          <TabsTrigger value="meals">Meals</TabsTrigger>
          <TabsTrigger value="workouts">Workouts</TabsTrigger>
        </TabsList>
        <TabsContent value="weight">
          <WeightPanel />
        </TabsContent>
        <TabsContent value="sleep">
          <SleepPanel />
        </TabsContent>
        <TabsContent value="meals">
          <MealsTab />
        </TabsContent>
        <TabsContent value="workouts">
          <WorkoutsTab />
        </TabsContent>
      </Tabs>
    </div>
  )
}
