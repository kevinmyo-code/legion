/**
 * Tetris's numbers, exactly as `.scratch/retro-games/issues/04-tetris-rules-and-race.md`
 * writes them (ADR 0060). The phone implements the same rules in Kotlin and both
 * are held to `shared/tetris/fixtures.json`, so a drift here fails a build rather
 * than deciding a race.
 *
 * Pure: no DOM, no clock, no React. `game.ts` is the board that uses these.
 */

export type PieceKind = 'I' | 'J' | 'L' | 'O' | 'S' | 'T' | 'Z'

/** The bag's starting order, before each shuffle. */
export const BAG_ORDER: readonly PieceKind[] = ['I', 'J', 'L', 'O', 'S', 'T', 'Z']

export const BOARD_WIDTH = 10
export const VISIBLE_ROWS = 20
/** Spawn rows above the visible board. */
export const HIDDEN_ROWS = 2
export const BOARD_ROWS = VISIBLE_ROWS + HIDDEN_ROWS

/** A race lasts this long from the engine's `start_at`. */
export const RACE_SECONDS = 180

/**
 * mulberry32, seeded with a uint32. 32-bit integer arithmetic throughout, as the
 * ticket writes it; returns a float in [0, 1).
 */
export function mulberry32(seed: number): () => number {
  let a = seed | 0
  return () => {
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

/** One shuffled bag: Fisher-Yates from i = 6 down to 1 over `BAG_ORDER`. */
export function shuffledBag(next: () => number): PieceKind[] {
  const bag = [...BAG_ORDER]
  for (let i = bag.length - 1; i >= 1; i--) {
    const j = Math.floor(next() * (i + 1))
    const swap = bag[i]
    bag[i] = bag[j]
    bag[j] = swap
  }
  return bag
}

/** An endless piece stream for one seed: bags concatenated from one RNG. */
export class PieceStream {
  private readonly next: () => number
  private queue: PieceKind[] = []

  constructor(seed: number) {
    this.next = mulberry32(seed)
  }

  take(): PieceKind {
    if (this.queue.length === 0) this.queue = shuffledBag(this.next)
    return this.queue.shift() as PieceKind
  }

  /** The first `count` pieces of a fresh stream for `seed`. */
  static first(seed: number, count: number): PieceKind[] {
    const stream = new PieceStream(seed)
    return Array.from({ length: count }, () => stream.take())
  }
}

/** Points for clearing 0 to 4 lines at once, before the level multiplier. */
export const LINE_POINTS: readonly number[] = [0, 100, 300, 500, 800]
export const SOFT_DROP_POINTS = 1
export const HARD_DROP_POINTS = 2

/** What one piece earns: the clear at the level BEFORE it, plus its drop cells. */
export function scoreFor({
  level,
  linesCleared,
  softDropCells,
  hardDropCells,
}: {
  level: number
  linesCleared: number
  softDropCells: number
  hardDropCells: number
}): number {
  return (
    (LINE_POINTS[linesCleared] ?? 0) * level +
    softDropCells * SOFT_DROP_POINTS +
    hardDropCells * HARD_DROP_POINTS
  )
}

export function levelFor(totalLines: number): number {
  return 1 + Math.floor(totalLines / 10)
}

/** Seconds per row of gravity at `level`. */
export function gravitySeconds(level: number): number {
  return Math.pow(0.8 - (level - 1) * 0.007, level - 1)
}
