import { act, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import { ENTER_WAIT_LINE, EnterWait, enterProgress } from './enter-wait'

/**
 * Entering a visit takes about 15 s (a seeded copy of the demo household per visitor): the wait
 * says what is happening and how long, and its bar fills by the clock, easing near the end and
 * never full before the answer comes.
 */

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

const fill = () => Number(/scaleX\(([\d.]+)\)/.exec(document.querySelector<HTMLElement>('[data-stage="enter-bar"]')!.style.transform)![1])

describe('the wait while a visit is entered', () => {
  test('the bar eases: most of the way by 16 s, slower near the end, never full', () => {
    expect(enterProgress(0)).toBe(0)
    expect(enterProgress(16_000)).toBeGreaterThan(0.8)
    expect(enterProgress(16_000)).toBeLessThan(0.95)
    // Each later second adds less than the one before.
    expect(enterProgress(15_000) - enterProgress(14_000)).toBeLessThan(enterProgress(2_000) - enterProgress(1_000))
    expect(enterProgress(10 * 60_000)).toBeLessThanOrEqual(0.95)
  })

  test('says what is happening and how long, and the bar grows with the clock', () => {
    vi.useFakeTimers()
    render(<EnterWait />)
    expect(screen.getByRole('status')).toHaveTextContent(ENTER_WAIT_LINE)
    expect(ENTER_WAIT_LINE).toMatch(/about 15 seconds/)
    expect(fill()).toBe(0)
    act(() => vi.advanceTimersByTime(8_000))
    const early = fill()
    expect(early).toBeGreaterThan(0.5)
    act(() => vi.advanceTimersByTime(60_000))
    expect(fill()).toBeGreaterThan(early)
    expect(fill()).toBeLessThan(1)
    // The bar is decoration: no figure for a screen reader to read out.
    expect(screen.queryByRole('progressbar')).toBeNull()
  })

  test('with reduced motion the bar steps once a second with no glide', () => {
    vi.useFakeTimers()
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: query === '(prefers-reduced-motion: reduce)',
      media: query,
      addEventListener() {},
      removeEventListener() {},
    }))
    render(<EnterWait />)
    const bar = document.querySelector<HTMLElement>('[data-stage="enter-bar"]')!
    expect(bar.className).not.toMatch(/transition/)
    act(() => vi.advanceTimersByTime(900))
    expect(fill()).toBe(0)
    act(() => vi.advanceTimersByTime(200))
    expect(fill()).toBeGreaterThan(0)
  })
})
