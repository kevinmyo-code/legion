/**
 * Pad input shared by the on-screen buttons, a physical gamepad and the keys.
 * DAS is the wait before a held direction starts repeating, ARR the gap between
 * repeats (milliseconds), the same numbers as the phone.
 */
export type PadAction =
  | 'left'
  | 'right'
  | 'down'
  | 'up'
  | 'drop'
  | 'rotateCw'
  | 'rotateCcw'
  | 'hold'
  | 'start'
  | 'select'

export const DAS_MS = 170
export const ARR_MS = 50

/** The actions that repeat while held. Up and Drop (both hard drop) do not. */
export const REPEATING: ReadonlySet<PadAction> = new Set<PadAction>(['left', 'right', 'down'])

export interface Repeater {
  start: () => void
  stop: () => void
}

/** Fire now, then after DAS, then every ARR until stopped. Timer-based so tests can fake time. */
export function createRepeater(fire: () => void, repeat: boolean, das = DAS_MS, arr = ARR_MS): Repeater {
  let wait: ReturnType<typeof setTimeout> | null = null
  let every: ReturnType<typeof setInterval> | null = null
  const stop = () => {
    if (wait !== null) clearTimeout(wait)
    if (every !== null) clearInterval(every)
    wait = null
    every = null
  }
  return {
    start: () => {
      stop()
      fire()
      if (!repeat) return
      wait = setTimeout(() => {
        wait = null
        fire()
        every = setInterval(fire, arr)
      }, das)
    },
    stop,
  }
}

/** Standard-mapping gamepad buttons. */
export const GAMEPAD_BUTTONS: Record<number, PadAction> = {
  0: 'rotateCw',
  1: 'rotateCcw',
  2: 'rotateCcw',
  3: 'rotateCw',
  4: 'hold',
  5: 'hold',
  8: 'select',
  9: 'start',
  12: 'up',
  13: 'down',
  14: 'left',
  15: 'right',
}

/** Which actions are held right now across every connected pad. */
export function readGamepads(pads: ArrayLike<Gamepad | null> | null | undefined): Set<PadAction> {
  const held = new Set<PadAction>()
  if (!pads) return held
  for (let i = 0; i < pads.length; i++) {
    const pad = pads[i]
    if (!pad || !pad.connected) continue
    pad.buttons.forEach((button, index) => {
      const action = GAMEPAD_BUTTONS[index]
      if (action && button.pressed) held.add(action)
    })
  }
  return held
}

/** Edge and auto-repeat detection for polled buttons. One per mounted screen. */
export class GamepadRepeater {
  private readonly since = new Map<PadAction, { down: number; last: number }>()

  private readonly das: number
  private readonly arr: number

  constructor(das = DAS_MS, arr = ARR_MS) {
    this.das = das
    this.arr = arr
  }

  step(now: number, held: Set<PadAction>, fire: (action: PadAction) => void) {
    for (const action of [...this.since.keys()]) if (!held.has(action)) this.since.delete(action)
    for (const action of held) {
      const state = this.since.get(action)
      if (!state) {
        this.since.set(action, { down: now, last: now })
        fire(action)
      } else if (REPEATING.has(action) && now - state.down >= this.das && now - state.last >= this.arr) {
        state.last = now
        fire(action)
      }
    }
  }
}
