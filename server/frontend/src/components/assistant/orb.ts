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

  constructor(count = POINTS) {
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
    const dot = pixelRatio * 1.7
    const { ink, accent } = colours
    const pts = this.pts
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
      ctx.fillStyle = `rgba(${c0},${c1},${c2},${alpha.toFixed(3)})`
      const s = dot * (0.55 + depth * 0.9)
      ctx.fillRect(cx + x1 * R - s / 2, cx + y2 * R - s / 2, s, s)
    }
  }
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
