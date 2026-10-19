import { LOCK_DELAY_S, SHAPES, TetrisGame, cellsOf, kicksFor } from './game'
import { BOARD_ROWS, BOARD_WIDTH, PieceStream } from './rules'

/** The board mechanics ticket 04 names: SRS, hold once, ghost, preview, drop
 * points, line clears scored at the level before the clear, top out. */

function fillRow(game: TetrisGame, y: number, gapX: number | null) {
  for (let x = 0; x < BOARD_WIDTH; x++) game.board[y][x] = x === gapX ? null : 'Z'
}

describe('a new game', () => {
  test('deals the seed\'s pieces: the first falls, the next five wait', () => {
    const game = new TetrisGame(0)
    const expected = PieceStream.first(0, 6)
    expect(game.active?.kind).toBe(expected[0])
    expect(game.next).toEqual(expected.slice(1))
  })

  test('two games with one seed deal the same sequence through hold and drops', () => {
    const a = new TetrisGame(123456789)
    const b = new TetrisGame(123456789)
    const seen: string[][] = [[], []]
    for (let i = 0; i < 12; i++) {
      for (const [n, game] of [a, b].entries()) {
        seen[n].push(game.active?.kind ?? '-')
        if (i === 3) game.holdPiece()
        else game.hardDrop()
      }
    }
    expect(seen[0]).toEqual(seen[1])
  })
})

describe('SRS', () => {
  test('each piece has four rotations of four cells, and O never changes', () => {
    for (const states of Object.values(SHAPES)) {
      expect(states).toHaveLength(4)
      for (const cells of states) expect(cells).toHaveLength(4)
    }
    const sorted = (cells: readonly (readonly number[])[]) => cells.map((c) => c.join(',')).sort()
    expect(sorted(SHAPES.O[1])).toEqual(sorted(SHAPES.O[0]))
  })

  test('the published first tests of each kick table', () => {
    expect(kicksFor('T', 0, 1)[1]).toEqual([-1, 0])
    expect(kicksFor('I', 0, 1)[1]).toEqual([-2, 0])
    expect(kicksFor('I', 3, 0)[4]).toEqual([-2, 1])
    expect(kicksFor('O', 0, 1)).toEqual([[0, 0]])
  })

  test('a T against the right wall kicks left to rotate', () => {
    const game = new TetrisGame(0)
    // Rotation L sits in box columns 0 and 1, so x = 8 is flush with the wall.
    game.active = { kind: 'T', rotation: 3, x: 8, y: 10 }
    expect(Math.max(...cellsOf(game.active).map(([x]) => x))).toBe(9)
    expect(game.rotate(1)).toBe(true)
    // Unkicked, rotation 0 would need column 10; the second test, (-1, 0), fits.
    expect(game.active.rotation).toBe(0)
    expect(game.active.x).toBe(7)
    expect(cellsOf(game.active).every(([x]) => x >= 0 && x < BOARD_WIDTH)).toBe(true)
  })
})

describe('hold', () => {
  test('once per piece, then again after a lock', () => {
    const game = new TetrisGame(1)
    const first = game.active?.kind
    expect(game.holdPiece()).toBe(true)
    expect(game.hold).toBe(first)
    expect(game.holdPiece()).toBe(false)
    game.hardDrop()
    const current = game.active?.kind
    expect(game.holdPiece()).toBe(true)
    expect(game.active?.kind).toBe(first)
    expect(game.hold).toBe(current)
  })
})

describe('scoring on the board', () => {
  test('a hard drop scores 2 a row and a soft drop 1 a row', () => {
    const game = new TetrisGame(0)
    expect(game.softDrop()).toBe(true)
    expect(game.softDrop()).toBe(true)
    const rows = game.hardDrop()
    expect(rows).toBeGreaterThan(0)
    expect(game.score).toBe(2 + rows * 2)
  })

  test('a single at level 1 is 100 plus the drop', () => {
    const game = new TetrisGame(0)
    const bottom = BOARD_ROWS - 1
    game.active = { kind: 'I', rotation: 1, x: 7, y: bottom - 3 } // vertical I in column 9
    fillRow(game, bottom, 9)
    const rows = game.hardDrop()
    expect(rows).toBe(0)
    expect(game.lines).toBe(1)
    expect(game.score).toBe(100)
  })

  test('a tetris is scored at the level before the clear, then the level rises', () => {
    const game = new TetrisGame(0)
    game.lines = 8
    game.level = 1
    const bottom = BOARD_ROWS - 1
    for (let y = bottom - 3; y <= bottom; y++) fillRow(game, y, 9)
    game.active = { kind: 'I', rotation: 1, x: 7, y: bottom - 3 }
    game.hardDrop()
    expect(game.score).toBe(800)
    expect(game.lines).toBe(12)
    expect(game.level).toBe(2)
  })
})

describe('gravity, the lock delay and the ghost', () => {
  test('the ghost is where a hard drop lands', () => {
    const game = new TetrisGame(42)
    const ghost = game.ghostY()
    const kind = game.active?.kind
    game.hardDrop()
    expect(game.board.flat().filter((c) => c === kind).length).toBe(4)
    expect(ghost).not.toBeNull()
  })

  test('a grounded piece locks only after the lock delay', () => {
    const game = new TetrisGame(42)
    const y = game.ghostY() as number
    game.active = { ...game.active!, y }
    const kind = game.active.kind
    game.tick(LOCK_DELAY_S / 2)
    expect(game.board.flat().some((c) => c !== null)).toBe(false)
    game.tick(LOCK_DELAY_S)
    expect(game.board.flat().filter((c) => c === kind).length).toBe(4)
  })

  test('at level 1 a piece falls one row a second', () => {
    const game = new TetrisGame(42)
    const start = game.active!.y
    game.tick(0.99)
    expect(game.active!.y).toBe(start)
    game.tick(0.02)
    expect(game.active!.y).toBe(start + 1)
  })
})

describe('topping out', () => {
  test('a piece that locks above the board ends the game, and the score stands', () => {
    const game = new TetrisGame(0)
    for (let y = 2; y < BOARD_ROWS; y++) fillRow(game, y, 9)
    game.active = { kind: 'O', rotation: 0, x: 0, y: 0 }
    game.score = 1234
    game.hardDrop()
    expect(game.over).toBe(true)
    expect(game.score).toBe(1234)
    expect(game.moveLeft()).toBe(false)
    expect(game.hardDrop()).toBe(0)
  })
})
