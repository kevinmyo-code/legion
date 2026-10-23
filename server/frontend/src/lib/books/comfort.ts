/**
 * The reader's comforts, mirroring the phone's (ticket 06): Day / Sepia / Night,
 * a warm "night light" overlay with a strength, text size for EPUB, full screen.
 * Settings are PER DEVICE and never synced (ticket 07 ruling 4).
 */

export type Theme = 'day' | 'sepia' | 'night'

export const THEMES: readonly Theme[] = ['day', 'sepia', 'night']

export interface ReaderSettings {
  theme: Theme
  warmOn: boolean
  /** 0-100 */
  warmStrength: number
  /** EPUB text size as a percentage of the book's own size. */
  textSize: number
}

export const DEFAULT_SETTINGS: ReaderSettings = { theme: 'day', warmOn: false, warmStrength: 50, textSize: 100 }

export const TEXT_SIZE_MIN = 70
export const TEXT_SIZE_MAX = 250
export const TEXT_SIZE_STEP = 10

const KEY = 'legion.books.settings'

export const PALETTE: Record<Theme, { background: string; text: string; link: string }> = {
  day: { background: '#ffffff', text: '#1b1b1f', link: '#3b3fb3' },
  sepia: { background: '#f4ecd8', text: '#433422', link: '#7a4b12' },
  night: { background: '#121212', text: '#d6d6d6', link: '#9fb2ff' },
}

export function nextTheme(theme: Theme): Theme {
  return THEMES[(THEMES.indexOf(theme) + 1) % THEMES.length]
}

export const THEME_LABEL: Record<Theme, string> = { day: 'Day', sepia: 'Sepia', night: 'Night' }

export function loadSettings(storage: Pick<Storage, 'getItem'> = localStorage): ReaderSettings {
  try {
    const raw = JSON.parse(storage.getItem(KEY) ?? 'null') as Partial<ReaderSettings> | null
    if (!raw) return DEFAULT_SETTINGS
    return {
      theme: THEMES.includes(raw.theme as Theme) ? (raw.theme as Theme) : DEFAULT_SETTINGS.theme,
      warmOn: raw.warmOn === true,
      warmStrength: clamp(Number(raw.warmStrength ?? DEFAULT_SETTINGS.warmStrength), 0, 100),
      textSize: clamp(Number(raw.textSize ?? DEFAULT_SETTINGS.textSize), TEXT_SIZE_MIN, TEXT_SIZE_MAX),
    }
  } catch {
    return DEFAULT_SETTINGS
  }
}

export function saveSettings(settings: ReaderSettings, storage: Pick<Storage, 'setItem'> = localStorage) {
  storage.setItem(KEY, JSON.stringify(settings))
}

function clamp(n: number, lo: number, hi: number): number {
  return Number.isFinite(n) ? Math.min(hi, Math.max(lo, n)) : lo
}

/** The phone's curve: an amber multiply layer, at most ~35% opaque at full strength. */
export function warmOpacity(strength: number): number {
  return (clamp(strength, 0, 100) / 100) * 0.35
}

/**
 * How a PDF page is recoloured. Night inverts lightness (white page to near-black,
 * black text to light grey) and keeps hue, so figures invert too, as on the phone.
 * Sepia tints. Comics are art and are NEVER recoloured, whatever the theme.
 */
export function pageFilter(theme: Theme, kind: 'pdf' | 'comic'): string {
  if (kind === 'comic') return 'none'
  if (theme === 'night') return 'invert(0.88) hue-rotate(180deg)'
  if (theme === 'sepia') return 'sepia(0.35) brightness(0.97)'
  return 'none'
}

export function clampTextSize(size: number): number {
  return clamp(Math.round(size), TEXT_SIZE_MIN, TEXT_SIZE_MAX)
}
