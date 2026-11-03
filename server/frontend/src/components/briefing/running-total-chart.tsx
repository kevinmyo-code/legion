import { Area, CartesianGrid, ComposedChart, ReferenceLine, XAxis, YAxis } from 'recharts'

import { ChartContainer, ChartTooltip, ChartTooltipContent, type ChartConfig } from '@/components/ui/chart'
import { money, type DayTotal } from '@/lib/briefing'
import { formatShortDay } from '@/lib/figures'

/**
 * The month's running total against its budget: one y-axis in dollars, a 2px
 * line over a faint fill for what was spent, and a dashed 2px line for the
 * budget. Two series, so a legend names both (by line style as well as colour),
 * and the same points are one press away as a table. Text is in the ink tokens,
 * never the series colour; the grid is the recessive outline-variant.
 */

const CONFIG = {
  cents: { label: 'Spent so far', color: 'var(--primary)' },
} satisfies ChartConfig

function axisMoney(cents: number): string {
  if (cents === 0) return '$0'
  const dollars = cents / 100
  return dollars >= 1000 ? `$${Math.round(dollars / 100) / 10}k` : `$${Math.round(dollars)}`
}

/** Four or five round ticks from $0 that reach past `top` (cents). */
export function niceTicks(top: number): number[] {
  const raw = Math.max(top, 100) / 4
  const power = 10 ** Math.floor(Math.log10(raw))
  const step = [1, 2, 2.5, 5, 10].map((m) => m * power).find((candidate) => candidate >= raw)!
  const ticks = [0]
  while (ticks[ticks.length - 1] < top) ticks.push(ticks.length * step)
  return ticks
}

export function RunningTotalChart({
  points,
  budgetCents,
  currency,
}: {
  points: readonly DayTotal[]
  budgetCents: number | null
  currency: string
}) {
  const top = Math.max(points.at(-1)?.cents ?? 0, budgetCents ?? 0)
  const ticks = niceTicks(top * 1.05)
  const last = points.at(-1)
  const description =
    `Running total of categorised spending by day, from ${money(points[0].cents, currency)} on day 1 ` +
    `to ${money(last!.cents, currency)} on day ${last!.day}` +
    (budgetCents !== null ? `, against a monthly budget of ${money(budgetCents, currency)}.` : '.')

  return (
    <div className="flex flex-col gap-1.5">
      <ul aria-label="Legend" className="flex flex-wrap gap-x-4 gap-y-1 text-[0.75rem] text-muted-foreground">
        <li className="flex items-center gap-1.5">
          <span aria-hidden="true" className="h-0.5 w-4 rounded-full bg-primary" />
          Spent so far
        </li>
        {budgetCents !== null && (
          <li className="flex items-center gap-1.5">
            <span aria-hidden="true" className="w-4 border-t-2 border-dashed border-foreground/70" />
            Monthly budget
          </li>
        )}
      </ul>
      <figure role="img" aria-label={description} className="m-0">
        <ChartContainer config={CONFIG} className="aspect-auto h-44 w-full">
          <ComposedChart data={[...points]} margin={{ top: 16, right: 8, bottom: 0, left: 0 }}>
            <CartesianGrid vertical={false} stroke="var(--outline-variant)" strokeOpacity={0.7} />
            <XAxis
              dataKey="date"
              tickLine={false}
              axisLine={false}
              tickMargin={6}
              interval="preserveStartEnd"
              minTickGap={48}
              tickFormatter={(date: string) => formatShortDay(date)}
              tick={{ fill: 'var(--muted-foreground)', fontSize: 11 }}
            />
            <YAxis
              width={40}
              tickLine={false}
              axisLine={false}
              domain={[0, ticks[ticks.length - 1]]}
              ticks={ticks}
              tickFormatter={axisMoney}
              tick={{ fill: 'var(--muted-foreground)', fontSize: 11 }}
            />
            <ChartTooltip
              cursor={{ stroke: 'var(--outline)', strokeWidth: 1 }}
              content={
                <ChartTooltipContent
                  labelFormatter={(_label, payload) => {
                    const point = payload?.[0]?.payload as DayTotal | undefined
                    return point ? formatShortDay(point.date) : ''
                  }}
                  formatter={(_value, _name, item) => {
                    const point = item.payload as DayTotal
                    return (
                      <span className="flex flex-col">
                        <span className="text-foreground tabular-nums">
                          Spent so far {money(point.cents, currency)}
                        </span>
                        {point.unverified && <span className="text-muted-foreground">Includes unverified rows</span>}
                      </span>
                    )
                  }}
                />
              }
            />
            {budgetCents !== null && (
              <ReferenceLine
                y={budgetCents}
                stroke="var(--foreground)"
                strokeOpacity={0.7}
                strokeWidth={2}
                strokeDasharray="5 4"
                label={{
                  value: `Budget ${money(budgetCents, currency)}`,
                  position: 'insideTopRight',
                  fill: 'var(--muted-foreground)',
                  fontSize: 11,
                }}
              />
            )}
            <Area
              dataKey="cents"
              type="linear"
              stroke="var(--color-cents)"
              strokeWidth={2}
              fill="var(--color-cents)"
              fillOpacity={0.12}
              dot={false}
              activeDot={{ r: 4, strokeWidth: 2, stroke: 'var(--card)' }}
              isAnimationActive={false}
            />
          </ComposedChart>
        </ChartContainer>
      </figure>
    </div>
  )
}

/** The same points as the chart, in words and figures. */
export function RunningTotalTable({ points, currency }: { points: readonly DayTotal[]; currency: string }) {
  return (
    <table className="w-full border-collapse text-[0.8125rem] tabular-nums">
      <thead>
        <tr className="text-left text-muted-foreground">
          <th scope="col" className="border-b border-outline-variant px-1.5 py-1 font-medium">Day</th>
          <th scope="col" className="border-b border-outline-variant px-1.5 py-1 text-right font-medium">Spent so far</th>
          <th scope="col" className="border-b border-outline-variant px-1.5 py-1 font-medium">Checked</th>
        </tr>
      </thead>
      <tbody>
        {points.map((point) => (
          <tr key={point.date}>
            <td className="border-b border-outline-variant px-1.5 py-1">{formatShortDay(point.date)}</td>
            <td className="border-b border-outline-variant px-1.5 py-1 text-right">{money(point.cents, currency)}</td>
            <td className="border-b border-outline-variant px-1.5 py-1">{point.unverified ? 'Includes unverified rows' : 'Verified'}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
