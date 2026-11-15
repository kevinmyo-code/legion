import { atMinutes, seriesWords, type TrendCard } from './types'

const W = 320
const H = 96
const PAD = 4

/**
 * One or two series over time as a line, the second dashed so the two differ without colour. The
 * picture is decoration (`aria-hidden`): each series' latest value, low and high are said in words
 * under it.
 */
export function TrendView({ card }: { card: TrendCard }) {
  const all = card.series.flatMap((s) => s.points)
  return (
    <>
      {all.length > 1 && <Chart card={card} />}
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

function Chart({ card }: { card: TrendCard }) {
  const all = card.series.flatMap((s) => s.points)
  const xs = all.map((p) => atMinutes(p.at))
  const ys = all.map((p) => p.value)
  const [x0, x1] = [Math.min(...xs), Math.max(...xs)]
  const [y0, y1] = [Math.min(...ys), Math.max(...ys)]
  const x = (at: string) => PAD + ((atMinutes(at) - x0) / Math.max(1, x1 - x0)) * (W - 2 * PAD)
  const y = (v: number) => H - PAD - ((v - y0) / (y1 - y0 || 1)) * (H - 2 * PAD)
  return (
    <svg data-card="chart" aria-hidden="true" viewBox={`0 0 ${W} ${H}`} className="h-24 w-full text-foreground">
      {card.series.map((series, index) =>
        series.points.length > 1 ? (
          <polyline
            key={index}
            fill="none"
            stroke="currentColor"
            strokeWidth={index === 0 ? 2 : 1.5}
            strokeDasharray={index === 0 ? undefined : '5 4'}
            points={series.points.map((p) => `${x(p.at).toFixed(1)},${y(p.value).toFixed(1)}`).join(' ')}
          />
        ) : series.points.length === 1 ? (
          <circle key={index} cx={x(series.points[0].at)} cy={y(series.points[0].value)} r={2.5} fill="currentColor" />
        ) : null,
      )}
    </svg>
  )
}
