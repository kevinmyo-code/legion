import type { ReactNode } from 'react'
import { Bar, BarChart, CartesianGrid, Line, LineChart, ReferenceLine, XAxis, YAxis } from 'recharts'

import { ChartContainer, ChartTooltip, ChartTooltipContent, type ChartConfig } from '@/components/ui/chart'

/**
 * The only three chart shapes the web uses: a sparkline, bars, and a meter.
 * No donuts, no pies (the phone's frozen chart vocabulary, and the ruling that
 * a share-of-a-whole ring says less than the number beside it).
 *
 * Colours come from the theme tokens (`--primary`, `--done`, `--outline`), which
 * is what makes dark mode a re-binding rather than a second set of chart
 * colours. **A chart is never the only carrier of a figure**: each one is drawn
 * beside the numbers it plots, and each is given a text description for a
 * screen reader. And no chart is drawn with nothing to plot - the caller says
 * "no entries yet" in a sentence instead, because bare axes read as "zero" when
 * they mean "nothing here yet".
 */

export interface Point {
  label: string
  value: number
}

const SPARK_CONFIG = { value: { label: 'Value', color: 'var(--primary)' } } satisfies ChartConfig
const BAR_CONFIG = { value: { label: 'Value', color: 'var(--primary)' } } satisfies ChartConfig

/** A small line over time. Needs at least two points to mean anything; with one
 * it draws a dot's worth of nothing, so callers show the figure instead. */
export function Sparkline({
  points,
  description,
  unit,
}: {
  points: readonly Point[]
  description: string
  unit: string
}) {
  const values = points.map((point) => point.value)
  const low = Math.min(...values)
  const high = Math.max(...values)
  const pad = Math.max((high - low) * 0.15, 0.5)
  return (
    <figure role="img" aria-label={description} className="m-0">
      <ChartContainer config={SPARK_CONFIG} className="aspect-auto h-28 w-full">
        <LineChart data={[...points]} margin={{ top: 8, right: 8, bottom: 4, left: 8 }}>
          <XAxis dataKey="label" hide />
          <YAxis domain={[low - pad, high + pad]} hide />
          <ChartTooltip content={<ChartTooltipContent formatter={(value) => `${value} ${unit}`} />} />
          <Line
            dataKey="value"
            type="monotone"
            stroke="var(--color-value)"
            strokeWidth={2.5}
            dot={{ r: 3, fill: 'var(--color-value)', strokeWidth: 0 }}
            isAnimationActive={false}
          />
        </LineChart>
      </ChartContainer>
    </figure>
  )
}

/** Vertical bars, optionally with one reference line (a target someone chose). */
export function Bars({
  points,
  description,
  unit,
  target,
  targetLabel,
}: {
  points: readonly Point[]
  description: string
  unit: string
  target?: number
  targetLabel?: string
}) {
  const top = Math.max(...points.map((point) => point.value), target ?? 0)
  return (
    <figure role="img" aria-label={description} className="m-0">
      <ChartContainer config={BAR_CONFIG} className="aspect-auto h-44 w-full">
        <BarChart data={[...points]} margin={{ top: 8, right: 8, bottom: 4, left: 0 }}>
          <CartesianGrid vertical={false} stroke="var(--outline-variant)" />
          <XAxis dataKey="label" tickLine={false} axisLine={false} tickMargin={6} />
          <YAxis domain={[0, Math.ceil(top * 1.1)]} width={36} tickLine={false} axisLine={false} />
          <ChartTooltip content={<ChartTooltipContent formatter={(value) => `${value} ${unit}`} />} />
          {target !== undefined && (
            <ReferenceLine
              y={target}
              stroke="var(--outline)"
              strokeDasharray="4 4"
              label={{ value: targetLabel ?? 'target', position: 'insideTopRight', fill: 'var(--muted-foreground)', fontSize: 12 }}
            />
          )}
          <Bar dataKey="value" fill="var(--color-value)" radius={[8, 8, 0, 0]} isAnimationActive={false} />
        </BarChart>
      </ChartContainer>
    </figure>
  )
}

/**
 * A horizontal meter: how far a figure is toward a target someone chose.
 *
 * The numbers are the content; the bar is a glance. It prints "of" the target in
 * words ("1,820 kcal of 2,200 kcal") and never a percentage or a score
 * (spec user story 70: no completion percentages anywhere). A bar over target is
 * capped at the full track and the text says the real figure.
 */
export function Meter({
  label,
  value,
  target,
  valueText,
  targetText,
  extra,
}: {
  label: string
  value: number
  target: number
  valueText: string
  targetText: string
  /** A trust word or a note that belongs beside the figure. */
  extra?: ReactNode
}) {
  const share = target > 0 ? Math.min(1, Math.max(0, value / target)) : 0
  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-0.5">
        <span className="text-[0.9375rem] font-medium">{label}</span>
        <span className="flex flex-wrap items-center gap-x-2 text-[0.9375rem] tabular-nums">
          <span>
            {valueText} of {targetText}
          </span>
          {extra}
        </span>
      </div>
      <div
        role="meter"
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={target}
        aria-valuenow={Math.min(value, target)}
        aria-valuetext={`${valueText} of ${targetText}`}
        className="h-2.5 overflow-hidden rounded-full bg-surface-3"
      >
        <div className="h-full rounded-full bg-primary" style={{ width: `${share * 100}%` }} />
      </div>
    </div>
  )
}
