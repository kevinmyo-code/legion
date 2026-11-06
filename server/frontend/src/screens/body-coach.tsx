import { useEffect, useState, type FormEvent, type ReactNode } from 'react'

import {
  useCoachProfile,
  useDecideProposal,
  useDeleteSavedMeal,
  useDeleteWaist,
  useEnergy,
  useLogSavedMeal,
  useLogWaist,
  useSaveMeal,
  useSaveProfile,
  useSavedMeals,
  useTrend,
  type CoachProfile,
  type CoachProfileUpdate,
  type Energy,
  type Trend,
} from '@/api/body-coach'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Switch } from '@/components/ui/switch'
import { TrendLine } from '@/components/workbench/charts'
import { ConfirmDelete } from '@/components/workbench/confirm-delete'
import { Loaded } from '@/components/workbench/loaded'
import { EmptySentence, ErrorSentence, Estimate, Figure, Panel } from '@/components/workbench/page'
import { SelectInput } from '@/components/workbench/record-form'
import { formatDay, formatNumber, formatShortDay, todayDay } from '@/lib/figures'

/**
 * The body coach on `/body` (fat-loss ticket 09): the hands path for every
 * capability the coach's voice tools have (ADR 0035), until the phone's Body
 * screen lands.
 *
 * **Every figure the engine computes is an estimate and prints the word beside
 * it** (CLAUDE.md section 4 rule 5): maintenance, target, protein, the trend, a
 * saved meal's macros. The basis is said in the engine's own words. A target
 * someone set (the current meal target) is printed plain. Nothing here
 * computes a figure: the web shows what the engine said.
 */

const ACTIVITY = [
  { value: '', label: 'Not set' },
  { value: 'sedentary', label: 'Sedentary (x1.2)' },
  { value: 'light', label: 'Light (x1.375)' },
  { value: 'moderate', label: 'Moderate (x1.55)' },
  { value: 'very', label: 'Very active (x1.725)' },
]
const SEX = [
  { value: '', label: 'Not set' },
  { value: 'male', label: 'Male' },
  { value: 'female', label: 'Female' },
]

function Big({ children }: { children: ReactNode }) {
  return <span className="text-[1.75rem] leading-none font-medium tabular-nums">{children}</span>
}

function Stat({ label, children, note }: { label: string; children: ReactNode; note?: ReactNode }) {
  return (
    <div className="flex min-w-44 flex-col gap-1 rounded-card bg-surface-2 p-4">
      <span className="text-[0.8125rem] text-muted-foreground">{label}</span>
      <span className="inline-flex flex-wrap items-baseline gap-x-2">{children}</span>
      {note && <span className="text-[0.8125rem] text-muted-foreground">{note}</span>}
    </div>
  )
}

function errorText(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback
}

// ---- Energy ---------------------------------------------------------------

function ProposalCard({ proposal }: { proposal: NonNullable<Energy['proposal']> }) {
  const decide = useDecideProposal()
  const [said, setSaid] = useState<string | null>(null)
  return (
    <div className="flex flex-col gap-3 rounded-card bg-primary-container p-4 text-primary-container-foreground">
      <p className="text-[0.9375rem] font-medium">
        Proposed: {formatNumber(proposal.current_target_kcal)} to {formatNumber(proposal.proposed_target_kcal)} kcal a
        day <Estimate />
      </p>
      <p className="text-[0.9375rem]">{proposal.words}</p>
      <p className="text-[0.8125rem]">
        From {formatDay(proposal.window_start)} to {formatDay(proposal.window_end)}:{' '}
        {proposal.intake_days} days with meals, {proposal.weigh_in_days} with a weigh-in.
      </p>
      <div className="flex gap-2">
        <Button
          disabled={decide.isPending}
          onClick={() =>
            decide.mutate(
              { id: proposal.id, accept: true },
              { onSuccess: () => setSaid(null), onError: (e) => setSaid(errorText(e, 'Nothing was changed.')) },
            )
          }
        >
          Accept
        </Button>
        <Button
          variant="secondary"
          disabled={decide.isPending}
          onClick={() =>
            decide.mutate(
              { id: proposal.id, accept: false },
              { onSuccess: () => setSaid(null), onError: (e) => setSaid(errorText(e, 'Nothing was changed.')) },
            )
          }
        >
          Dismiss
        </Button>
      </div>
      {said && <ErrorSentence>{said}</ErrorSentence>}
    </div>
  )
}

function EnergyBody({ energy }: { energy: Energy }) {
  return (
    <div className="flex flex-col gap-4">
      <p className="text-[0.9375rem]">{energy.words}</p>
      {energy.maintenance_kcal === null ? (
        <EmptySentence>{energy.basis_words}</EmptySentence>
      ) : (
        <div className="flex flex-wrap gap-3">
          <Stat
            label="Maintenance"
            note={energy.basis === 'trend' && energy.formula_maintenance_kcal !== null
              ? `Formula alone: ${formatNumber(energy.formula_maintenance_kcal)} kcal`
              : energy.basis_words}
          >
            <Big>{formatNumber(energy.maintenance_kcal)} kcal</Big>
            <Estimate />
          </Stat>
          <Stat label="Suggested target" note={`For ${energy.target_rate_pct.toFixed(2)}% of bodyweight a week`}>
            {energy.suggested_target_kcal === null ? (
              <span className="text-[0.9375rem] text-muted-foreground">Not suggested</span>
            ) : (
              <>
                <Big>{formatNumber(energy.suggested_target_kcal)} kcal</Big>
                <Estimate />
              </>
            )}
          </Stat>
          {energy.protein_g !== null && (
            <Stat label="Protein" note={`${energy.protein_g_per_kg.toFixed(1)} g/kg of ${energy.protein_basis}`}>
              <Big>{formatNumber(energy.protein_g)} g</Big>
              <Estimate />
            </Stat>
          )}
        </div>
      )}
      {energy.missing.length > 0 && (
        <p className="text-[0.8125rem] text-muted-foreground">
          Still needed for the formula: {energy.missing.join(', ')}.
        </p>
      )}
      <p className="text-[0.9375rem]">
        {energy.current_target
          ? `Your meal target: ${formatNumber(energy.current_target.calories_kcal)} kcal and ${formatNumber(
              energy.current_target.protein_g,
            )} g protein, from ${formatDay(energy.current_target.effective_from)}.`
          : 'No meal target is set yet. Set one on the Meals tab.'}
      </p>
      {energy.proposal && <ProposalCard proposal={energy.proposal} />}
    </div>
  )
}

function EnergyPanel() {
  const energy = useEnergy()
  return (
    <Panel title="Energy" description="Calories and protein from your own numbers. Every figure is an estimate.">
      <Loaded query={energy} what="energy estimate" quiet empty="" isEmpty={() => false} render={(e) => <EnergyBody energy={e} />} />
    </Panel>
  )
}

// ---- Trend ----------------------------------------------------------------

function TrendBody({ trend }: { trend: Trend }) {
  if (trend.points.length === 0) return <EmptySentence>{trend.words}</EmptySentence>
  const points = trend.points.map((point) => ({
    label: formatShortDay(point.day),
    trend: point.trend_lbs,
    raw: point.weight_lbs,
  }))
  const stone = trend.milestone
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap gap-3">
        <Stat label="Trend weight">
          <Big>{formatNumber(trend.latest_trend_lbs ?? 0)} lbs</Big>
          <Estimate />
        </Stat>
        <Stat
          label="Rate, last 14 days"
          note={
            trend.in_band === null
              ? undefined
              : trend.in_band
                ? 'Inside the 0.5 to 1.0% loss band'
                : 'Outside the 0.5 to 1.0% loss band'
          }
        >
          {trend.rate_lbs_per_week === null || trend.rate_pct_per_week === null ? (
            <span className="text-[0.9375rem] text-muted-foreground">Not yet</span>
          ) : (
            <Figure word="estimate">
              {`${formatNumber(trend.rate_lbs_per_week)} lbs a week (${trend.rate_pct_per_week.toFixed(2)}%)`}
            </Figure>
          )}
        </Stat>
        {stone && (
          <Stat label="Goal" note={stone.goal_id === null ? 'From your profile' : stone.statement}>
            <span className="text-[0.9375rem] tabular-nums">
              {formatNumber(stone.remaining_lbs)} lbs to go to {formatNumber(stone.target_lbs)} lbs
              {stone.weeks_to_go !== null && `, about ${formatNumber(stone.weeks_to_go, 0)} weeks`}
            </span>
            <Estimate />
          </Stat>
        )}
      </div>
      {points.length >= 2 ? (
        <TrendLine
          points={points}
          unit="lbs"
          reference={stone?.target_lbs}
          referenceLabel="goal"
          description={`Weight trend over ${points.length} days in lbs, from ${points[0].trend} to ${points[points.length - 1].trend}; dots are the day's own readings`}
        />
      ) : null}
      <p className="text-[0.8125rem] text-muted-foreground">
        The line is the smoothed trend; dots are the readings. A day with no reading has no dot.
      </p>
      <p className="text-[0.9375rem]">{trend.words}</p>
    </div>
  )
}

function TrendPanel() {
  const trend = useTrend(90)
  return (
    <Panel title="Weight trend" description="Last 90 days, smoothed so one heavy morning does not move it much.">
      <Loaded query={trend} what="weight trend" quiet empty="" isEmpty={() => false} render={(t) => <TrendBody trend={t} />} />
    </Panel>
  )
}

// ---- Waist ----------------------------------------------------------------

function WaistPanel() {
  const trend = useTrend(90)
  const log = useLogWaist()
  const del = useDeleteWaist()
  const [value, setValue] = useState('')
  const [unit, setUnit] = useState<'in' | 'cm'>('in')
  const [day, setDay] = useState(todayDay())
  const [problem, setProblem] = useState<string | null>(null)

  function submit(event: FormEvent) {
    event.preventDefault()
    const number = Number(value)
    if (value.trim() === '' || !Number.isFinite(number) || number <= 0) {
      setProblem('Nothing was saved. Give the waist as a number above zero.')
      return
    }
    setProblem(null)
    log.mutate(
      { ...(unit === 'in' ? { waist_in: number } : { waist_cm: number }), measured_on: day },
      { onSuccess: () => setValue(''), onError: (e) => setProblem(errorText(e, 'Nothing was saved.')) },
    )
  }

  return (
    <Panel title="Waist" description="Once a week, at the navel, relaxed. A second check beside the scale.">
      <form onSubmit={submit} className="mb-4 flex flex-wrap items-end gap-3" aria-label="Log a waist measurement">
        <div className="flex flex-col gap-1">
          <Label htmlFor="waist-value">Waist</Label>
          <Input id="waist-value" type="number" step="0.1" min="0" value={value} onChange={(e) => setValue(e.target.value)} className="w-32" />
        </div>
        <div className="flex flex-col gap-1">
          <Label htmlFor="waist-unit">Unit</Label>
          <SelectInput
            id="waist-unit"
            value={unit}
            onChange={(next) => setUnit(next as 'in' | 'cm')}
            options={[
              { value: 'in', label: 'in' },
              { value: 'cm', label: 'cm' },
            ]}
          />
        </div>
        <div className="flex flex-col gap-1">
          <Label htmlFor="waist-day">Measured on</Label>
          <Input id="waist-day" type="date" value={day} onChange={(e) => setDay(e.target.value)} className="w-44" />
        </div>
        <Button type="submit" disabled={log.isPending}>
          Log waist
        </Button>
      </form>
      {problem && <ErrorSentence>{problem}</ErrorSentence>}
      <Loaded
        query={trend}
        what="waist measurements"
        quiet
        empty="No waist measured yet."
        isEmpty={(t) => t.waist.length === 0}
        render={(t) => (
          <div className="flex flex-col gap-2">
            {t.waist_change_in_28d !== null && (
              <p className="text-[0.9375rem]">
                {t.waist_change_in_28d === 0
                  ? 'Unchanged over four weeks.'
                  : `${t.waist_change_in_28d < 0 ? 'Down' : 'Up'} ${formatNumber(Math.abs(t.waist_change_in_28d))} in over four weeks.`}
              </p>
            )}
            <ul className="flex flex-col divide-y divide-outline-variant">
              {t.waist.map((row) => (
                <li key={row.id} className="flex items-center justify-between gap-3 py-2">
                  <span className="tabular-nums">
                    {formatDay(row.measured_on)}: {formatNumber(row.waist_in)} in ({formatNumber(row.waist_cm)} cm)
                  </span>
                  <ConfirmDelete
                    label={`the waist from ${formatDay(row.measured_on)}`}
                    consequence="Deleted for good, for everyone in the household."
                    onConfirm={async () => {
                      await del.mutateAsync(row.id)
                    }}
                  />
                </li>
              ))}
            </ul>
          </div>
        )}
      />
    </Panel>
  )
}

// ---- Saved meals ----------------------------------------------------------

function SavedMealsPanel() {
  const meals = useSavedMeals()
  const save = useSaveMeal()
  const log = useLogSavedMeal()
  const del = useDeleteSavedMeal()
  const [name, setName] = useState('')
  const [kcal, setKcal] = useState('')
  const [protein, setProtein] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  const [said, setSaid] = useState<string | null>(null)

  function submit(event: FormEvent) {
    event.preventDefault()
    if (name.trim() === '' || kcal.trim() === '' || protein.trim() === '') {
      setProblem('Nothing was saved. A saved meal needs a name, calories and protein.')
      return
    }
    setProblem(null)
    save.mutate(
      { name: name.trim(), calories_kcal: Math.round(Number(kcal)), protein_g: Number(protein) },
      {
        onSuccess: () => {
          setName('')
          setKcal('')
          setProtein('')
        },
        onError: (e) => setProblem(errorText(e, 'Nothing was saved.')),
      },
    )
  }

  return (
    <Panel
      title="Saved meals"
      description='Meals you eat often, so "log my usual breakfast" is one sentence. The macros are estimates.'
    >
      <form onSubmit={submit} className="mb-4 flex flex-wrap items-end gap-3" aria-label="Save a meal">
        <div className="flex flex-col gap-1">
          <Label htmlFor="meal-name">Name</Label>
          <Input id="meal-name" value={name} onChange={(e) => setName(e.target.value)} placeholder="Usual breakfast" className="w-56" />
        </div>
        <div className="flex flex-col gap-1">
          <Label htmlFor="meal-kcal">Calories, kcal (estimate)</Label>
          <Input id="meal-kcal" type="number" min="0" step="1" value={kcal} onChange={(e) => setKcal(e.target.value)} className="w-40" />
        </div>
        <div className="flex flex-col gap-1">
          <Label htmlFor="meal-protein">Protein, g (estimate)</Label>
          <Input id="meal-protein" type="number" min="0" step="0.1" value={protein} onChange={(e) => setProtein(e.target.value)} className="w-40" />
        </div>
        <Button type="submit" disabled={save.isPending}>
          Save meal
        </Button>
      </form>
      {problem && <ErrorSentence>{problem}</ErrorSentence>}
      {said && (
        <p role="status" className="mb-2 text-[0.9375rem]">
          {said}
        </p>
      )}
      <Loaded
        query={meals}
        what="saved meals"
        quiet
        empty="No saved meals yet."
        isEmpty={(m) => m.results.length === 0}
        render={(m) => (
          <ul className="flex flex-col divide-y divide-outline-variant">
            {m.results.map((meal) => (
              <li key={meal.id} className="flex flex-wrap items-center justify-between gap-3 py-2">
                <span className="inline-flex flex-wrap items-center gap-x-2">
                  <span className="font-medium">{meal.name}</span>
                  <span className="tabular-nums">
                    {formatNumber(meal.calories_kcal)} kcal, {formatNumber(meal.protein_g)} g protein
                  </span>
                  <Estimate />
                </span>
                <span className="flex items-center gap-2">
                  <Button
                    size="sm"
                    variant="secondary"
                    aria-label={`Log ${meal.name} now`}
                    disabled={log.isPending}
                    onClick={() =>
                      log.mutate(meal.id, {
                        onSuccess: (out) => setSaid(out?.words ?? null),
                        onError: (e) => setSaid(errorText(e, 'Nothing was logged.')),
                      })
                    }
                  >
                    Log now
                  </Button>
                  <ConfirmDelete
                    label={`"${meal.name}"`}
                    consequence="The preset goes; meals already logged from it stay."
                    onConfirm={async () => {
                      await del.mutateAsync(meal.id)
                    }}
                  />
                </span>
              </li>
            ))}
          </ul>
        )}
      />
    </Panel>
  )
}

// ---- Profile --------------------------------------------------------------

interface ProfileValues {
  height_cm: string
  sex: string
  birth_year: string
  activity_level: string
  target_rate_pct: string
  protein_g_per_kg: string
  weekly_line_on: boolean
  goal_weight_lbs: string
}

function valuesOf(profile: CoachProfile): ProfileValues {
  return {
    height_cm: profile.height_cm === null ? '' : String(profile.height_cm),
    sex: profile.sex ?? '',
    birth_year: profile.birth_year === null ? '' : String(profile.birth_year),
    activity_level: profile.activity_level ?? '',
    target_rate_pct: String(profile.target_rate_pct),
    protein_g_per_kg: String(profile.protein_g_per_kg),
    weekly_line_on: profile.weekly_line_on,
    goal_weight_lbs: profile.goal_weight_lbs === null ? '' : String(profile.goal_weight_lbs),
  }
}

function ProfileForm({ profile }: { profile: CoachProfile }) {
  const save = useSaveProfile()
  const [values, setValues] = useState(() => valuesOf(profile))
  const [said, setSaid] = useState<{ ok: boolean; text: string } | null>(null)
  useEffect(() => setValues(valuesOf(profile)), [profile])

  function set<K extends keyof ProfileValues>(key: K, value: ProfileValues[K]) {
    setValues((current) => ({ ...current, [key]: value }))
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    const body: CoachProfileUpdate = {
      target_rate_pct: Number(values.target_rate_pct),
      protein_g_per_kg: Number(values.protein_g_per_kg),
      weekly_line_on: values.weekly_line_on,
      // Blank clears it: the coach then aims at no weight unless a goal is on the engine.
      goal_weight_lbs: values.goal_weight_lbs.trim() === '' ? null : Number(values.goal_weight_lbs),
    }
    if (values.height_cm.trim() !== '') body.height_cm = Number(values.height_cm)
    if (values.sex) body.sex = values.sex as CoachProfileUpdate['sex']
    if (values.birth_year.trim() !== '') body.birth_year = Math.round(Number(values.birth_year))
    if (values.activity_level) body.activity_level = values.activity_level as CoachProfileUpdate['activity_level']
    save.mutate(body, {
      onSuccess: () => setSaid({ ok: true, text: 'Saved.' }),
      onError: (e) => setSaid({ ok: false, text: errorText(e, 'Nothing was saved.') }),
    })
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-4" aria-label="Body profile">
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <div className="flex flex-col gap-1">
          <Label htmlFor="coach-height">Height, cm</Label>
          <Input id="coach-height" type="number" step="0.1" value={values.height_cm} onChange={(e) => set('height_cm', e.target.value)} />
        </div>
        <div className="flex flex-col gap-1">
          <Label htmlFor="coach-sex">Sex (the formula needs it)</Label>
          <SelectInput id="coach-sex" value={values.sex} onChange={(v) => set('sex', v)} options={SEX} />
        </div>
        <div className="flex flex-col gap-1">
          <Label htmlFor="coach-birth">Birth year</Label>
          <Input id="coach-birth" type="number" step="1" value={values.birth_year} onChange={(e) => set('birth_year', e.target.value)} />
        </div>
        <div className="flex flex-col gap-1">
          <Label htmlFor="coach-activity">Activity</Label>
          <SelectInput id="coach-activity" value={values.activity_level} onChange={(v) => set('activity_level', v)} options={ACTIVITY} />
        </div>
        <div className="flex flex-col gap-1">
          <Label htmlFor="coach-rate">Rate, % of bodyweight a week (0.25 to 1.0)</Label>
          <Input id="coach-rate" type="number" step="0.05" min="0.25" max="1" value={values.target_rate_pct} onChange={(e) => set('target_rate_pct', e.target.value)} />
        </div>
        <div className="flex flex-col gap-1">
          <Label htmlFor="coach-protein">Protein, g per kg (1.6 to 2.2)</Label>
          <Input id="coach-protein" type="number" step="0.1" min="1.6" max="2.2" value={values.protein_g_per_kg} onChange={(e) => set('protein_g_per_kg', e.target.value)} />
        </div>
        <div className="flex flex-col gap-1">
          <Label htmlFor="coach-goal">Goal weight (lb)</Label>
          <Input id="coach-goal" type="number" step="0.1" min="1" max="999" value={values.goal_weight_lbs} onChange={(e) => set('goal_weight_lbs', e.target.value)} />
        </div>
      </div>
      <div className="flex items-center gap-3">
        <Switch id="coach-weekly" checked={values.weekly_line_on} onCheckedChange={(on) => set('weekly_line_on', on)} />
        <Label htmlFor="coach-weekly">Monday weight line</Label>
      </div>
      {profile.missing.length > 0 && (
        <p className="text-[0.8125rem] text-muted-foreground">Still needed: {profile.missing.join(', ')}.</p>
      )}
      <div className="flex items-center gap-3">
        <Button type="submit" disabled={save.isPending}>
          Save profile
        </Button>
        {said &&
          (said.ok ? (
            <span role="status" className="text-[0.9375rem]">
              {said.text}
            </span>
          ) : (
            <ErrorSentence>{said.text}</ErrorSentence>
          ))}
      </div>
    </form>
  )
}

function ProfilePanel() {
  const profile = useCoachProfile()
  return (
    <Panel title="Your profile" description="Private to you; nobody else in the household sees it.">
      <Loaded query={profile} what="profile" quiet empty="" isEmpty={() => false} render={(p) => <ProfileForm profile={p} />} />
    </Panel>
  )
}

export function CoachTab() {
  return (
    <div className="flex flex-col gap-6">
      <EnergyPanel />
      <TrendPanel />
      <SavedMealsPanel />
      <WaistPanel />
      <ProfilePanel />
    </div>
  )
}
