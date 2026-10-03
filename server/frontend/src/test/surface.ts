import type { Surface } from '../lib/surface'

/**
 * Pick which surface a test renders at, by stubbing `window.matchMedia` the way
 * a real browser at that width would answer it.
 *
 * jsdom has no layout and no `matchMedia`, so without this every component that
 * asks which surface it is on gets nothing. The stub answers the one width query
 * `useSurface` asks (`min-width: 1024px`), answers `prefers-color-scheme: dark`
 * from `prefersDark`, and fires `change` listeners when `set` is called, so a
 * test can resize across the 1024 px line and watch one tree replace the other.
 *
 * `src/test/setup.ts` installs the family surface before every test; call this
 * at the top of a test to override it.
 */

const WIDE_QUERY = '(min-width: 1024px)'
const DARK_QUERY = '(prefers-color-scheme: dark)'

export interface SurfaceStub {
  /** Cross the 1024 px line, notifying listeners like a window resize would. */
  set(surface: Surface): void
  /** Flip the device colour scheme, notifying listeners. */
  setPrefersDark(dark: boolean): void
}

interface Listener {
  query: string
  fn: (event: MediaQueryListEvent) => void
}

export function stubSurface(initial: Surface, prefersDark = false): SurfaceStub {
  let surface = initial
  let dark = prefersDark
  const listeners = new Set<Listener>()

  const matches = (query: string): boolean => {
    if (query === WIDE_QUERY) return surface === 'workbench'
    if (query === DARK_QUERY) return dark
    return false
  }

  const notify = (query: string) => {
    for (const listener of listeners) {
      if (listener.query === query) {
        listener.fn({ matches: matches(query), media: query } as MediaQueryListEvent)
      }
    }
  }

  window.matchMedia = ((query: string) => ({
    get matches() {
      return matches(query)
    },
    media: query,
    onchange: null,
    addEventListener: (_type: string, fn: (event: MediaQueryListEvent) => void) => {
      listeners.add({ query, fn })
    },
    removeEventListener: (_type: string, fn: (event: MediaQueryListEvent) => void) => {
      for (const listener of listeners) {
        if (listener.query === query && listener.fn === fn) listeners.delete(listener)
      }
    },
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  })) as typeof window.matchMedia

  return {
    set(next) {
      surface = next
      notify(WIDE_QUERY)
    },
    setPrefersDark(next) {
      dark = next
      notify(DARK_QUERY)
    },
  }
}
