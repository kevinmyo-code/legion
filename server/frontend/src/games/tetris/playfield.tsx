import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type PointerEvent as ReactPointerEvent,
  type ReactNode,
} from 'react'

import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

import { GamePad } from './controls'
import { SHAPES, TetrisGame, cellsOf, type Cell } from './game'
import { GamepadRepeater, readGamepads, type PadAction } from './input'
import { BOARD_WIDTH, HIDDEN_ROWS, VISIBLE_ROWS, type PieceKind } from './rules'
import {
  THEMES,
  THEME_ORDER,
  loadTheme,
  nextTheme,
  saveTheme,
  type BoardTheme,
  type ThemeId,
} from './themes'

/**
 * The Tetris screen's moving part: a canvas board, hold, the next five, and the
 * controls (ticket 05). The rules live in `game.ts`; this only draws them and
 * turns touches and keys into moves.
 *
 * Touch: the on-screen gamepad (controls.tsx) is the main scheme; on the board
 * itself a tap rotates, a sideways swipe moves, a slow downward drag soft-drops
 * and a quick flick down hard-drops. Keys: arrows move and soft-drop, Up or X
 * rotates, Z rotates back, Space hard-drops, C or Shift holds, P or Escape pauses
 * (solo only). A physical gamepad works through the Gamepad API.
 */

/** The guideline colours: a piece is recognised by them across every Tetris. */
export const PIECE_COLOURS: Record<PieceKind, string> = {
  I: '#31c7ef',
  J: '#5a65ad',
  L: '#ef7921',
  O: '#f7d308',
  S: '#42b642',
  T: '#ad4d9c',
  Z: '#ef2029',
}

/** The walls and floor are drawn inside the canvas, so the lanes are always visible. */
const WALL = 8
const CELL = 30
const CANVAS_W = BOARD_WIDTH * CELL + 2 * WALL
const CANVAS_H = VISIBLE_ROWS * CELL + WALL
const FLICK_MS = 220
const FLICK_PX = 50

export interface Stats {
  score: number
  lines: number
  level: number
  hold: PieceKind | null
  holdUsed: boolean
  next: PieceKind[]
  over: boolean
}

function statsOf(game: TetrisGame): Stats {
  return {
    score: game.score,
    lines: game.lines,
    level: game.level,
    hold: game.hold,
    holdUsed: game.holdUsed,
    next: game.next,
    over: game.over,
  }
}

function sameStats(a: Stats, b: Stats): boolean {
  return (
    a.score === b.score &&
    a.lines === b.lines &&
    a.level === b.level &&
    a.hold === b.hold &&
    a.holdUsed === b.holdUsed &&
    a.over === b.over &&
    a.next.join('') === b.next.join('')
  )
}

/** One lookup per canvas: a browser without 2D canvas (jsdom in the tests) is
 * asked once, not sixty times a second. */
const contexts = new WeakMap<HTMLCanvasElement, CanvasRenderingContext2D | null>()

function contextOf(canvas: HTMLCanvasElement): CanvasRenderingContext2D | null {
  if (!contexts.has(canvas)) {
    let ctx: CanvasRenderingContext2D | null = null
    try {
      ctx = canvas.getContext('2d')
    } catch {
      ctx = null
    }
    contexts.set(canvas, ctx)
  }
  return contexts.get(canvas) ?? null
}

function draw(canvas: HTMLCanvasElement | null, game: TetrisGame, theme: BoardTheme) {
  const ctx = canvas ? contextOf(canvas) : null
  if (!canvas || !ctx) return
  const wellH = VISIBLE_ROWS * CELL
  // Page-facing backdrop is the wall colour; the well sits inside it.
  ctx.shadowBlur = 0
  ctx.fillStyle = theme.wall
  ctx.fillRect(0, 0, canvas.width, canvas.height)
  ctx.fillStyle = theme.well
  ctx.fillRect(WALL, 0, BOARD_WIDTH * CELL, wellH)
  ctx.strokeStyle = theme.grid
  ctx.lineWidth = 1
  for (let x = 1; x < BOARD_WIDTH; x++) {
    ctx.beginPath()
    ctx.moveTo(WALL + x * CELL + 0.5, 0)
    ctx.lineTo(WALL + x * CELL + 0.5, wellH)
    ctx.stroke()
  }
  for (let y = 1; y < VISIBLE_ROWS; y++) {
    ctx.beginPath()
    ctx.moveTo(WALL, y * CELL + 0.5)
    ctx.lineTo(WALL + BOARD_WIDTH * CELL, y * CELL + 0.5)
    ctx.stroke()
  }
  if (theme.glow > 0) {
    // A bright outlined wall with a soft glow.
    ctx.save()
    ctx.shadowColor = theme.wall
    ctx.shadowBlur = theme.glow
    ctx.strokeStyle = theme.wall
    ctx.lineWidth = 2
    ctx.strokeRect(WALL - 1, -2, BOARD_WIDTH * CELL + 2, wellH + 3)
    ctx.restore()
  }
  const paint = (x: number, y: number, kind: PieceKind, ghost = false) => {
    const row = y - HIDDEN_ROWS
    if (row < 0) return
    const px = WALL + x * CELL + 1
    const py = row * CELL + 1
    const size = CELL - 2
    if (ghost) {
      ctx.save()
      ctx.globalAlpha = 0.28
      ctx.fillStyle = theme.fill[kind]
      ctx.fillRect(px, py, size, size)
      ctx.globalAlpha = 1
      ctx.strokeStyle = theme.edge[kind]
      ctx.lineWidth = 2
      ctx.setLineDash([4, 3])
      ctx.strokeRect(px + 1, py + 1, size - 2, size - 2)
      ctx.restore()
      return
    }
    ctx.save()
    if (theme.glow > 0) {
      ctx.shadowColor = theme.fill[kind]
      ctx.shadowBlur = theme.glow
    }
    ctx.fillStyle = theme.fill[kind]
    ctx.fillRect(px, py, size, size)
    ctx.restore()
    ctx.strokeStyle = theme.edge[kind]
    ctx.lineWidth = 2
    ctx.strokeRect(px + 1, py + 1, size - 2, size - 2)
    if (theme.bevel) {
      // The pixel-ish Game Boy block: a light notch in the corner.
      ctx.fillStyle = theme.well
      ctx.fillRect(px + 5, py + 5, 6, 6)
    }
  }
  game.board.forEach((line: Cell[], y) =>
    line.forEach((cell, x) => {
      if (cell) paint(x, y, cell)
    }),
  )
  const active = game.active
  if (active) {
    const ghostY = game.ghostY()
    if (ghostY !== null && ghostY !== active.y) {
      for (const [x, y] of cellsOf({ ...active, y: ghostY })) paint(x, y, active.kind, true)
    }
    for (const [x, y] of cellsOf(active)) paint(x, y, active.kind)
  }
}

/** A small picture of one piece, for hold and next. */
export function PieceIcon({ kind, dim = false, theme }: { kind: PieceKind | null; dim?: boolean; theme: BoardTheme }) {
  if (kind === null) return <div className="h-6 w-12" aria-hidden="true" />
  const cells = SHAPES[kind][0]
  const xs = cells.map(([x]) => x)
  const ys = cells.map(([, y]) => y)
  const minX = Math.min(...xs)
  const minY = Math.min(...ys)
  return (
    <svg
      viewBox={`0 0 ${(Math.max(...xs) - minX + 1) * 10} ${(Math.max(...ys) - minY + 1) * 10}`}
      className="h-6 w-12"
      role="img"
      aria-label={kind}
      style={{ opacity: dim ? 0.4 : 1, backgroundColor: theme.well }}
    >
      {cells.map(([x, y]) => (
        <rect key={`${x},${y}`} x={(x - minX) * 10 + 0.5} y={(y - minY) * 10 + 0.5} width={9} height={9} fill={theme.fill[kind]} stroke={theme.edge[kind]} strokeWidth={1} />
      ))}
    </svg>
  )
}

export interface PlayfieldOptions {
  /** Solo may pause; a race may not, because the clock is shared. */
  canPause?: boolean
  /** Where Quit in the pause overlay goes. */
  onQuit?: () => void
  /** A race: the board leaves at least a third of the row for the opponent's column. */
  besideOpponent?: boolean
}

export const RACE_NO_PAUSE = "Races can't pause, the clock is shared."

export interface Playfield {
  stats: Stats
  /** The live numbers, readable from a timer without waiting for a render. */
  statsRef: { current: Stats }
  board: ReactNode
  /** The small Hold button, or null while the gamepad (which has Hold) is showing. */
  holdButton: ReactNode
  /** The three-theme picker, one row. */
  themePicker: ReactNode
  /** A Pause button in solo; in a race, the sentence saying why there is none. */
  pauseButton: ReactNode
  /** The on-screen gamepad, or null while it is collapsed. */
  controls: ReactNode
  /** Shows or hides the gamepad. */
  padToggle: ReactNode
  theme: BoardTheme
  paused: boolean
  pause: () => void
  resume: () => void
  setTheme: (id: ThemeId) => void
  /** The live game, for tests. */
  getGame: () => TetrisGame | null
}

/** The pad starts out on a touch screen or a narrow window; on a desk with a mouse it starts collapsed. */
function padStartsOpen(): boolean {
  try {
    if (typeof window.matchMedia !== 'function') return true
    return window.matchMedia('(pointer: coarse), (max-width: 767px)').matches || !window.matchMedia('(any-pointer: fine)').matches
  } catch {
    return true
  }
}

/**
 * One game for `seed`, ticking while `running`. A new seed is a new game.
 * `onOver` fires once, when the player tops out.
 */
export function usePlayfield(
  seed: number,
  running: boolean,
  onOver?: (stats: Stats) => void,
  options: PlayfieldOptions = {},
): Playfield {
  const canPause = options.canPause ?? false
  const gameRef = useRef<TetrisGame | null>(null)
  const canvasRef = useRef<HTMLCanvasElement | null>(null)
  const [stats, setStats] = useState<Stats>(() => {
    const game = new TetrisGame(seed)
    gameRef.current = game
    return statsOf(game)
  })
  const statsRef = useRef(stats)
  const onOverRef = useRef(onOver)
  const runningRef = useRef(running)
  const [paused, setPaused] = useState(false)
  const pausedRef = useRef(false)
  const [themeId, setThemeId] = useState<ThemeId>(loadTheme)
  const themeRef = useRef<BoardTheme>(THEMES[themeId])
  const [padOpen, setPadOpen] = useState(padStartsOpen)
  const quitRef = useRef(options.onQuit)

  useEffect(() => {
    onOverRef.current = onOver
    runningRef.current = running
    quitRef.current = options.onQuit
  })

  const publish = useCallback(() => {
    const game = gameRef.current
    if (!game) return
    draw(canvasRef.current, game, themeRef.current)
    const next = statsOf(game)
    if (!sameStats(next, statsRef.current)) {
      const wasOver = statsRef.current.over
      statsRef.current = next
      setStats(next)
      if (next.over && !wasOver) onOverRef.current?.(next)
    }
  }, [])

  const setTheme = useCallback(
    (id: ThemeId) => {
      themeRef.current = THEMES[id]
      setThemeId(id)
      saveTheme(id)
      publish()
    },
    [publish],
  )

  const setPausedBoth = useCallback((value: boolean) => {
    pausedRef.current = value
    setPaused(value)
  }, [])
  const pause = useCallback(() => {
    const game = gameRef.current
    if (!canPause || !game || game.over) return
    setPausedBoth(true)
  }, [canPause, setPausedBoth])
  const resume = useCallback(() => setPausedBoth(false), [setPausedBoth])

  // A new seed is a new game.
  const seedRef = useRef(seed)
  useEffect(() => {
    if (seedRef.current === seed) return
    seedRef.current = seed
    gameRef.current = new TetrisGame(seed)
    statsRef.current = { ...statsOf(gameRef.current), score: -1 }
    setPausedBoth(false)
    publish()
  }, [seed, publish, setPausedBoth])

  const act = useCallback(
    (move: (game: TetrisGame) => unknown) => {
      const game = gameRef.current
      if (!game || !runningRef.current || pausedRef.current || game.over) return
      move(game)
      publish()
    },
    [publish],
  )

  /** Every input, from keys, the pad or a gamepad, ends up here. */
  const perform = useCallback(
    (action: PadAction) => {
      switch (action) {
        case 'left':
          return act((g) => g.moveLeft())
        case 'right':
          return act((g) => g.moveRight())
        case 'down':
          return act((g) => g.softDrop())
        case 'up':
        case 'drop':
          return act((g) => g.hardDrop())
        case 'rotateCw':
          return act((g) => g.rotate(1))
        case 'rotateCcw':
          return act((g) => g.rotate(-1))
        case 'hold':
          return act((g) => g.holdPiece())
        case 'select':
          return setTheme(nextTheme(themeRef.current.id))
        case 'start':
          if (!canPause) return
          return pausedRef.current ? resume() : pause()
      }
    },
    [act, setTheme, canPause, pause, resume],
  )
  const performRef = useRef(perform)
  useEffect(() => {
    performRef.current = perform
  })

  useEffect(() => {
    let frame = 0
    let last: number | null = null
    const pads = new GamepadRepeater()
    const loop = (now: number) => {
      const game = gameRef.current
      if (game && runningRef.current && !pausedRef.current && last !== null) game.tick(Math.min((now - last) / 1000, 0.1))
      last = now
      try {
        const list = typeof navigator.getGamepads === 'function' ? navigator.getGamepads() : null
        pads.step(now, readGamepads(list), (action) => performRef.current(action))
      } catch {
        // A browser that refuses the Gamepad API still plays.
      }
      publish()
      frame = requestAnimationFrame(loop)
    }
    frame = requestAnimationFrame(loop)
    return () => cancelAnimationFrame(frame)
  }, [publish])

  // A hidden tab or an app switch pauses a solo game, so it is waiting when you are back.
  useEffect(() => {
    if (!canPause) return
    const onVisibility = () => {
      if (document.visibilityState === 'hidden') pause()
    }
    document.addEventListener('visibilitychange', onVisibility)
    return () => document.removeEventListener('visibilitychange', onVisibility)
  }, [canPause, pause])

  // Keys. The pause keys work while paused too, so P or Escape resumes.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null
      if (target && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) return
      if (event.key === 'Escape' || event.key === 'p' || event.key === 'P') {
        if (!canPause) return
        event.preventDefault()
        if (pausedRef.current) resume()
        else pause()
        return
      }
      if (!running) return
      const moves: Record<string, PadAction> = {
        ArrowLeft: 'left',
        ArrowRight: 'right',
        ArrowDown: 'down',
        ArrowUp: 'rotateCw',
        x: 'rotateCw',
        X: 'rotateCw',
        z: 'rotateCcw',
        Z: 'rotateCcw',
        ' ': 'up',
        c: 'hold',
        C: 'hold',
        Shift: 'hold',
      }
      const action = moves[event.key]
      if (!action) return
      event.preventDefault()
      perform(action)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [running, canPause, perform, pause, resume])

  // Touch on the board itself.
  const gesture = useRef<{ x: number; y: number; t: number; dx: number; dy: number; moved: boolean } | null>(null)
  const cellPx = () => {
    const width = canvasRef.current?.getBoundingClientRect().width || CANVAS_W
    return (width * ((BOARD_WIDTH * CELL) / CANVAS_W)) / BOARD_WIDTH
  }

  const onPointerDown = (event: ReactPointerEvent) => {
    gesture.current = { x: event.clientX, y: event.clientY, t: event.timeStamp, dx: 0, dy: 0, moved: false }
    ;(event.currentTarget as HTMLElement).setPointerCapture?.(event.pointerId)
  }
  const onPointerMove = (event: ReactPointerEvent) => {
    const g = gesture.current
    if (!g) return
    const size = cellPx()
    const dx = event.clientX - g.x
    const dy = event.clientY - g.y
    while (dx - g.dx >= size) {
      g.dx += size
      g.moved = true
      perform('right')
    }
    while (g.dx - dx >= size) {
      g.dx -= size
      g.moved = true
      perform('left')
    }
    // A slow drag down soft-drops; a fast one is left for the flick to decide.
    if (event.timeStamp - g.t > FLICK_MS) {
      while (dy - g.dy >= size) {
        g.dy += size
        g.moved = true
        perform('down')
      }
    }
  }
  const onPointerUp = (event: ReactPointerEvent) => {
    const g = gesture.current
    gesture.current = null
    if (!g) return
    const dy = event.clientY - g.y
    const dx = event.clientX - g.x
    const quick = event.timeStamp - g.t <= FLICK_MS
    if (quick && dy >= FLICK_PX && Math.abs(dy) > Math.abs(dx)) {
      perform('up')
      return
    }
    if (!g.moved && Math.abs(dx) < 10 && Math.abs(dy) < 10) perform('rotateCw')
  }

  const theme = THEMES[themeId]
  const holdDisabled = !running || paused || stats.holdUsed || stats.over

  const holdButton = padOpen ? null : (
    <Button type="button" variant="outline" onClick={() => perform('hold')} disabled={holdDisabled}>
      Hold
    </Button>
  )

  const board = (
    <div
      className={cn(
        'relative w-full shrink-0 touch-none select-none',
        options.besideOpponent
          ? 'max-w-[min(62%,calc((100dvh-28rem)/2.1))]'
          : 'max-w-[min(100%,calc((100dvh-26rem)/2.1))]',
      )}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={() => (gesture.current = null)}
      onContextMenu={(event) => event.preventDefault()}
    >
      <canvas
        ref={(node) => {
          canvasRef.current = node
          if (node && gameRef.current) draw(node, gameRef.current, themeRef.current)
        }}
        width={CANVAS_W}
        height={CANVAS_H}
        className="block h-auto w-full rounded-control"
        role="img"
        aria-label={`Tetris board. Score ${stats.score.toLocaleString()}, ${stats.lines} lines, level ${stats.level}.`}
      />
      {paused && (
        <div
          role="dialog"
          aria-label="Paused"
          className="absolute inset-0 flex flex-col items-center justify-center gap-2 rounded-control bg-background p-2 text-center"
          onPointerDown={(event) => event.stopPropagation()}
          onPointerUp={(event) => event.stopPropagation()}
        >
          <p className="text-[1.25rem] font-medium">Paused</p>
          <p className="text-[0.8125rem] text-muted-foreground">The board is hidden while the game waits.</p>
          <Button onClick={resume}>Resume</Button>
          <Button variant="outline" onClick={() => quitRef.current?.()}>
            Quit
          </Button>
        </div>
      )}
    </div>
  )

  const themePicker = (
    <div role="radiogroup" aria-label="Board theme" className="flex gap-1">
      {THEME_ORDER.map((id) => (
        <button
          key={id}
          type="button"
          role="radio"
          aria-checked={id === themeId}
          onClick={() => setTheme(id)}
          className={cn(
            'h-9 min-w-14 rounded-control px-2 text-[0.75rem]',
            id === themeId ? 'bg-primary text-primary-foreground' : 'bg-surface-2 text-foreground',
          )}
        >
          {THEMES[id].label}
        </button>
      ))}
    </div>
  )

  const pauseButton = canPause ? (
    <Button type="button" variant="outline" onClick={() => (paused ? resume() : pause())} disabled={stats.over}>
      {paused ? 'Resume' : 'Pause'}
    </Button>
  ) : (
    <p className="text-[0.8125rem] text-muted-foreground">{RACE_NO_PAUSE}</p>
  )

  const controls = padOpen ? (
    <GamePad press={perform} holdDisabled={holdDisabled} startDisabled={!canPause || stats.over} />
  ) : null

  const padToggle = (
    <Button type="button" variant="ghost" size="sm" onClick={() => setPadOpen(!padOpen)}>
      {padOpen ? 'Hide pad' : 'Show on-screen pad'}
    </Button>
  )

  return {
    stats,
    statsRef,
    board,
    holdButton,
    themePicker,
    pauseButton,
    controls,
    padToggle,
    theme,
    paused,
    pause,
    resume,
    setTheme,
    getGame: () => gameRef.current,
  }
}

/** Hold and the next five, beside or above the board. `compact` (a race, where
 * the opponent's board takes the column) puts Hold beside the next three. */
export function Sidebar({ stats, theme, compact = false }: { stats: Stats; theme: BoardTheme; compact?: boolean }) {
  const next = compact ? stats.next.slice(0, 3) : stats.next
  return (
    <div className={cn('text-[0.8125rem]', compact ? 'grid grid-cols-2 gap-2' : 'flex flex-col gap-3')}>
      <div>
        <p className="text-muted-foreground">Hold</p>
        <PieceIcon kind={stats.hold} dim={stats.holdUsed} theme={theme} />
      </div>
      <div>
        <p className="text-muted-foreground">Next</p>
        <ul className="flex flex-col gap-1" aria-label="Next pieces">
          {next.map((kind, index) => (
            <li key={`${index}-${kind}`}>
              <PieceIcon kind={kind} theme={theme} />
            </li>
          ))}
        </ul>
      </div>
    </div>
  )
}

/** Score, lines and level, as numbers a person reads at a glance. */
export function Numbers({ stats, label = 'You', stacked = false }: { stats: Stats; label?: string; stacked?: boolean }) {
  const rows: [string, string][] = [
    ['Score', stats.score.toLocaleString()],
    ['Lines', String(stats.lines)],
    ['Level', String(stats.level)],
  ]
  return (
    <dl
      className={cn('grid gap-2', stacked ? 'grid-cols-1 gap-1' : 'grid-cols-3 text-center')}
      aria-label={`${label}: score, lines and level`}
    >
      {rows.map(([name, value]) => (
        <div key={name} className={stacked ? 'flex items-baseline justify-between gap-2' : undefined}>
          <dt className="text-[0.75rem] text-muted-foreground">{name}</dt>
          <dd className={cn('font-medium tabular-nums', stacked ? 'text-[1rem]' : 'text-[1.25rem]')}>{value}</dd>
        </div>
      ))}
    </dl>
  )
}
