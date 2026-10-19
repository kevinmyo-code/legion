import fixtures from '../../../../../shared/tetris/fixtures.json'
import { PieceStream, gravitySeconds, levelFor, mulberry32, scoreFor } from './rules'

/**
 * Ticket 04's rule set, held to `shared/tetris/fixtures.json`: the one file the
 * phone's Kotlin tests read too. A failure here is a drift between the two
 * implementations, and the fix is never an edit to the fixture.
 */

describe('mulberry32', () => {
  test.each(fixtures.rng)('seed $seed gives the fixture floats', ({ seed, firstFloats }) => {
    const next = mulberry32(seed)
    expect(firstFloats.map(() => next())).toEqual(firstFloats)
  })
})

describe('the 7-bag', () => {
  test.each(fixtures.bags)('seed $seed deals the fixture pieces', ({ seed, pieces }) => {
    expect(PieceStream.first(seed, pieces.length)).toEqual(pieces)
  })

  test('every run of seven from a bag boundary holds each piece once', () => {
    const pieces = PieceStream.first(42, 70)
    for (let i = 0; i < 70; i += 7) {
      expect([...pieces.slice(i, i + 7)].sort()).toEqual(['I', 'J', 'L', 'O', 'S', 'T', 'Z'])
    }
  })
})

describe('scoring', () => {
  test.each(fixtures.score)(
    'level $level, $linesCleared lines, $softDropCells soft, $hardDropCells hard = $points',
    ({ level, linesCleared, softDropCells, hardDropCells, points }) => {
      expect(scoreFor({ level, linesCleared, softDropCells, hardDropCells })).toBe(points)
    },
  )

  test.each(fixtures.level)('$totalLines lines is level $level', ({ totalLines, level }) => {
    expect(levelFor(totalLines)).toBe(level)
  })

  test.each(fixtures.gravity)('level $level falls a row every $secondsPerRow s', ({ level, secondsPerRow }) => {
    expect(Math.abs(gravitySeconds(level) - secondsPerRow)).toBeLessThanOrEqual(1e-9)
  })
})
