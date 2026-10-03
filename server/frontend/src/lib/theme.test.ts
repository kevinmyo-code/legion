import { afterEach, describe, expect, test, vi } from 'vitest'

import { stubSurface } from '@/test/surface'

import indexHtml from '../../index.html?raw'

import {
  THEME_KEY,
  applyTheme,
  getThemePref,
  nextThemePref,
  resolvesDark,
  setThemePref,
  watchSystemTheme,
} from './theme'
import { THEME_COLOR_DARK, THEME_COLOR_LIGHT } from './theme-colors'

afterEach(() => {
  vi.restoreAllMocks()
  document.head.querySelectorAll('meta[name="theme-color"]').forEach((meta) => meta.remove())
})

const isDark = () => document.documentElement.classList.contains('dark')

describe('theme preference', () => {
  test('system follows the device and keeps following it live', () => {
    const device = stubSurface('family', false)
    applyTheme('system')
    expect(isDark()).toBe(false)

    const stop = watchSystemTheme()
    device.setPrefersDark(true)
    expect(isDark()).toBe(true)
    device.setPrefersDark(false)
    expect(isDark()).toBe(false)
    stop()
    device.setPrefersDark(true)
    expect(isDark()).toBe(false)
  })

  test('a stored choice beats the device, in both directions', () => {
    stubSurface('family', true)
    window.localStorage.setItem(THEME_KEY, 'light')
    expect(getThemePref()).toBe('light')
    applyTheme()
    expect(isDark()).toBe(false)

    stubSurface('family', false)
    window.localStorage.setItem(THEME_KEY, 'dark')
    applyTheme()
    expect(isDark()).toBe(true)
  })

  test('a device change does not override an explicit choice', () => {
    const device = stubSurface('family', false)
    setThemePref('light')
    const stop = watchSystemTheme()
    device.setPrefersDark(true)
    expect(isDark()).toBe(false)
    stop()
  })

  test('unreadable storage means system, never an error', () => {
    stubSurface('family', true)
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError')
    })
    expect(getThemePref()).toBe('system')
    expect(() => applyTheme()).not.toThrow()
    expect(isDark()).toBe(true)
  })

  test('a stored value nobody wrote is ignored', () => {
    window.localStorage.setItem(THEME_KEY, 'sepia')
    expect(getThemePref()).toBe('system')
  })

  test('a choice still applies when it cannot be stored', () => {
    stubSurface('family', false)
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('full', 'QuotaExceededError')
    })
    expect(() => setThemePref('dark')).not.toThrow()
    expect(isDark()).toBe(true)
  })

  test('the toggle cycles system, light, dark and back', () => {
    expect(nextThemePref('system')).toBe('light')
    expect(nextThemePref('light')).toBe('dark')
    expect(nextThemePref('dark')).toBe('system')
  })

  test('an explicit choice retints both theme-color metas, system restores the pair', () => {
    document.head.innerHTML = `
      <meta name="theme-color" content="${THEME_COLOR_LIGHT}" data-scheme="light">
      <meta name="theme-color" content="${THEME_COLOR_DARK}" data-scheme="dark">`
    const metas = () =>
      Array.from(document.querySelectorAll<HTMLMetaElement>('meta[name="theme-color"]')).map(
        (meta) => meta.content,
      )

    stubSurface('family', false)
    applyTheme('dark')
    expect(metas()).toEqual([THEME_COLOR_DARK, THEME_COLOR_DARK])
    applyTheme('system')
    expect(metas()).toEqual([THEME_COLOR_LIGHT, THEME_COLOR_DARK])
  })

  test('resolvesDark answers without a matchMedia at all', () => {
    const saved = window.matchMedia
    // @ts-expect-error - simulating a browser with no matchMedia
    window.matchMedia = undefined
    expect(resolvesDark('system')).toBe(false)
    expect(resolvesDark('dark')).toBe(true)
    window.matchMedia = saved
  })
})

describe('the pre-paint script in index.html', () => {
  const html = indexHtml
  const script = /<script>([\s\S]*?)<\/script>/.exec(html)?.[1]

  // Runs the real inline script, so the copy that executes before any bundle
  // cannot quietly disagree with `theme.ts`.
  function runInline(stored: string | null, deviceDark: boolean): boolean {
    stubSurface('family', deviceDark)
    if (stored !== null) window.localStorage.setItem(THEME_KEY, stored)
    document.documentElement.classList.remove('dark')
    new Function(script!)()
    return isDark()
  }

  test('exists', () => {
    expect(script).toBeTruthy()
  })

  test.each([
    [null, false],
    [null, true],
    ['light', false],
    ['light', true],
    ['dark', false],
    ['dark', true],
    ['sepia', true],
  ] as const)('stored %s on a device where dark=%s agrees with theme.ts', (stored, deviceDark) => {
    window.localStorage.clear()
    const fromScript = runInline(stored, deviceDark)

    window.localStorage.clear()
    stubSurface('family', deviceDark)
    if (stored !== null) window.localStorage.setItem(THEME_KEY, stored)
    expect(fromScript).toBe(resolvesDark(getThemePref()))
  })

  test('unreadable storage in the script means the device setting', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError')
    })
    expect(runInline(null, true)).toBe(true)
    expect(runInline(null, false)).toBe(false)
  })

  test('the two theme-color metas carry the hex theme-colors.ts names', () => {
    expect(html).toContain(`content="${THEME_COLOR_LIGHT}" media="(prefers-color-scheme: light)"`)
    expect(html).toContain(`content="${THEME_COLOR_DARK}" media="(prefers-color-scheme: dark)"`)
  })
})
