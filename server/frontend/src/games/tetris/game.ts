/**
 * The board: SRS rotation with the standard wall kicks, hold once per piece, a
 * ghost, a five-piece preview, gravity by level and a lock delay (ticket 04).
 *
 * Deterministic given its seed and the inputs it is handed: no clock of its own.
 * The screen calls `tick(seconds)` from its animation frame and the input methods
 * from touch and keys, so a test drives it exactly as a player would.
 *
 * Coordinates: `x` 0..9 left to right, `y` 0..21 top to bottom, rows 0 and 1
 * the hidden spawn rows. The SRS kick tables are written y-up, as published, and
 * flipped once where they are read.
 */

import {
  BOARD_ROWS,
  BOARD_WIDTH,
  HIDDEN_ROWS,
  PieceStream,
  gravitySeconds,
  levelFor,
  scoreFor,
  type PieceKind,
} from './rules'

export type Cell = PieceKind | null
export type Rotation = 0 | 1 | 2 | 3

export const PREVIEW = 5
/** How long a grounded piece waits before it locks, and how many moves reset that. */
export const LOCK_DELAY_S = 0.5
export const LOCK_RESETS = 15

type Point = readonly [number, number]

/** Rotation 0 of each piece inside its box, y down. Other rotations are derived. */
const SPAWN_SHAPES: Record<PieceKind, { size: number; cells: Point[] }> = {
  I: { size: 4, cells: [[0, 1], [1, 1], [2, 1], [3, 1]] },
  J: { size: 3, cells: [[0, 0], [0, 1], [1, 1], [2, 1]] },
  L: { size: 3, cells: [[2, 0], [0, 1], [1, 1], [2, 1]] },
  O: { size: 2, cells: [[0, 0], [1, 0], [0, 1], [1, 1]] },
  S: { size: 3, cells: [[1, 0], [2, 0], [0, 1], [1, 1]] },
  T: { size: 3, cells: [[1, 0], [0, 1], [1, 1], [2, 1]] },
  Z: { size: 3, cells: [[0, 0], [1, 0], [1, 1], [2, 1]] },
}

function rotateCw(cells: Point[], size: number): Point[] {
  return cells.map(([x, y]) => [size - 1 - y, x] as const)
}

/** `SHAPES[kind][rotation]`: the four cells, y down, inside the piece's box. */
export const SHAPES: Record<PieceKind, Point[][]> = Object.fromEntries(
  (Object.keys(SPAWN_SHAPES) as PieceKind[]).map((kind) => {
    const { size, cells } = SPAWN_SHAPES[kind]
    const states: Point[][] = [cells]
    for (let r = 1; r < 4; r++) states.push(rotateCw(states[r - 1], size))
    return [kind, states]
  }),
) as Record<PieceKind, Point[][]>

/** SRS kicks for J, L, S, T and Z, keyed `from>to`, y UP as published. */
const KICKS_JLSTZ: Record<string, Point[]> = {
  '0>1': [[0, 0], [-1, 0], [-1, 1], [0, -2], [-1, -2]],
  '1>0': [[0, 0], [1, 0], [1, -1], [0, 2], [1, 2]],
  '1>2': [[0, 0], [1, 0], [1, -1], [0, 2], [1, 2]],
  '2>1': [[0, 0], [-1, 0], [-1, 1], [0, -2], [-1, -2]],
  '2>3': [[0, 0], [1, 0], [1, 1], [0, -2], [1, -2]],
  '3>2': [[0, 0], [-1, 0], [-1, -1], [0, 2], [-1, 2]],
  '3>0': [[0, 0], [-1, 0], [-1, -1], [0, 2], [-1, 2]],
  '0>3': [[0, 0], [1, 0], [1, 1], [0, -2], [1, -2]],
}

/** SRS kicks for I, y UP as published. */
const KICKS_I: Record<string, Point[]> = {
  '0>1': [[0, 0], [-2, 0], [1, 0], [-2, -1], [1, 2]],
  '1>0': [[0, 0], [2, 0], [-1, 0], [2, 1], [-1, -2]],
  '1>2': [[0, 0], [-1, 0], [2, 0], [-1, 2], [2, -1]],
  '2>1': [[0, 0], [1, 0], [-2, 0], [1, -2], [-2, 1]],
  '2>3': [[0, 0], [2, 0], [-1, 0], [2, 1], [-1, -2]],
  '3>2': [[0, 0], [-2, 0], [1, 0], [-2, -1], [1, 2]],
  '3>0': [[0, 0], [1, 0], [-2, 0], [1, -2], [-2, 1]],
  '0>3': [[0, 0], [-1, 0], [2, 0], [-1, 2], [2, -1]],
}

export function kicksFor(kind: PieceKind, from: Rotation, to: Rotation): Point[] {
  if (kind === 'O') return [[0, 0]]
  const table = kind === 'I' ? KICKS_I : KICKS_JLSTZ
  return table[`${from}>${to}`]
}

export interface ActivePiece {
  kind: PieceKind
  rotation: Rotation
  /** The box's top-left on the board. */
  x: number
  y: number
}

/** Where a piece's box starts: centred, its top row in the hidden rows. */
function spawnFor(kind: PieceKind): ActivePiece {
  if (kind === 'O') return { kind, rotation: 0, x: 4, y: 0 }
  if (kind === 'I') return { kind, rotation: 0, x: 3, y: 0 }
  return { kind, rotation: 0, x: 3, y: 0 }
}

export function cellsOf(piece: ActivePiece): Point[] {
  return SHAPES[piece.kind][piece.rotation].map(([cx, cy]) => [piece.x + cx, piece.y + cy] as const)
}

export interface GameSnapshot {
  board: Cell[][]
  active: ActivePiece | null
  ghostY: number | null
  hold: PieceKind | null
  holdUsed: boolean
  next: PieceKind[]
  score: number
  lines: number
  level: number
  toppedOut: boolean
}

export class TetrisGame {
  readonly board: Cell[][]
  active: ActivePiece | null = null
  hold: PieceKind | null = null
  holdUsed = false
  score = 0
  lines = 0
  level = 1
  toppedOut = false

  private readonly stream: PieceStream
  private readonly queue: PieceKind[] = []
  private fallClock = 0
  private lockClock = 0
  private lockResets = 0
  private softDropCells = 0

  constructor(seed: number) {
    this.board = Array.from({ length: BOARD_ROWS }, () => Array<Cell>(BOARD_WIDTH).fill(null))
    this.stream = new PieceStream(seed >>> 0)
    this.refill()
    this.spawn(this.queue.shift() as PieceKind)
  }

  get next(): PieceKind[] {
    return this.queue.slice(0, PREVIEW)
  }

  private refill() {
    while (this.queue.length < PREVIEW + 1) this.queue.push(this.stream.take())
  }

  private fits(piece: ActivePiece): boolean {
    return cellsOf(piece).every(
      ([x, y]) => x >= 0 && x < BOARD_WIDTH && y >= 0 && y < BOARD_ROWS && this.board[y][x] === null,
    )
  }

  private spawn(kind: PieceKind) {
    const piece = spawnFor(kind)
    this.fallClock = 0
    this.lockClock = 0
    this.lockResets = 0
    this.softDropCells = 0
    if (!this.fits(piece)) {
      // Block out: the next piece has nowhere to appear.
      this.active = null
      this.toppedOut = true
      return
    }
    this.active = piece
    // Guideline: a piece drops one row at once if it can, so it is seen.
    const lower = { ...piece, y: piece.y + 1 }
    if (this.fits(lower)) this.active = lower
  }

  private grounded(): boolean {
    if (!this.active) return false
    return !this.fits({ ...this.active, y: this.active.y + 1 })
  }

  /** A successful move or rotation on the ground buys more time, up to a limit. */
  private movedOnGround() {
    if (this.grounded() && this.lockResets < LOCK_RESETS) {
      this.lockClock = 0
      this.lockResets += 1
    }
  }

  private tryPlace(next: ActivePiece): boolean {
    if (!this.fits(next)) return false
    this.active = next
    this.movedOnGround()
    return true
  }

  get over(): boolean {
    return this.toppedOut
  }

  moveLeft(): boolean {
    if (!this.active || this.over) return false
    return this.tryPlace({ ...this.active, x: this.active.x - 1 })
  }

  moveRight(): boolean {
    if (!this.active || this.over) return false
    return this.tryPlace({ ...this.active, x: this.active.x + 1 })
  }

  rotate(direction: 1 | -1 = 1): boolean {
    if (!this.active || this.over) return false
    const from = this.active.rotation
    const to = (((from + direction) % 4) + 4) % 4 as Rotation
    for (const [kx, ky] of kicksFor(this.active.kind, from, to)) {
      // Published kicks are y up; the board is y down.
      const candidate = { ...this.active, rotation: to, x: this.active.x + kx, y: this.active.y - ky }
      if (this.tryPlace(candidate)) return true
    }
    return false
  }

  /** One row down by the player's hand: 1 point if it moved. */
  softDrop(): boolean {
    if (!this.active || this.over) return false
    const lower = { ...this.active, y: this.active.y + 1 }
    if (!this.fits(lower)) return false
    this.active = lower
    this.fallClock = 0
    this.softDropCells += 1
    return true
  }

  /** Straight down and lock: 2 points a row. Returns the rows dropped. */
  hardDrop(): number {
    if (!this.active || this.over) return 0
    const target = this.ghostY() ?? this.active.y
    const cells = target - this.active.y
    this.active = { ...this.active, y: target }
    this.lock(cells)
    return cells
  }

  /** Swap the falling piece into hold, once per piece. */
  holdPiece(): boolean {
    if (!this.active || this.over || this.holdUsed) return false
    const current = this.active.kind
    if (this.hold === null) {
      this.hold = current
      this.spawnNext()
    } else {
      const held = this.hold
      this.hold = current
      this.spawn(held)
    }
    this.holdUsed = true
    return true
  }

  /** The row the falling piece would land on. */
  ghostY(): number | null {
    if (!this.active) return null
    let y = this.active.y
    while (this.fits({ ...this.active, y: y + 1 })) y += 1
    return y
  }

  /** Advance gravity and the lock delay by `seconds`. */
  tick(seconds: number) {
    if (!this.active || this.over || seconds <= 0) return
    if (this.grounded()) {
      this.lockClock += seconds
      if (this.lockClock >= LOCK_DELAY_S) this.lock(0)
      return
    }
    this.fallClock += seconds
    const perRow = gravitySeconds(this.level)
    while (this.fallClock >= perRow && this.active && !this.grounded()) {
      this.fallClock -= perRow
      this.active = { ...this.active, y: this.active.y + 1 }
    }
    if (this.grounded()) this.fallClock = 0
  }

  private spawnNext() {
    const kind = this.queue.shift() as PieceKind
    this.refill()
    this.spawn(kind)
  }

  private lock(hardDropCells: number) {
    if (!this.active) return
    const placed = cellsOf(this.active)
    for (const [x, y] of placed) this.board[y][x] = this.active.kind
    const cleared = this.clearLines()
    this.score += scoreFor({
      level: this.level,
      linesCleared: cleared,
      softDropCells: this.softDropCells,
      hardDropCells,
    })
    this.lines += cleared
    this.level = levelFor(this.lines)
    this.active = null
    this.holdUsed = false
    // Lock out: the whole piece came to rest above the visible board.
    if (cleared === 0 && placed.every(([, y]) => y < HIDDEN_ROWS)) {
      this.toppedOut = true
      return
    }
    this.spawnNext()
  }

  private clearLines(): number {
    let cleared = 0
    for (let y = BOARD_ROWS - 1; y >= 0; y--) {
      if (this.board[y].every((cell) => cell !== null)) {
        this.board.splice(y, 1)
        this.board.unshift(Array<Cell>(BOARD_WIDTH).fill(null))
        cleared += 1
        y += 1
      }
    }
    return cleared
  }

  snapshot(): GameSnapshot {
    return {
      board: this.board.map((row) => [...row]),
      active: this.active ? { ...this.active } : null,
      ghostY: this.ghostY(),
      hold: this.hold,
      holdUsed: this.holdUsed,
      next: this.next,
      score: this.score,
      lines: this.lines,
      level: this.level,
      toppedOut: this.toppedOut,
    }
  }
}
