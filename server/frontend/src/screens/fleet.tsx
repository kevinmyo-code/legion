import { Pencil } from 'lucide-react'
import { useState } from 'react'

import {
  drives,
  maintenanceSchedules,
  scheduleIdentity,
  serviceHistory,
  vehicles,
  type Drive,
  type MaintenanceSchedule,
  type ServiceHistory,
  type Vehicle,
} from '@/api/aspects'
import { WriteRefused } from '@/api/refusal'
import { useRows, useSave, wire } from '@/api/synced'
import { Button } from '@/components/ui/button'
import { CrudPanel } from '@/components/workbench/crud-panel'
import { Loaded } from '@/components/workbench/loaded'
import { Estimate, PageHeader, Panel } from '@/components/workbench/page'
import { EditorDialog, type FieldSpec, type Values } from '@/components/workbench/record-form'
import {
  centsToDollars,
  dollarsToCents,
  formatDay,
  formatInstant,
  formatMoney,
  formatNumber,
  fromLocalInput,
  todayDay,
  toLocalInput,
} from '@/lib/figures'

/**
 * `/fleet`: vehicles, their service history, their maintenance schedules and
 * their recent drives.
 *
 * What is writable follows what the engine allows, read from its schema:
 *
 * - **Service history and maintenance schedules**: add, edit, delete.
 * - **Vehicles**: edit only, and only a vehicle that has an `origin_guid`. The
 *   engine keys a vehicle's PUT by that column and it is nullable: a vehicle the
 *   engine created itself has none, so there is no key to write it by. The row
 *   says so in words rather than offering a button that would write somewhere
 *   else (MEMORY: "`origin_guid` for a server-created vehicle" is an open
 *   decision). Creating a vehicle is therefore not offered either - it would
 *   need exactly that decision - and the screen says new vehicles come from the
 *   phone.
 * - **Drives**: read only (ticket 17). `obd_samples` are not shown: a series of
 *   twenty thousand points is a different tool's job.
 *
 * Trust, as this screen carries it:
 *
 * - A service entry is `OBSERVED` (LEGION saw it, or read it from a document) or
 *   `ASSERTED` (somebody said so, nothing checked it). The word is in the row.
 *   An entry typed on the web is asserted, and editing an observed one says that
 *   saving makes it asserted.
 * - A maintenance interval the person did not state (`SEEDED`) is LEGION's
 *   guess, and carries the word `estimate` beside it. One saved from here is
 *   `CONFIRMED`: the person typed it.
 * - A drive's fuel is `no fuel reading` when the sensor was silent, never zero.
 *   No miles-per-gallon figure is derived from it here.
 */

// ---- Vehicle --------------------------------------------------------------

const VEHICLE_FIELDS: FieldSpec[] = [
  { name: 'name', label: 'Name', kind: 'text', required: true },
  { name: 'make', label: 'Make', kind: 'text', required: true },
  { name: 'model', label: 'Model', kind: 'text', required: true },
  { name: 'year', label: 'Year', kind: 'number', required: true, step: '1', min: '1900', max: '2100' },
  { name: 'trim', label: 'Trim', kind: 'text' },
  { name: 'engine', label: 'Engine', kind: 'text' },
  { name: 'odometer_baseline', label: 'Odometer reading, miles', kind: 'number', step: '1', min: '0' },
  {
    name: 'odometer_baseline_at',
    label: 'Odometer read on',
    kind: 'datetime',
    hint: 'A reading and the moment it was read go together: both or neither.',
  },
  {
    name: 'archived',
    label: 'Status',
    kind: 'select',
    options: [
      { value: 'no', label: 'Active' },
      { value: 'yes', label: 'Archived' },
    ],
  },
]

function blankToNull(value: string): string | null {
  return value.trim() === '' ? null : value.trim()
}

function vehicleTitle(vehicle: Vehicle): string {
  return `${vehicle.year} ${vehicle.make} ${vehicle.model}${vehicle.trim ? ` ${vehicle.trim}` : ''}`
}

function VehicleCard({ vehicle }: { vehicle: Vehicle }) {
  const save = useSave(vehicles)
  const [editing, setEditing] = useState(false)

  return (
    <Panel
      title={vehicle.name}
      description={vehicleTitle(vehicle)}
      action={
        vehicle.origin_guid ? (
          <Button variant="secondary" onClick={() => setEditing(true)}>
            <Pencil /> Edit vehicle
          </Button>
        ) : null
      }
    >
      <dl className="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-1.5 text-[0.9375rem]">
        <dt className="text-muted-foreground">Engine</dt>
        <dd>{vehicle.engine ?? 'Not recorded'}</dd>
        <dt className="text-muted-foreground">Odometer</dt>
        <dd className="tabular-nums">
          {vehicle.odometer_baseline != null && vehicle.odometer_baseline_at
            ? `${formatNumber(vehicle.odometer_baseline, 0)} miles, read ${formatInstant(vehicle.odometer_baseline_at)}`
            : 'No reading recorded'}
        </dd>
        <dt className="text-muted-foreground">Status</dt>
        <dd>{vehicle.archived ? 'Archived' : 'Active'}</dd>
      </dl>
      {!vehicle.origin_guid && (
        <p className="mt-3 rounded-control bg-surface-2 px-4 py-3 text-[0.9375rem] text-muted-foreground">
          This vehicle has no key on the engine yet, so it cannot be edited here.
        </p>
      )}
      {editing && vehicle.origin_guid && (
        <EditorDialog
          title={`Edit ${vehicle.name}`}
          fields={VEHICLE_FIELDS}
          initial={{
            name: vehicle.name,
            make: vehicle.make,
            model: vehicle.model,
            year: String(vehicle.year),
            trim: vehicle.trim ?? '',
            engine: vehicle.engine ?? '',
            odometer_baseline: vehicle.odometer_baseline != null ? String(vehicle.odometer_baseline) : '',
            odometer_baseline_at: vehicle.odometer_baseline_at ? toLocalInput(vehicle.odometer_baseline_at) : '',
            archived: vehicle.archived ? 'yes' : 'no',
          }}
          onClose={() => setEditing(false)}
          onSave={async (values: Values) => {
            const reading = values.odometer_baseline.trim()
            const readAt = values.odometer_baseline_at.trim()
            if ((reading === '') !== (readAt === '')) {
              throw new WriteRefused(
                'Nothing was saved. An odometer reading needs the moment it was read, and a moment needs a reading.',
              )
            }
            // PUT is the whole row, not a patch: every field the form does not
            // show is sent back as it was, so saving never clears one.
            await save.mutateAsync({
              identity: vehicle.origin_guid ?? '',
              body: wire<Vehicle>({
                name: values.name.trim(),
                make: values.make.trim(),
                model: values.model.trim(),
                year: Math.round(Number(values.year)),
                trim: blankToNull(values.trim),
                engine: blankToNull(values.engine),
                confirmed: vehicle.confirmed,
                odometer_baseline: reading === '' ? null : Math.round(Number(reading)),
                odometer_baseline_at: readAt === '' ? null : fromLocalInput(readAt),
                origin_guid: vehicle.origin_guid,
                archived: values.archived === 'yes',
                last_obd_mac: vehicle.last_obd_mac ?? null,
              }),
            })
          }}
        />
      )}
    </Panel>
  )
}

// ---- Service history ------------------------------------------------------

const SERVICE_FIELDS: FieldSpec[] = [
  { name: 'service_name', label: 'Service', kind: 'text', required: true, placeholder: 'Oil change' },
  { name: 'service_date', label: 'Date', kind: 'date' },
  { name: 'mileage', label: 'Mileage', kind: 'number', step: '1', min: '0' },
  { name: 'cost', label: 'Cost, dollars', kind: 'number', step: '0.01', hint: 'Leave blank if you did not record one. Blank is not the same as free.' },
]

function ServiceRecord({ kind }: { kind: string }) {
  return kind === 'OBSERVED' ? (
    <span>Observed</span>
  ) : (
    <span>Asserted, not checked</span>
  )
}

function ServicePanel({ vehicle }: { vehicle: Vehicle }) {
  return (
    <CrudPanel
      table={serviceHistory}
      title="Service history"
      description="What was done to this vehicle. Observed means LEGION saw it or read it from a document; asserted means someone said so and nothing checked it."
      addLabel="Add a service"
      what="service history"
      empty="No service recorded for this vehicle yet."
      select={(rows) => rows.filter((row) => row.vehicle_id === vehicle.id)}
      emptySelection="No service recorded for this vehicle yet."
      sort={(a, b) => (b.service_date ?? '').localeCompare(a.service_date ?? '')}
      limit={15}
      columns={[
        { header: 'Date', cell: (row) => (row.service_date ? formatDay(row.service_date) : 'No date') },
        { header: 'Service', cell: (row) => <span className="font-medium">{row.service_name}</span> },
        { header: 'Mileage', align: 'right', cell: (row) => (row.mileage != null ? `${formatNumber(row.mileage, 0)} mi` : 'Not recorded') },
        { header: 'Cost', align: 'right', cell: (row) => (row.cost_cents != null ? formatMoney(row.cost_cents) : 'No cost recorded') },
        { header: 'Record', cell: (row) => <ServiceRecord kind={row.kind} /> },
      ]}
      fields={SERVICE_FIELDS}
      identity={(row) => row.origin_guid ?? null}
      initial={(row): Values => ({
        service_name: row?.service_name ?? '',
        service_date: row?.service_date ?? todayDay(),
        mileage: row?.mileage != null ? String(row.mileage) : '',
        cost: row?.cost_cents != null ? centsToDollars(row.cost_cents) : '',
      })}
      toBody={(values, _row, guid) => {
        const cost = values.cost.trim()
        const cents = cost === '' ? null : dollarsToCents(cost)
        if (cost !== '' && cents === null) {
          throw new WriteRefused('Nothing was saved. Cost must be an amount like 49.99, with at most two decimals.')
        }
        return wire<ServiceHistory>({
          vehicle_id: vehicle.id,
          service_name: values.service_name.trim(),
          mileage: values.mileage.trim() === '' ? null : Math.round(Number(values.mileage)),
          service_date: values.service_date.trim() === '' ? null : values.service_date,
          cost_cents: cents,
          // Someone typed this on the web, and nothing checked it.
          kind: 'ASSERTED',
          origin_guid: guid,
        })
      }}
      formExtra={(row) =>
        row?.kind === 'OBSERVED' ? (
          <p className="rounded-control bg-surface-3 px-4 py-3 text-[0.8125rem]">
            LEGION observed this service. Saving a change makes it asserted, because the figures are then what you
            typed rather than what was seen.
          </p>
        ) : null
      }
      rowLabel={(row) => `${row.service_name}${row.service_date ? ` on ${formatDay(row.service_date)}` : ''}`}
      deleteConsequence="This entry is removed for everyone in the household."
    />
  )
}

// ---- Maintenance schedules ------------------------------------------------

function intervalWords(row: MaintenanceSchedule): string {
  const parts: string[] = []
  if (row.interval_miles != null) parts.push(`${formatNumber(row.interval_miles, 0)} miles`)
  if (row.interval_months != null) parts.push(`${row.interval_months} ${row.interval_months === 1 ? 'month' : 'months'}`)
  return parts.length === 0 ? 'No interval' : `Every ${parts.join(' or ')}`
}

function sourceWords(source: string): string {
  switch (source) {
    case 'SEEDED':
      return "LEGION's guess"
    case 'LOOKUP':
      return 'From a factory lookup'
    case 'CONFIRMED':
      return 'Confirmed by you'
    default:
      return source
  }
}

function SchedulePanel({ vehicle }: { vehicle: Vehicle }) {
  const history = useRows(serviceHistory)
  // The last time a service of the same name appears in this vehicle's history,
  // matched by name only. Nothing is inferred from a near match.
  const lastDone = (name: string): string => {
    if (history.data === undefined) return history.isPending ? 'Reading history' : 'History not available'
    const dates = history.data
      .filter((row) => row.vehicle_id === vehicle.id && row.service_name.toLowerCase() === name.toLowerCase())
      .map((row) => row.service_date)
      .filter((date): date is string => date !== null && date !== undefined)
      .sort()
    return dates.length > 0 ? formatDay(dates[dates.length - 1]) : 'Not in the history'
  }

  return (
    <CrudPanel
      table={maintenanceSchedules}
      title="Maintenance schedule"
      description="How often each service comes due. An interval LEGION seeded and you have not confirmed is a guess, and says so."
      addLabel="Add a schedule"
      what="maintenance schedules"
      empty="No maintenance schedule for this vehicle yet."
      select={(rows) => rows.filter((row) => row.vehicle_id === vehicle.id)}
      emptySelection="No maintenance schedule for this vehicle yet."
      sort={(a, b) => a.service_name.localeCompare(b.service_name)}
      columns={[
        { header: 'Service', cell: (row) => <span className="font-medium">{row.service_name}</span> },
        {
          header: 'Interval',
          cell: (row) => (
            <span className="inline-flex flex-wrap items-center gap-x-2">
              <span>{intervalWords(row)}</span>
              {row.interval_source === 'SEEDED' && <Estimate />}
            </span>
          ),
        },
        { header: 'Source', cell: (row) => sourceWords(row.interval_source) },
        {
          header: 'Last in the history',
          cell: (row) => (row.never_done ? 'Marked never done' : lastDone(row.service_name)),
        },
      ]}
      fields={(row) => [
        {
          name: 'service_name',
          label: 'Service',
          kind: 'text',
          required: true,
          readOnly: row !== null,
          hint: row ? 'The name is the schedule\'s key, so it cannot be changed. Add a new schedule to use another name.' : undefined,
        },
        { name: 'interval_miles', label: 'Every, miles', kind: 'number', step: '1', min: '1' },
        { name: 'interval_months', label: 'Every, months', kind: 'number', step: '1', min: '1', hint: 'Give miles, months, or both.' },
        {
          name: 'never_done',
          label: 'Done before?',
          kind: 'select',
          options: [
            { value: 'no', label: 'Yes, or not sure' },
            { value: 'yes', label: 'Never done' },
          ],
        },
      ]}
      identity={(row) => scheduleIdentity(row.vehicle_id, row.service_name)}
      newIdentity={(values) => scheduleIdentity(vehicle.id, values.service_name.trim())}
      initial={(row): Values => ({
        service_name: row?.service_name ?? '',
        interval_miles: row?.interval_miles != null ? String(row.interval_miles) : '',
        interval_months: row?.interval_months != null ? String(row.interval_months) : '',
        never_done: row?.never_done ? 'yes' : 'no',
      })}
      toBody={(values, row) =>
        wire<MaintenanceSchedule>({
          vehicle_id: vehicle.id,
          service_name: row?.service_name ?? values.service_name.trim(),
          interval_miles: values.interval_miles.trim() === '' ? null : Math.round(Number(values.interval_miles)),
          interval_months: values.interval_months.trim() === '' ? null : Math.round(Number(values.interval_months)),
          // Saved from here, the interval is one a person stated.
          interval_source: 'CONFIRMED',
          never_done: values.never_done === 'yes',
        })
      }
      rowLabel={(row) => `the ${row.service_name} schedule`}
      deleteConsequence="This schedule is removed for everyone in the household."
    />
  )
}

// ---- Drives ---------------------------------------------------------------

function endReason(reason: string): string {
  if (reason === 'ENGINE_OFF') return 'Engine off'
  if (reason === 'LINK_LOST') return 'Dongle link lost'
  return reason
}

function DrivesPanel({ vehicle }: { vehicle: Vehicle }) {
  return (
    <CrudPanel<Drive>
      table={drives}
      title="Recent drives"
      description="Read only. Fuel comes from the mass-airflow sensor; when it was silent the drive says no fuel reading rather than zero."
      what="drives"
      empty="No drives recorded for this vehicle yet."
      select={(rows) => rows.filter((row) => row.vehicle_id === vehicle.id)}
      emptySelection="No drives recorded for this vehicle yet."
      sort={(a, b) => b.started_at.localeCompare(a.started_at)}
      limit={10}
      columns={[
        { header: 'Started', cell: (row) => formatInstant(row.started_at) },
        { header: 'Miles', align: 'right', cell: (row) => formatNumber(row.miles, 1) },
        {
          header: 'Fuel',
          align: 'right',
          cell: (row) => (row.gallons == null ? 'No fuel reading' : `${formatNumber(row.gallons, 2)} gal`),
        },
        { header: 'Ended by', cell: (row) => endReason(row.end_reason) },
      ]}
    />
  )
}

// ---- Screen ---------------------------------------------------------------

export function FleetScreen() {
  const query = useRows(vehicles)
  const [chosen, setChosen] = useState<string | null>(null)

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Fleet"
        subtitle="Vehicles, what has been done to them, when each service comes due, and recent drives. New vehicles are added from the phone."
      />
      <Loaded
        query={query}
        what="vehicles"
        quiet
        empty="No vehicles yet. They are added from the phone."
        render={(rows) => {
          const ordered = [...rows].sort(
            (a, b) => Number(a.archived ?? false) - Number(b.archived ?? false) || a.name.localeCompare(b.name),
          )
          const vehicle = ordered.find((row) => row.id === chosen) ?? ordered[0]
          return (
            <div className="flex flex-col gap-6">
              <div role="group" aria-label="Vehicles" className="flex flex-wrap gap-2">
                {ordered.map((row) => (
                  <Button
                    key={row.id}
                    variant={row.id === vehicle.id ? 'secondary' : 'outline'}
                    aria-pressed={row.id === vehicle.id}
                    onClick={() => setChosen(row.id)}
                  >
                    {row.name}
                    {row.archived ? ' (archived)' : ''}
                  </Button>
                ))}
              </div>
              <VehicleCard key={vehicle.id} vehicle={vehicle} />
              <ServicePanel vehicle={vehicle} />
              <SchedulePanel vehicle={vehicle} />
              <DrivesPanel vehicle={vehicle} />
            </div>
          )
        }}
      />
    </div>
  )
}
