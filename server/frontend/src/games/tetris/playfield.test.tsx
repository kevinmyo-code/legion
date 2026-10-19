import { act, fireEvent, render, renderHook, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

import { GamePad } from './controls'
import { ARR_MS, DAS_MS, GamepadRepeater, createRepeater, type PadAction } from './input'
import { RACE_NO_PAUSE, usePlayfield } from './playfield'
import { DEFAULT_THEME, THEME_KEY, loadTheme, saveTheme } from './themes'

beforeEach(() => {
  vi.useFakeTimers()
  window.localStorage.clear()
})
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

const solo = () => renderHook(() => usePlayfield(1, true, undefined, { canPause: true }))

describe('pause', () => {
  test('solo: pausing stops gravity, resuming restarts it', () => {
    const { result } = solo()
    act(() => void vi.advanceTimersByTime(1500))
    const y0 = result.current.getGame()!.active!.y
    expect(y0).toBeGreaterThan(1)

    act(() => result.current.pause())
    expect(result.current.paused).toBe(true)
    act(() => void vi.advanceTimersByTime(10_000))
    expect(result.current.getGame()!.active!.y).toBe(y0)

    act(() => result.current.resume())
    act(() => void vi.advanceTimersByTime(1500))
    expect(result.current.getGame()!.active!.y).toBeGreaterThan(y0)
  })

  test('a grounded piece does not lock while paused', () => {
    const { result } = solo()
    const game = result.current.getGame()!
    game.active = { ...game.active!, y: game.ghostY()! }
    const kind = game.active.kind
    act(() => result.current.pause())
    act(() => void vi.advanceTimersByTime(5000))
    expect(game.active?.kind).toBe(kind)
    expect(game.active?.y).toBe(game.ghostY())
  })

  test('input does nothing while paused', () => {
    const { result } = solo()
    const x = result.current.getGame()!.active!.x
    act(() => result.current.pause())
    fireEvent.keyDown(window, { key: 'ArrowLeft' })
    expect(result.current.getGame()!.active!.x).toBe(x)
  })

  test('Escape and P toggle pause', () => {
    const { result } = solo()
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(result.current.paused).toBe(true)
    fireEvent.keyDown(window, { key: 'p' })
    expect(result.current.paused).toBe(false)
  })

  test('a hidden tab pauses a solo game', () => {
    const { result } = solo()
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
    act(() => void document.dispatchEvent(new Event('visibilitychange')))
    expect(result.current.paused).toBe(true)
  })

  test('a race refuses to pause, says why, and keeps falling', () => {
    const { result } = renderHook(() => usePlayfield(1, true))
    act(() => result.current.pause())
    fireEvent.keyDown(window, { key: 'Escape' })
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
    act(() => void document.dispatchEvent(new Event('visibilitychange')))
    expect(result.current.paused).toBe(false)
    render(<>{result.current.pauseButton}</>)
    expect(screen.getByText(RACE_NO_PAUSE)).toBeInTheDocument()
    const y0 = result.current.getGame()!.active!.y
    act(() => void vi.advanceTimersByTime(1500))
    expect(result.current.getGame()!.active!.y).toBeGreaterThan(y0)
  })
})

describe('auto-repeat', () => {
  test('fires at once, again after DAS, then every ARR, and stops on release', () => {
    const fire = vi.fn()
    const r = createRepeater(fire, true)
    r.start()
    expect(fire).toHaveBeenCalledTimes(1)
    vi.advanceTimersByTime(DAS_MS - 1)
    expect(fire).toHaveBeenCalledTimes(1)
    vi.advanceTimersByTime(1)
    expect(fire).toHaveBeenCalledTimes(2)
    vi.advanceTimersByTime(ARR_MS * 3)
    expect(fire).toHaveBeenCalledTimes(5)
    r.stop()
    vi.advanceTimersByTime(1000)
    expect(fire).toHaveBeenCalledTimes(5)
  })

  test('a non-repeating button fires once however long it is held', () => {
    const fire = vi.fn()
    createRepeater(fire, false).start()
    vi.advanceTimersByTime(2000)
    expect(fire).toHaveBeenCalledTimes(1)
  })

  test('the on-screen Left button repeats while pressed and stops on pointer up', () => {
    const press = vi.fn()
    render(<GamePad press={press} holdDisabled={false} startDisabled={false} />)
    const left = screen.getByRole('button', { name: 'Move left' })
    fireEvent.pointerDown(left)
    expect(press).toHaveBeenCalledTimes(1)
    vi.advanceTimersByTime(DAS_MS + ARR_MS)
    expect(press).toHaveBeenCalledTimes(3)
    fireEvent.pointerUp(left)
    vi.advanceTimersByTime(1000)
    expect(press).toHaveBeenCalledTimes(3)
    expect(press).toHaveBeenCalledWith('left')
  })

  test('hard drop and A do not repeat', () => {
    const press = vi.fn()
    render(<GamePad press={press} holdDisabled={false} startDisabled={false} />)
    fireEvent.pointerDown(screen.getByRole('button', { name: 'Hard drop' }))
    fireEvent.pointerDown(screen.getByRole('button', { name: 'Rotate clockwise' }))
    vi.advanceTimersByTime(1000)
    expect(press).toHaveBeenCalledTimes(2)
  })

  test('the big Drop button sits with A and B and fires one drop per press', () => {
    const press = vi.fn()
    render(<GamePad press={press} holdDisabled={false} startDisabled={false} />)
    const drop = screen.getByRole('button', { name: 'Drop' })
    expect(drop).toHaveTextContent('Drop')
    expect(drop.parentElement).toBe(screen.getByRole('button', { name: 'Rotate clockwise' }).parentElement)
    fireEvent.pointerDown(drop)
    vi.advanceTimersByTime(1000)
    expect(press.mock.calls).toEqual([['drop']])
  })

  test('Drop hard-drops the falling piece in a game', () => {
    const { result } = solo()
    const game = result.current.getGame()!
    const before = game.active!
    const floor = game.ghostY()!
    render(<>{result.current.controls}</>)
    act(() => void fireEvent.pointerDown(screen.getByRole('button', { name: 'Drop' })))
    // The piece landed and locked where its ghost was, and the next one spawned.
    expect(game.active).not.toBe(before)
    expect(game.board.some((row, y) => y >= floor && row.some((cell) => cell === before.kind))).toBe(true)
  })

  test('a physical gamepad edge-fires once and repeats only directions', () => {
    const fire = vi.fn()
    const pads = new GamepadRepeater()
    const held = (): Set<PadAction> => new Set<PadAction>(['left', 'rotateCw'])
    pads.step(0, held(), fire)
    pads.step(100, held(), fire)
    expect(fire).toHaveBeenCalledTimes(2)
    pads.step(DAS_MS, held(), fire)
    pads.step(DAS_MS + ARR_MS, held(), fire)
    expect(fire.mock.calls.filter(([a]) => a === 'left')).toHaveLength(3)
    expect(fire.mock.calls.filter(([a]) => a === 'rotateCw')).toHaveLength(1)
  })
})

describe('themes', () => {
  test('default when nothing is stored', () => {
    expect(loadTheme()).toBe(DEFAULT_THEME)
  })

  test('the choice is remembered', () => {
    expect(saveTheme('neon')).toBe(true)
    expect(window.localStorage.getItem(THEME_KEY)).toBe('neon')
    expect(loadTheme()).toBe('neon')
  })

  test('an unknown stored value falls back to the default', () => {
    window.localStorage.setItem(THEME_KEY, 'green')
    expect(loadTheme()).toBe(DEFAULT_THEME)
  })

  test('storage that throws falls back and the game still plays', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    expect(loadTheme()).toBe(DEFAULT_THEME)
    expect(saveTheme('classic')).toBe(false)
    const { result } = solo()
    act(() => result.current.setTheme('classic'))
    expect(result.current.theme.id).toBe('classic')
  })

  test('Theme on the pad cycles the theme and remembers it', () => {
    const { result } = solo()
    expect(result.current.theme.id).toBe('soft')
    render(<>{result.current.controls}</>)
    fireEvent.pointerDown(screen.getByRole('button', { name: 'Change theme' }))
    expect(window.localStorage.getItem(THEME_KEY)).toBe('classic')
  })
})
