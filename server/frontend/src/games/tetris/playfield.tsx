import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type PointerEvent as ReactPointerEvent,
  type ReactNode,
} from 'react'

import { Button } from '@/components/ui/button'

import { SHAPES, TetrisGame, cellsOf, type Cell } from './game'
import { BOARD_WIDTH, HIDDEN_ROWS, VISIBLE_ROWS, type PieceKind } from './rules'

/**
 * The Tetris screen's moving part: a canvas board, hold, the next five, and the
 * controls (ticket 05). The rules live in `game.ts`; this only draws them and
 * turns touches and keys into moves.
 *
 * Touch: tap rotates, a sideways swipe moves a column per cell width, a slow
 * downward drag soft-drops a row per cell height, a quick downward flick hard
 * drops. Keys: arrows move and soft-drop, Up or X rotates, Z rotates back,
 * Space hard-drops, C or Shift holds.
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

const WELL = '#15171c'
const GRID = '#23262e'
const CELL = 30
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

function draw(canvas: HTMLCanvasElement | null, game: TetrisGame) {
  const ctx = canvas ? contextOf(canvas) : null
  if (!canvas || !ctx) return
  ctx.fillStyle = WELL
  ctx.fillRect(0, 0, canvas.width, canvas.height)
  ctx.strokeStyle = GRID
  ctx.lineWidth = 1
  for (let x = 1; x < BOARD_WIDTH; x++) {
    ctx.beginPath()
    ctx.moveTo(x * CELL + 0.5, 0)
    ctx.lineTo(x * CELL + 0.5, canvas.height)
    ctx.stroke()
  }
  const paint = (x: number, y: number, colour: string, alpha = 1) => {
    const row = y - HIDDEN_ROWS
    if (row < 0) return
    ctx.globalAlpha = alpha
    ctx.fillStyle = colour
    ctx.fillRect(x * CELL + 1, row * CELL + 1, CELL - 2, CELL - 2)
    ctx.globalAlpha = 1
  }
  game.board.forEach((line: Cell[], y) =>
    line.forEach((cell, x) => {
      if (cell) paint(x, y, PIECE_COLOURS[cell])
    }),
  )
  const active = game.active
  if (active) {
    const ghostY = game.ghostY()
    if (ghostY !== null && ghostY !== active.y) {
      for (const [x, y] of cellsOf({ ...active, y: ghostY })) paint(x, y, PIECE_COLOURS[active.kind], 0.25)
    }
    for (const [x, y] of cellsOf(active)) paint(x, y, PIECE_COLOURS[active.kind])
  }
}

/** A small picture of one piece, for hold and next. */
export function PieceIcon({ kind, dim = false }: { kind: PieceKind | null; dim?: boolean }) {
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
      style={{ opacity: dim ? 0.4 : 1 }}
    >
      {cells.map(([x, y]) => (
        <rect key={`${x},${y}`} x={(x - minX) * 10 + 0.5} y={(y - minY) * 10 + 0.5} width={9} height={9} fill={PIECE_COLOURS[kind]} />
      ))}
    </svg>
  )
}

export interface Playfield {
  stats: Stats
  /** The live numbers, readable from a timer without waiting for a render. */
  statsRef: { current: Stats }
  board: ReactNode
  holdButton: ReactNode
}

/**
 * One game for `seed`, ticking while `running`. A new seed is a new game.
 * `onOver` fires once, when the player tops out.
 */
export function usePlayfield(seed: number, running: boolean, onOver?: (stats: Stats) => void): Playfield {
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

  useEffect(() => {
    onOverRef.current = onOver
    runningRef.current = running
  })

  const publish = useCallback(() => {
    const game = gameRef.current
    if (!game) return
    draw(canvasRef.current, game)
    const next = statsOf(game)
    if (!sameStats(next, statsRef.current)) {
      const wasOver = statsRef.current.over
      statsRef.current = next
      setStats(next)
      if (next.over && !wasOver) onOverRef.current?.(next)
    }
  }, [])

  // A new seed is a new game.
  const seedRef = useRef(seed)
  useEffect(() => {
    if (seedRef.current === seed) return
    seedRef.current = seed
    gameRef.current = new TetrisGame(seed)
    statsRef.current = { ...statsOf(gameRef.current), score: -1 }
    publish()
  }, [seed, publish])

  useEffect(() => {
    let frame = 0
    let last: number | null = null
    const loop = (now: number) => {
      const game = gameRef.current
      if (game && runningRef.current && last !== null) game.tick(Math.min((now - last) / 1000, 0.1))
      last = now
      publish()
      frame = requestAnimationFrame(loop)
    }
    frame = requestAnimationFrame(loop)
    return () => cancelAnimationFrame(frame)
  }, [publish])

  const act = useCallback(
    (move: (game: TetrisGame) => unknown) => {
      const game = gameRef.current
      if (!game || !runningRef.current || game.over) return
      move(game)
      publish()
    },
    [publish],
  )

  // Keys, while the game runs.
  useEffect(() => {
    if (!running) return
    const onKey = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null
      if (target && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) return
      const moves: Record<string, (game: TetrisGame) => unknown> = {
        ArrowLeft: (g) => g.moveLeft(),
        ArrowRight: (g) => g.moveRight(),
        ArrowDown: (g) => g.softDrop(),
        ArrowUp: (g) => g.rotate(1),
        x: (g) => g.rotate(1),
        X: (g) => g.rotate(1),
        z: (g) => g.rotate(-1),
        Z: (g) => g.rotate(-1),
        ' ': (g) => g.hardDrop(),
        c: (g) => g.holdPiece(),
        C: (g) => g.holdPiece(),
        Shift: (g) => g.holdPiece(),
      }
      const move = moves[event.key]
      if (!move) return
      event.preventDefault()
      act(move)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [running, act])

  // Touch.
  const gesture = useRef<{ x: number; y: number; t: number; dx: number; dy: number; moved: boolean } | null>(null)
  const cellPx = () => (canvasRef.current?.getBoundingClientRect().width || BOARD_WIDTH * CELL) / BOARD_WIDTH

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
      act((game) => game.moveRight())
    }
    while (g.dx - dx >= size) {
      g.dx -= size
      g.moved = true
      act((game) => game.moveLeft())
    }
    // A slow drag down soft-drops; a fast one is left for the flick to decide.
    if (event.timeStamp - g.t > FLICK_MS) {
      while (dy - g.dy >= size) {
        g.dy += size
        g.moved = true
        act((game) => game.softDrop())
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
      act((game) => game.hardDrop())
      return
    }
    if (!g.moved && Math.abs(dx) < 10 && Math.abs(dy) < 10) act((game) => game.rotate(1))
  }

  const holdButton = (
    <Button
      type="button"
      variant="outline"
      onClick={() => act((game) => game.holdPiece())}
      disabled={!running || stats.holdUsed || stats.over}
    >
      Hold
    </Button>
  )

  const board = (
    <div
      className="relative w-full max-w-[min(100%,calc((100dvh-16rem)/2))] touch-none select-none"
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={() => (gesture.current = null)}
    >
      <canvas
        ref={(node) => {
          canvasRef.current = node
          if (node && gameRef.current) draw(node, gameRef.current)
        }}
        width={BOARD_WIDTH * CELL}
        height={VISIBLE_ROWS * CELL}
        className="block h-auto w-full rounded-control"
        role="img"
        aria-label={`Tetris board. Score ${stats.score.toLocaleString()}, ${stats.lines} lines, level ${stats.level}.`}
      />
    </div>
  )

  return { stats, statsRef, board, holdButton }
}

/** Hold and the next five, beside or above the board. */
export function Sidebar({ stats }: { stats: Stats }) {
  return (
    <div className="flex flex-col gap-3 text-[0.8125rem]">
      <div>
        <p className="text-muted-foreground">Hold</p>
        <PieceIcon kind={stats.hold} dim={stats.holdUsed} />
      </div>
      <div>
        <p className="text-muted-foreground">Next</p>
        <ul className="flex flex-col gap-1" aria-label="Next pieces">
          {stats.next.map((kind, index) => (
            <li key={`${index}-${kind}`}>
              <PieceIcon kind={kind} />
            </li>
          ))}
        </ul>
      </div>
    </div>
  )
}

/** Score, lines and level, as numbers a person reads at a glance. */
export function Numbers({ stats, label = 'You' }: { stats: Stats; label?: string }) {
  return (
    <dl className="grid grid-cols-3 gap-2 text-center" aria-label={`${label}: score, lines and level`}>
      <div>
        <dt className="text-[0.75rem] text-muted-foreground">Score</dt>
        <dd className="text-[1.25rem] font-medium tabular-nums">{stats.score.toLocaleString()}</dd>
      </div>
      <div>
        <dt className="text-[0.75rem] text-muted-foreground">Lines</dt>
        <dd className="text-[1.25rem] font-medium tabular-nums">{stats.lines}</dd>
      </div>
      <div>
        <dt className="text-[0.75rem] text-muted-foreground">Level</dt>
        <dd className="text-[1.25rem] font-medium tabular-nums">{stats.level}</dd>
      </div>
    </dl>
  )
}
