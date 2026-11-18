import { afterEach, describe, expect, it, vi } from 'vitest'

import { Backstop, STUCK_MS } from './backstop'

afterEach(() => {
  vi.useRealTimers()
})

describe('Backstop', () => {
  it('a conversation stuck open with nothing going on is unstuck after STUCK_MS, once', () => {
    vi.useFakeTimers()
    let stuck = true
    const unstick = vi.fn(() => {
      stuck = false
    })
    const lapsed = vi.fn()
    const b = new Backstop(() => Date.now(), lapsed, () => {}, () => stuck, unstick)
    b.listening()
    // Chunks keep arriving, so the window itself never lapses.
    for (let ms = 0; ms < STUCK_MS - 1_500; ms += 500) {
      b.chunk()
      vi.advanceTimersByTime(500)
    }
    expect(unstick).not.toHaveBeenCalled()
    for (let ms = 0; ms < 3_000; ms += 500) {
      b.chunk()
      vi.advanceTimersByTime(500)
    }
    expect(unstick).toHaveBeenCalledTimes(1)
    expect(lapsed).not.toHaveBeenCalled()
    b.stop()
  })
})
