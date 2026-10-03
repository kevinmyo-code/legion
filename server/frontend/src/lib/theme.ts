import { THEME_COLOR_DARK, THEME_COLOR_LIGHT } from './theme-colors'

/**
 * Light, dark, or whatever the device says.
 *
 * `system` is the default and means "follow `prefers-color-scheme`, live": if
 * the phone flips to dark at sunset the page flips with it. `light` and `dark`
 * are a person's override and are stored per device.
 *
 * The stored value is read inside try/catch because storage is allowed to be
 * absent or throw (Safari private windows, a locked-down profile, a browser
 * with storage turned off). Unreadable storage means `system`, never an error:
 * a theme preference is not worth a broken page.
 *
 * `index.html` carries a tiny inline script that does the same read and sets
 * the `dark` class before first paint, so a dark device never flashes white.
 * It cannot import this file (it runs before any bundle), so the two are kept
 * honest by `theme.test.ts`, which executes the real inline script and checks
 * it agrees with this module.
 */
export type ThemePref = 'system' | 'light' | 'dark'

/** localStorage key. The `.v1` is the format version, as with `legion.pins.v1`. */
export const THEME_KEY = 'legion.theme.v1'

const DARK_QUERY = '(prefers-color-scheme: dark)'

export function isThemePref(value: unknown): value is ThemePref {
  return value === 'system' || value === 'light' || value === 'dark'
}

/** The stored preference; `system` when nothing valid is stored or storage throws. */
export function getThemePref(): ThemePref {
  try {
    const stored = window.localStorage.getItem(THEME_KEY)
    return isThemePref(stored) ? stored : 'system'
  } catch {
    return 'system'
  }
}

function systemPrefersDark(): boolean {
  // jsdom and very old browsers have no `matchMedia`; no signal means light.
  if (typeof window.matchMedia !== 'function') return false
  return window.matchMedia(DARK_QUERY).matches
}

/** Whether a preference resolves to dark right now. */
export function resolvesDark(pref: ThemePref): boolean {
  if (pref === 'dark') return true
  if (pref === 'light') return false
  return systemPrefersDark()
}

/**
 * Put the preference on the page: the `dark` class on `<html>` (what the CSS
 * keys off) and the `theme-color` metas (what the browser chrome and the
 * installed app's status bar are tinted by).
 *
 * The metas ship as a light/dark pair keyed by `media`, which is right for
 * `system`. An explicit choice that disagrees with the device would leave the
 * chrome the wrong colour, so for `light` and `dark` every meta is set to the
 * chosen colour, and `system` puts each back.
 */
export function applyTheme(pref: ThemePref = getThemePref()): void {
  const dark = resolvesDark(pref)
  const root = document.documentElement
  root.classList.toggle('dark', dark)
  root.style.colorScheme = dark ? 'dark' : 'light'

  document.querySelectorAll<HTMLMetaElement>('meta[name="theme-color"]').forEach((meta) => {
    const own = meta.dataset.scheme === 'dark' ? THEME_COLOR_DARK : THEME_COLOR_LIGHT
    meta.content = pref === 'system' ? own : dark ? THEME_COLOR_DARK : THEME_COLOR_LIGHT
  })
}

/** Store the preference (best effort) and apply it. */
export function setThemePref(pref: ThemePref): void {
  try {
    window.localStorage.setItem(THEME_KEY, pref)
  } catch {
    // Not stored; it still applies for this page load.
  }
  applyTheme(pref)
  listeners.forEach((listener) => listener())
}

const listeners = new Set<() => void>()

/** For `useSyncExternalStore`: re-render when the preference changes. */
export function subscribeThemePref(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/**
 * Follow the device live while the preference is `system`. Call once at
 * startup; returns the unsubscribe.
 */
export function watchSystemTheme(): () => void {
  if (typeof window.matchMedia !== 'function') return () => {}
  const query = window.matchMedia(DARK_QUERY)
  const onChange = () => {
    if (getThemePref() === 'system') applyTheme('system')
  }
  query.addEventListener('change', onChange)
  return () => query.removeEventListener('change', onChange)
}

/** The next preference in the toggle's cycle: System, Light, Dark, System. */
export function nextThemePref(pref: ThemePref): ThemePref {
  return pref === 'system' ? 'light' : pref === 'light' ? 'dark' : 'system'
}

export const THEME_LABEL: Record<ThemePref, string> = {
  system: 'System',
  light: 'Light',
  dark: 'Dark',
}
