import type { PieceKind } from './rules'

/**
 * The three board looks (retro-games, PWA polish). The rules never see these;
 * only the canvas and the piece icons do. The choice is remembered per device.
 */
export type ThemeId = 'classic' | 'neon' | 'soft'

export interface BoardTheme {
  id: ThemeId
  label: string
  /** The well behind the pieces: always distinct from the page around it. */
  well: string
  grid: string
  /** The left, right and floor walls. */
  wall: string
  /** Neon only: a glow on the wall and pieces, in canvas shadowBlur pixels. */
  glow: number
  fill: Record<PieceKind, string>
  /** A piece's outline and bevel shade. */
  edge: Record<PieceKind, string>
  /** Pixel-ish inner bevel, Classic. */
  bevel: boolean
}

const same = (colour: string): Record<PieceKind, string> => ({
  I: colour, J: colour, L: colour, O: colour, S: colour, T: colour, Z: colour,
})

/** Game Boy DMG: four greens. Pieces use the two darkest on the lightest well. */
const CLASSIC: BoardTheme = {
  id: 'classic',
  label: 'Classic',
  well: '#9bbc0f',
  grid: '#8bac0f',
  wall: '#0f380f',
  glow: 0,
  fill: { I: '#306230', J: '#0f380f', L: '#306230', O: '#0f380f', S: '#306230', T: '#0f380f', Z: '#306230' },
  edge: same('#0f380f'),
  bevel: true,
}

/** Guideline colours on a near-black well. */
const NEON: BoardTheme = {
  id: 'neon',
  label: 'Neon',
  well: '#07080d',
  grid: '#1b1f2b',
  wall: '#e8f4ff',
  glow: 8,
  fill: { I: '#22d3ee', O: '#facc15', T: '#a855f7', S: '#22c55e', Z: '#ef4444', J: '#3b82f6', L: '#f97316' },
  edge: { I: '#a5f3fc', O: '#fef08a', T: '#d8b4fe', S: '#86efac', Z: '#fca5a5', J: '#93c5fd', L: '#fdba74' },
  bevel: false,
}

/** Light well in the web's soft palette, pastel pieces, a dark wall line. */
const SOFT: BoardTheme = {
  id: 'soft',
  label: 'Soft',
  well: '#ffffff',
  grid: '#e7e3ef',
  wall: '#3d3a4b',
  glow: 0,
  fill: { I: '#8fd9ec', O: '#f7dd7a', T: '#c9a6ea', S: '#9fdca3', Z: '#f1a0a0', J: '#9db0ee', L: '#f4be8b' },
  edge: { I: '#4aa8c4', O: '#c9a82c', T: '#8f63c2', S: '#5ca862', Z: '#c46262', J: '#6179c8', L: '#c98842' },
  bevel: false,
}

export const THEMES: Record<ThemeId, BoardTheme> = { classic: CLASSIC, neon: NEON, soft: SOFT }
export const THEME_ORDER: ThemeId[] = ['classic', 'neon', 'soft']
export const DEFAULT_THEME: ThemeId = 'soft'
export const THEME_KEY = 'legion.tetris.theme'

/** The remembered theme, or the default when storage is missing or throws. */
export function loadTheme(): ThemeId {
  try {
    const stored = window.localStorage.getItem(THEME_KEY)
    if (stored === 'classic' || stored === 'neon' || stored === 'soft') return stored
  } catch {
    // Storage blocked: fall through to the default.
  }
  return DEFAULT_THEME
}

/** Remember the theme. Returns false when storage refused; the choice still holds for this visit. */
export function saveTheme(id: ThemeId): boolean {
  try {
    window.localStorage.setItem(THEME_KEY, id)
    return true
  } catch {
    return false
  }
}

export function nextTheme(id: ThemeId): ThemeId {
  return THEME_ORDER[(THEME_ORDER.indexOf(id) + 1) % THEME_ORDER.length]
}
