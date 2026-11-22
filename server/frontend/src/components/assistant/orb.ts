import type { AssistantState } from '@/assistant/live-client'

/**
 * The voice orb's drawing, kept free of React so it can be tested as numbers.
 *
 * Points on a sphere, rotated and displaced each frame. Every mode is a set of
 * numbers (spin, wobble, ripple, a sweeping band, scatter, brightness, size, how
 * much it follows the sound); the renderer eases toward the mode's set, so a
 * change of mode is a morph, not a cut (Kevin, 2026-11-04: "a ball avatar that
 * animates as its thinking or idle", prototype `orb-dock.html`, look A).
 *
 * The orb is never the only signal: `statusWords` says the same thing in words
 * beside it, so a colour-blind visitor or a screen reader loses nothing.
 */

export type OrbMode = 'idle' | 'connecting' | 'listening' | 'thinking' | 'speaking' | 'resting'

/** Which mode the conversation's state shows as. */
export function orbMode(state: AssistantState): OrbMode {
  if (state.phase === 'ended') return 'resting'
  if (state.mic === 'starting' || state.phase === 'connecting') return 'connecting'
  if (state.activity === 'speaking') return 'speaking'
  if (state.activity === 'thinking') return 'thinking'
  if (state.mic === 'on') return 'listening'
  if (state.notice && state.notice.kind.startsWith('mic')) return 'resting'
  return 'idle'
}

/** spin, wobble, ripple, band, scatter, bright, size, react */
type Params = [number, number, number, number, number, number, number, number]

const MODES: Record<OrbMode, Params> = {
  idle: [0.1, 0.05, 0, 0, 0, 0.75, 1, 0],
  connecting: [0.35, 0.03, 0, 0, 0.55, 0.45, 0.8, 0],
  listening: [0.16, 0.06, 0, 0, 0, 0.8, 1, 1],
  thinking: [0.85, 0.04, 0, 1, 0, 0.75, 0.92, 0],
  speaking: [0.22, 0.04, 1, 0, 0, 1, 1.04, 1],
  resting: [0.04, 0.02, 0, 0, 0, 0.3, 0.96, 0],
}

export interface OrbColours {
  /** The dots, as r, g, b. */
  ink: readonly [number, number, number]
  /** The sweep and the ripple crests. */
  accent: readonly [number, number, number]
}

const POINTS = 1500

export class OrbRenderer {
  private readonly pts: Float32Array
  private cur: Params = [...MODES.idle]
  private mode: OrbMode = 'idle'
  private t = 0
  private rot = 0
  private level = 0
  // A mark the points gather into (the Q at the Quanex section, Kevin 2026-11-22): one target per
  // point, x and y in the orb's radius units, and how far the points have gone toward it (0..1).
  private mark: Float32Array | null = null
  private markGoal = 0
  private morph = 0
  private still = false

  private readonly dotScale: number

  /** `dotScale` scales each point; a small orb (the phone's Talk bar) wants fewer, smaller points. */
  constructor(count = POINTS, dotScale = 1) {
    this.dotScale = dotScale
    // Fibonacci sphere: even coverage, no bunching at the poles. x, y, z, seed.
    this.pts = new Float32Array(count * 4)
    for (let i = 0; i < count; i++) {
      const y = 1 - ((i + 0.5) / count) * 2
      const r = Math.sqrt(1 - y * y)
      const th = i * 2.399963
      this.pts.set([Math.cos(th) * r, y, Math.sin(th) * r, fract(Math.sin(i * 12.9898) * 43758.5453)], i * 4)
    }
  }

  setMode(mode: OrbMode) {
    this.mode = mode
  }

  /** The points gather into `targets` (from `markTargets`), or go back to the sphere (null). */
  setMark(targets: Float32Array | null) {
    if (targets) this.mark = targets
    this.markGoal = targets ? 1 : 0
  }

  /** How many points it draws. */
  size(): number {
    return this.pts.length / 4
  }

  /** How far the points have gathered into the mark (tests read this). */
  morphed(): number {
    return this.morph
  }

  /** Where the eased numbers are now (tests read this). */
  params(): readonly number[] {
    return this.cur
  }

  /**
   * Advance by `dt` seconds toward the current mode, following `sound` (0..1,
   * the mic while listening, the reply while speaking). `still` freezes the
   * motion (reduced motion) but still eases the mode, so the orb changes look
   * without moving.
   */
  step(dt: number, sound: number, still: boolean) {
    const d = Math.min(0.05, Math.max(0, dt))
    const goal = MODES[this.mode]
    const k = 1 - Math.pow(0.02, d)
    for (let i = 0; i < goal.length; i++) this.cur[i] += (goal[i] - this.cur[i]) * k
    // About a second to gather or scatter; under reduced motion the same time, as a crossfade.
    this.morph += (this.markGoal - this.morph) * (1 - Math.pow(0.004, d))
    if (Math.abs(this.markGoal - this.morph) < 0.002) this.morph = this.markGoal
    this.still = still
    if (still) {
      this.level = 0
      return
    }
    this.t += d
    this.rot += this.cur[0] * d
    this.level += (Math.min(1, Math.max(0, sound)) - this.level) * (1 - Math.pow(0.0005, d))
  }

  draw(ctx: CanvasRenderingContext2D, size: number, colours: OrbColours, pixelRatio: number) {
    const [, wobble, ripple, band, scatter, bright, sz, react] = this.cur
    const t = this.t
    const lv = this.level * react
    const cx = size / 2
    const R = size * 0.34 * sz * (1 + lv * 0.06)
    ctx.clearRect(0, 0, size, size)
    const cr = Math.cos(this.rot)
    const sr = Math.sin(this.rot)
    const ct = Math.cos(0.38)
    const st = Math.sin(0.38)
    const dot = pixelRatio * 1.7 * this.dotScale
    const { ink, accent } = colours
    const pts = this.pts
    const mark = this.morph > 0 ? this.mark : null
    // Smoothstep, so the gather starts and lands softly.
    const m = mark ? this.morph * this.morph * (3 - 2 * this.morph) : 0
    const fade = this.still && mark !== null
    for (let i = 0; i < pts.length; i += 4) {
      let x = pts[i]
      let y = pts[i + 1]
      let z = pts[i + 2]
      const seed = pts[i + 3]
      // Surface motion: a slow wobble always, bumps with the voice, a ripple from the poles while speaking.
      const n = Math.sin(x * 3.1 + t * 0.9) * Math.sin(y * 3.7 - t * 0.7) * Math.sin(z * 2.9 + t * 0.5)
      const rip = Math.sin(Math.asin(y) * 7 - t * 7) * ripple * (0.035 + lv * 0.09)
      const r =
        1 + n * (wobble + lv * 0.14 * (1 - ripple)) + rip + scatter * (seed - 0.5) * 1.1 * (0.6 + 0.4 * Math.sin(t * 2 + seed * 6))
      // Thinking: a band sweeps the sphere top to bottom.
      const sweep = band * Math.exp(-Math.pow((y - Math.sin(t * 1.6) * 0.9) * 4.2, 2))
      x *= r
      y *= r
      z *= r
      const x1 = x * cr + z * sr
      const z1 = -x * sr + z * cr
      const y2 = y * ct - z1 * st
      const z2 = y * st + z1 * ct
      const depth = (z2 + 1.3) / 2.6
      const twinkle = 0.75 + 0.25 * Math.sin(t * 2 + seed * 40)
      const alpha = Math.min(1, (0.12 + 0.88 * depth * depth) * bright * twinkle + sweep * 0.6)
      const hot = Math.min(1, sweep + (rip > 0 ? ripple * lv * 0.9 : 0))
      const c0 = Math.round(ink[0] + (accent[0] - ink[0]) * hot)
      const c1 = Math.round(ink[1] + (accent[1] - ink[1]) * hot)
      const c2 = Math.round(ink[2] + (accent[2] - ink[2]) * hot)
      const s = dot * (0.55 + depth * 0.9)
      let px = cx + x1 * R
      let py = cx + y2 * R
      if (mark) {
        const j = (i / 4) * 2
        // The mark is flat and still alive: each point shimmers a little around its place.
        const mx = cx + (mark[j] + Math.sin(t * 1.3 + seed * 30) * 0.012) * R * 1.15
        const my = cx + (mark[j + 1] + Math.cos(t * 1.1 + seed * 30) * 0.012) * R * 1.15
        const ma = Math.min(1, (0.7 + 0.3 * twinkle) * Math.max(bright, 0.6))
        if (fade) {
          // Reduced motion: nothing travels; the sphere fades out as the mark fades in.
          ctx.fillStyle = `rgba(${c0},${c1},${c2},${(alpha * (1 - m)).toFixed(3)})`
          ctx.fillRect(px - s / 2, py - s / 2, s, s)
          ctx.fillStyle = `rgba(${accent[0]},${accent[1]},${accent[2]},${(ma * m).toFixed(3)})`
          ctx.fillRect(mx - s / 2, my - s / 2, s, s)
          continue
        }
        px += (mx - px) * m
        py += (my - py) * m
        const a = alpha + (ma - alpha) * m
        const r0 = Math.round(c0 + (accent[0] - c0) * m)
        const r1 = Math.round(c1 + (accent[1] - c1) * m)
        const r2 = Math.round(c2 + (accent[2] - c2) * m)
        ctx.fillStyle = `rgba(${r0},${r1},${r2},${a.toFixed(3)})`
        ctx.fillRect(px - s / 2, py - s / 2, s, s)
        continue
      }
      ctx.fillStyle = `rgba(${c0},${c1},${c2},${alpha.toFixed(3)})`
      ctx.fillRect(px - s / 2, py - s / 2, s, s)
    }
  }
}

/**
 * One target per point inside a mark, from its coverage (`alpha`, a `size` x `size` image's alpha
 * channel, as a canvas draws it): x and y in radius units, centred, the mark's larger side spanning
 * -1..1. The same seeded pick as the points' own seeds, so a mark is the same on every load. No
 * covered pixel (no font to draw with): a ring, so the points still gather into something.
 */
export function markTargets(alpha: ArrayLike<number>, size: number, count: number): Float32Array {
  const pool: number[] = []
  for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) if (alpha[y * size + x] > 128) pool.push(x, y)
  const out = new Float32Array(count * 2)
  const n = pool.length / 2
  let minX = size
  let maxX = 0
  let minY = size
  let maxY = 0
  for (let k = 0; k < pool.length; k += 2) {
    minX = Math.min(minX, pool[k])
    maxX = Math.max(maxX, pool[k])
    minY = Math.min(minY, pool[k + 1])
    maxY = Math.max(maxY, pool[k + 1])
  }
  const midX = (minX + maxX) / 2
  const midY = (minY + maxY) / 2
  const half = Math.max(1, (Math.max(maxX - minX, maxY - minY) + 1) / 2)
  for (let i = 0; i < count; i++) {
    const seed = fract(Math.sin(i * 12.9898) * 43758.5453)
    const jitter = fract(Math.sin(i * 78.233) * 12543.123)
    if (n === 0) {
      const a = seed * Math.PI * 2
      out[i * 2] = Math.cos(a) * 0.8
      out[i * 2 + 1] = Math.sin(a) * 0.8
      continue
    }
    const k = Math.floor(seed * n) * 2
    out[i * 2] = (pool[k] + jitter - 0.5 - midX) / half
    out[i * 2 + 1] = (pool[k + 1] + fract(seed * 7) - 0.5 - midY) / half
  }
  return out
}


function fract(value: number): number {
  return value - Math.floor(value)
}

/** `#rrggbb` or `rgb(r, g, b)` (a computed CSS colour) to r, g, b; null if unreadable. */
export function parseColour(value: string): [number, number, number] | null {
  const text = value.trim()
  const hex = /^#([0-9a-f]{6})$/i.exec(text)
  if (hex) {
    const n = parseInt(hex[1], 16)
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255]
  }
  const rgb = /^rgba?\(\s*(\d+)[\s,]+(\d+)[\s,]+(\d+)/i.exec(text)
  if (rgb) return [Number(rgb[1]), Number(rgb[2]), Number(rgb[3])]
  return null
}
