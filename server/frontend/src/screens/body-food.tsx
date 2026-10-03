import { useState } from 'react'

import { mealLogs, mealTargets, type MealLog, type MealTarget } from '@/api/aspects'
import { useRows, wire } from '@/api/synced'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Meter } from '@/components/workbench/charts'
import { CrudPanel } from '@/components/workbench/crud-panel'
import { Loaded } from '@/components/workbench/loaded'
import { Estimate } from '@/components/workbench/page'
import type { FieldSpec, Values } from '@/components/workbench/record-form'
import { inForceOn, macroTotalsOn } from '@/lib/body'
import { formatDay, formatInstant, formatNumber, fromLocalInput, localDay, plural, todayDay, toLocalInput } from '@/lib/figures'

/**
 * Meals against targets.
 *
 * **The logged side is an estimate and says so; the target side is a choice and
 * does not.** A meal's calories and macros are the model's guess from its
 * description (CLAUDE.md section 4 rule 5, and the API's own help text on those
 * four columns), so every figure that comes from them prints the word
 * `estimate` beside it - in the meters, in the table, in the form labels. A
 * target is a number the person typed, printed plain.
 *
 * A meal with no figures is counted and named ("2 meals have no estimate and
 * are not in these totals") rather than ignored, so a low total never reads as a
 * light day when it is a day with gaps.
 */

function macro(value: number | null | undefined, unit: string): string {
  return value === null || value === undefined ? '' : `${formatNumber(value)}${unit}`
}

const MEAL_FIELDS: FieldSpec[] = [
  { name: 'description', label: 'What you ate', kind: 'text', required: true, placeholder: 'Chicken, rice and greens' },
  { name: 'logged_at', label: 'When', kind: 'datetime', required: true },
  { name: 'calories_kcal', label: 'Calories, kcal (estimate)', kind: 'number', step: '1', min: '0' },
  { name: 'protein_g', label: 'Protein, g (estimate)', kind: 'number', step: '0.1', min: '0' },
  { name: 'carbs_g', label: 'Carbs, g (estimate)', kind: 'number', step: '0.1', min: '0' },
  { name: 'fat_g', label: 'Fat, g (estimate)', kind: 'number', step: '0.1', min: '0' },
]

function optionalNumber(value: string, whole = false): number | null {
  if (value.trim() === '') return null
  return whole ? Math.round(Number(value)) : Number(value)
}

function DayMeters({ day, meals }: { day: string; meals: MealLog[] }) {
  const targets = useRows(mealTargets)
  const totals = macroTotalsOn(meals, day)
  return (
    <div className="flex flex-col gap-4 rounded-card bg-surface-2 p-4">
      <p className="text-[0.8125rem] text-muted-foreground">
        What you logged on {formatDay(day)} against the target in force that day. Logged figures are estimates;
        the target is the number you set.
      </p>
      <Loaded
        query={targets}
        what="meal targets"
        quiet
        empty="No meal target is set, so there is nothing to measure these meals against. Set one below."
        render={(all) => {
          const target = inForceOn(all, day, (row) => row.effective_from_date)
          if (target === null) {
            return (
              <p className="text-[0.9375rem] text-muted-foreground">
                No meal target was in force on {formatDay(day)}; the earliest starts later. Logged that day:{' '}
                {formatNumber(totals.calories)} kcal <Estimate />.
              </p>
            )
          }
          return (
            <div className="flex flex-col gap-4">
              <Meter
                label="Calories"
                value={totals.calories}
                target={target.calories_kcal}
                valueText={`${formatNumber(totals.calories)} kcal`}
                targetText={`${formatNumber(target.calories_kcal)} kcal`}
                extra={<Estimate />}
              />
              {(
                [
                  ['Protein', totals.protein, target.protein_g],
                  ['Carbs', totals.carbs, target.carbs_g],
                  ['Fat', totals.fat, target.fat_g],
                ] as const
              ).map(([label, value, goal]) => (
                <Meter
                  key={label}
                  label={label}
                  value={value}
                  target={goal}
                  valueText={`${formatNumber(value)} g`}
                  targetText={`${formatNumber(goal)} g`}
                  extra={<Estimate />}
                />
              ))}
            </div>
          )
        }}
      />
      {totals.mealsWithoutEstimate > 0 && (
        <p className="text-[0.8125rem] text-muted-foreground">
          {plural(totals.mealsWithoutEstimate, 'meal')} that day {totals.mealsWithoutEstimate === 1 ? 'has' : 'have'}{' '}
          no estimate and {totals.mealsWithoutEstimate === 1 ? 'is' : 'are'} not in these totals.
        </p>
      )}
    </div>
  )
}

const TARGET_FIELDS: FieldSpec[] = [
  { name: 'calories_kcal', label: 'Calories, kcal', kind: 'number', required: true, step: '1', min: '1' },
  { name: 'protein_g', label: 'Protein, g', kind: 'number', required: true, step: '1', min: '0' },
  { name: 'carbs_g', label: 'Carbs, g', kind: 'number', required: true, step: '1', min: '0' },
  { name: 'fat_g', label: 'Fat, g', kind: 'number', required: true, step: '1', min: '0' },
  { name: 'effective_from_date', label: 'Starts on', kind: 'date', required: true },
]

export function MealsTab() {
  const [day, setDay] = useState(todayDay())

  return (
    <div className="flex flex-col gap-6">
      <CrudPanel
        table={mealLogs}
        title="Meals"
        description="Calories and macros are estimates: nothing on a plate states them."
        addLabel="Log a meal"
        what="meals"
        empty="No meals logged yet. Add one and its estimated macros will be measured against your target."
        actions={
          <div className="flex items-center gap-2">
            <Label htmlFor="meal-day" className="text-[0.8125rem] text-muted-foreground">
              Day
            </Label>
            <Input
              id="meal-day"
              type="date"
              value={day}
              onChange={(event) => event.target.value && setDay(event.target.value)}
              className="h-10 w-44"
            />
          </div>
        }
        select={(rows) => rows.filter((meal) => localDay(meal.logged_at) === day)}
        emptySelection={`No meals logged on ${formatDay(day)}. Pick another day, or log one.`}
        sort={(a, b) => a.logged_at.localeCompare(b.logged_at)}
        above={(_chosen, all) => <DayMeters day={day} meals={all} />}
        columns={[
          { header: 'When', cell: (row) => formatInstant(row.logged_at) },
          { header: 'Meal', cell: (row) => <span className="font-medium">{row.description}</span> },
          {
            header: 'Macros',
            cell: (row) => {
              const parts = [
                macro(row.calories_kcal, ' kcal'),
                row.protein_g != null ? `${macro(row.protein_g, ' g')} protein` : '',
                row.carbs_g != null ? `${macro(row.carbs_g, ' g')} carbs` : '',
                row.fat_g != null ? `${macro(row.fat_g, ' g')} fat` : '',
              ].filter(Boolean)
              return parts.length === 0 ? (
                <span className="text-muted-foreground">No estimate</span>
              ) : (
                <span className="inline-flex flex-wrap items-center gap-x-2">
                  <span>{parts.join(', ')}</span>
                  <Estimate />
                </span>
              )
            },
          },
        ]}
        fields={MEAL_FIELDS}
        identity={(row) => row.origin_guid}
        initial={(row): Values => ({
          description: row?.description ?? '',
          logged_at: toLocalInput(row?.logged_at ?? new Date()),
          calories_kcal: row?.calories_kcal != null ? String(row.calories_kcal) : '',
          protein_g: row?.protein_g != null ? String(row.protein_g) : '',
          carbs_g: row?.carbs_g != null ? String(row.carbs_g) : '',
          fat_g: row?.fat_g != null ? String(row.fat_g) : '',
        })}
        toBody={(values, row, guid) =>
          wire<MealLog>({
            description: values.description.trim(),
            calories_kcal: optionalNumber(values.calories_kcal, true),
            protein_g: optionalNumber(values.protein_g),
            carbs_g: optionalNumber(values.carbs_g),
            fat_g: optionalNumber(values.fat_g),
            logged_at: fromLocalInput(values.logged_at),
            source_image_path: row?.source_image_path ?? null,
            trust_tier: 'REPORTED',
            origin_guid: guid,
          })
        }
        formExtra={() => (
          <p className="text-[0.8125rem] text-muted-foreground">
            Leave a macro blank if you do not know it. It is saved as a figure you gave, shown as an estimate.
          </p>
        )}
        rowLabel={(row) => `"${row.description}"`}
        deleteConsequence="This meal is removed for everyone in the household."
      />
      <CrudPanel
        table={mealTargets}
        title="Meal targets"
        description="What you are aiming for each day. A new target starts on its own date; earlier days keep the one they had."
        addLabel="Set a target"
        what="meal targets"
        empty="No meal target set yet. Meals are logged, but there is nothing to measure them against."
        sort={(a, b) => b.effective_from_date.localeCompare(a.effective_from_date)}
        columns={[
          { header: 'From', cell: (row) => formatDay(row.effective_from_date) },
          { header: 'Calories', align: 'right', cell: (row) => `${formatNumber(row.calories_kcal)} kcal` },
          { header: 'Protein', align: 'right', cell: (row) => `${formatNumber(row.protein_g)} g` },
          { header: 'Carbs', align: 'right', cell: (row) => `${formatNumber(row.carbs_g)} g` },
          { header: 'Fat', align: 'right', cell: (row) => `${formatNumber(row.fat_g)} g` },
        ]}
        fields={TARGET_FIELDS}
        identity={(row) => row.origin_guid}
        initial={(row): Values => ({
          calories_kcal: row ? String(row.calories_kcal) : '',
          protein_g: row ? String(row.protein_g) : '',
          carbs_g: row ? String(row.carbs_g) : '',
          fat_g: row ? String(row.fat_g) : '',
          effective_from_date: row?.effective_from_date ?? todayDay(),
        })}
        toBody={(values, _row, guid) =>
          wire<MealTarget>({
            calories_kcal: Math.round(Number(values.calories_kcal)),
            protein_g: Number(values.protein_g),
            carbs_g: Number(values.carbs_g),
            fat_g: Number(values.fat_g),
            effective_from_date: values.effective_from_date,
            origin_guid: guid,
          })
        }
        rowLabel={(row) => `the meal target from ${formatDay(row.effective_from_date)}`}
        deleteConsequence="This target is removed. Days it covered fall back to the one before it."
      />
    </div>
  )
}
