import { describe, expect, it } from 'vitest'

import { BOARD_CELLS, decodeBoard, encodeBoard, encodeCells } from './board-wire'
import { TetrisGame, cellsOf, type Cell } from './game'
import { BOARD_ROWS, BOARD_WIDTH, type PieceKind } from './rules'

interface WireCase {
  name: string
  locked: [number, number, PieceKind][]
  active: { kind: PieceKind; cells: [number, number][] } | null
  wire: string
}

// Same Docker-build caveat as rules.test.ts: `shared/` is outside the image's context.
// @ts-ignore TS2307 inside the Docker build only
import rawFixtures from '../../../../../shared/tetris/fixtures.json'
const cases = (rawFixtures as { boardWire: WireCase[] }).boardWire

function emptyBoard(): Cell[][] {
  return Array.from({ length: BOARD_ROWS }, () => Array<Cell>(BOARD_WIDTH).fill(null))
}

/**
 * The board on the wire (ADR 0060, amended 2026-10-19). The phone encodes the
 * same fixtures in `BoardWireTest.kt`, so the two sides cannot drift apart.
 */
describe('board wire', () => {
  it('is 220 cells', () => {
    expect(BOARD_CELLS).toBe(220)
  })

  it.each(cases.map((c) => [c.name, c] as const))('encodes the shared fixture: %s', (_name, c) => {
    const board = emptyBoard()
    for (const [x, y, kind] of c.locked) board[y][x] = kind
    expect(encodeCells(board, c.active)).toBe(c.wire)
    expect(c.wire).toHaveLength(220)
  })

  it.each(cases.map((c) => [c.name, c] as const))('decodes the shared fixture back: %s', (_name, c) => {
    const rows = decodeBoard(c.wire)
    expect(rows).not.toBeNull()
    expect(encodeCells(rows!, null)).toBe(c.wire)
  })

  it('bakes in the falling piece and never the ghost', () => {
    const game = new TetrisGame(0)
    const wire = encodeBoard(game)
    const active = game.active!
    const letters = [...wire].filter((ch) => ch !== '.')
    expect(letters).toHaveLength(4)
    expect(new Set(letters)).toEqual(new Set([active.kind]))
    for (const [x, y] of cellsOf(active)) expect(wire[y * BOARD_WIDTH + x]).toBe(active.kind)
  })

  it('refuses anything that is not a whole board', () => {
    expect(decodeBoard('')).toBeNull()
    expect(decodeBoard(null)).toBeNull()
    expect(decodeBoard('.'.repeat(219))).toBeNull()
    expect(decodeBoard('.'.repeat(219) + 'G')).toBeNull()
    expect(decodeBoard('.'.repeat(219) + 'i')).toBeNull()
  })
})
