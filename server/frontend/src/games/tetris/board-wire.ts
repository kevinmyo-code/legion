import { cellsOf, type Cell, type TetrisGame } from './game'
import { BOARD_ROWS, BOARD_WIDTH, type PieceKind } from './rules'

/**
 * A board on the wire (ADR 0060, amended 2026-10-19): 22 rows of 10, top row
 * first, hidden rows included, one character a cell. `.` is empty, a letter is
 * that piece. The falling piece is baked in; the ghost is never sent. The phone
 * reads and writes the same string (`games/race/BoardWire.kt`), and both are
 * held to `shared/tetris/fixtures.json` `boardWire`.
 */
export const BOARD_CELLS = BOARD_ROWS * BOARD_WIDTH

const PIECES = new Set<string>(['I', 'J', 'L', 'O', 'S', 'T', 'Z'])

/** Locked cells plus the falling piece, as the 220-character wire string. */
export function encodeCells(board: readonly (readonly Cell[])[], active: { kind: PieceKind; cells: readonly (readonly [number, number])[] } | null): string {
  const grid = Array.from({ length: BOARD_ROWS }, (_, y) =>
    Array.from({ length: BOARD_WIDTH }, (_, x) => board[y]?.[x] ?? null),
  )
  if (active) {
    for (const [x, y] of active.cells) {
      if (y >= 0 && y < BOARD_ROWS && x >= 0 && x < BOARD_WIDTH) grid[y][x] = active.kind
    }
  }
  return grid.map((row) => row.map((cell) => cell ?? '.').join('')).join('')
}

/** The live game as the wire string. */
export function encodeBoard(game: TetrisGame): string {
  const active = game.active
  return encodeCells(game.board, active ? { kind: active.kind, cells: cellsOf(active) } : null)
}

/** The wire string as rows of cells, or null for anything that is not a whole board. */
export function decodeBoard(text: string | null | undefined): Cell[][] | null {
  if (!text || text.length !== BOARD_CELLS) return null
  const rows: Cell[][] = []
  for (let y = 0; y < BOARD_ROWS; y++) {
    const row: Cell[] = []
    for (let x = 0; x < BOARD_WIDTH; x++) {
      const ch = text[y * BOARD_WIDTH + x]
      if (ch === '.') row.push(null)
      else if (PIECES.has(ch)) row.push(ch as PieceKind)
      else return null
    }
    rows.push(row)
  }
  return rows
}
