import { HeadlineView } from './readings'
import { atMinutes, axisWords, FLAG_WORDS, seriesWords, trendHeadline, unitWords, type TrendCard } from './types'

const W = 320
const H = 112
const PAD = 6
const DAY = 1440
/** A run of points breaks where a step is over this many times the series' usual step. */
const GAP_STEPS = 3

/**
 * One or two series over time, the second dashed so the two differ without colour, with the y
 * span said at the left (low, high), the first and last time under it, and the guide line (a
 * target, a goal, an alert threshold) dashed in the muted ink. Bars stand from zero in day slots,
 * so a night with nothing logged is an empty slot, never a zero. The plot is decoration
 * (`aria-hidden`): the axis words, the legend and each series' sentence say what it shows.
 */
export function TrendView({ card }: { card: TrendCard }) {
  const all = card.series.flatMap((s) => s.points)
  const bars = card.style === 'bars'
  return (
    <>
      <HeadlineView headline={trendHeadline(card)} />
      {(all.length > 1 || (bars && all.length === 1)) && <Chart card={card} />}
      <ul data-card="trend" className="mt-1.5 flex flex-col gap-0.5">
        {card.series.map((series, index) => (
          <li key={`${index}-${series.name}`} data-card="series" data-flag={series.flag}>
            {seriesWords(series, card.unit)}
          </li>
        ))}
      </ul>
    </>
  )
}

/** Indices of `ats` in runs, broken where a step is over GAP_STEPS times the median step. */
export function runs(ats: string[]): number[][] {
  if (ats.length === 0) return []
  const ms = ats.map(atMinutes)
  const steps = ms.slice(1).map((m, i) => m - ms[i])
  const usual = [...steps].sort((a, b) => a - b)[Math.floor(steps.length / 2)] ?? 0
  const out: number[][] = [[0]]
  steps.forEach((step, i) => {
    if (usual > 0 && step > usual * GAP_STEPS) out.push([])
    out[out.length - 1].push(i + 1)
  })
  return out
}

function Chart({ card }: { card: TrendCard }) {
  const bars = card.style === 'bars'
  const all = card.series.flatMap((s) => s.points)
  const values = all.map((p) => p.value)
  const [lo, hi] = [Math.min(...values), Math.max(...values)]
  const g = card.guide?.value
  const reach = Math.max(hi - lo, 1)
  const near = g !== undefined && g >= lo - reach && g <= hi + reach
  const withGuide = g !== undefined && (near || bars) ? [g] : []
  const low = bars ? Math.min(0, lo) : Math.min(...values, ...withGuide)
  const highRaw = Math.max(...values, ...withGuide)
  const high = highRaw > low ? highRaw : low + 1
  const y = (v: number) => PAD + (1 - (v - low) / (high - low)) * (H - 2 * PAD)
  const xs = all.map((p) => atMinutes(p.at))
  const [x0, x1] = [Math.min(...xs), Math.max(...xs)]
  const days = Math.max(1, Math.floor((x1 - x0) / DAY) + 1)
  const slot = (W - 2 * PAD) / days
  const x = (at: string) => {
    const m = atMinutes(at) - x0
    if (bars) return PAD + (Math.floor(m / DAY) + 0.5) * slot
    return PAD + (x1 > x0 ? m / (x1 - x0) : 0.5) * (W - 2 * PAD)
  }
  const first = all.reduce((a, b) => (atMinutes(b.at) < atMinutes(a.at) ? b : a)).at
  const last = all.reduce((a, b) => (atMinutes(b.at) > atMinutes(a.at) ? b : a)).at
  const lead = card.series[0].points
  const off = g === undefined || withGuide.length > 0 ? '' : g < low ? ', below the chart' : ', above the chart'
  return (
    <div className="mt-1">
      <div className="flex gap-2">
        <div aria-hidden="true" className="flex w-16 flex-none flex-col justify-between text-[0.75rem] text-muted-foreground">
          <span data-card="high">{unitWords(high, card.unit)}</span>
          <span data-card="low">{unitWords(low, card.unit)}</span>
        </div>
        <svg data-card="chart" aria-hidden="true" viewBox={`0 0 ${W} ${H}`} className="h-28 w-full text-foreground">
          <line x1="0" x2={W} y1="0.5" y2="0.5" className="stroke-border" />
          <line x1="0" x2={W} y1={H - 0.5} y2={H - 0.5} className="stroke-border" />
          {withGuide.length > 0 && (
            <line data-card="guide" x1="0" x2={W} y1={y(g!)} y2={y(g!)} className="stroke-muted-foreground" strokeWidth="1.5" strokeDasharray="4 6" />
          )}
          {bars
            ? lead.map((p) => (
                <rect
                  key={p.at}
                  data-card="column"
                  x={x(p.at) - Math.min(slot * 0.3, 12)}
                  width={Math.min(slot * 0.6, 24)}
                  y={Math.min(y(p.value), y(0))}
                  height={Math.abs(y(0) - y(p.value))}
                  fill="currentColor"
                />
              ))
            : card.series.map((series, index) =>
                runs(series.points.map((p) => p.at)).map((run, r) =>
                  run.length > 1 ? (
                    <polyline
                      key={`${index}-${r}`}
                      fill="none"
                      stroke="currentColor"
                      strokeWidth={index === 0 ? 2 : 1.5}
                      strokeLinejoin="round"
                      strokeLinecap="round"
                      strokeDasharray={index === 0 ? undefined : '5 4'}
                      points={run.map((i) => `${x(series.points[i].at).toFixed(1)},${y(series.points[i].value).toFixed(1)}`).join(' ')}
                    />
                  ) : (
                    <circle key={`${index}-${r}`} cx={x(series.points[run[0]].at)} cy={y(series.points[run[0]].value)} r={2.5} fill="currentColor" />
                  ),
                ),
              )}
          {!bars && lead.length > 0 && (
            <circle data-card="latest" cx={x(lead[lead.length - 1].at)} cy={y(lead[lead.length - 1].value)} r={4} fill="currentColor" className="stroke-background" strokeWidth="2" />
          )}
        </svg>
      </div>
      <p aria-hidden="true" className="ml-18 flex justify-between pl-0 text-[0.75rem] text-muted-foreground">
        <span>{axisWords(first, first.slice(0, 10) !== last.slice(0, 10))}</span>
        {last !== first && <span>{axisWords(last, first.slice(0, 10) !== last.slice(0, 10))}</span>}
      </p>
      <p data-card="legend" className="mt-0.5 flex flex-wrap gap-x-4 text-[0.8125rem]">
        {card.series.length > 1 &&
          card.series.map((s, i) => (
            <span key={s.name} className="inline-flex items-center gap-1">
              <svg aria-hidden="true" viewBox="0 0 22 4" className="h-1 w-5">
                <line x1="0" x2="22" y1="2" y2="2" stroke="currentColor" strokeWidth="2" strokeDasharray={i === 0 ? undefined : '5 4'} />
              </svg>
              {s.flag ? `${s.name} (${FLAG_WORDS[s.flag]})` : s.name}
            </span>
          ))}
        {card.guide && (
          <span data-card="guide-words" className="inline-flex items-center gap-1">
            <svg aria-hidden="true" viewBox="0 0 22 4" className="h-1 w-5 text-muted-foreground">
              <line x1="0" x2="22" y1="2" y2="2" stroke="currentColor" strokeWidth="2" strokeDasharray="4 4" />
            </svg>
            {card.guide.label + off}
          </span>
        )}
      </p>
    </div>
  )
}
