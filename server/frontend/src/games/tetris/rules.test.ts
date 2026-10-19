import { PieceStream, gravitySeconds, levelFor, mulberry32, scoreFor } from './rules'

interface Fixtures {
  rng: { seed: number; firstFloats: number[] }[]
  bags: { seed: number; pieces: string[] }[]
  score: { level: number; linesCleared: number; softDropCells: number; hardDropCells: number; points: number }[]
  level: { totalLines: number; level: number }[]
  gravity: { level: number; secondsPerRow: number }[]
}

// The engine's Docker build context is `server/`, so the repo-root `shared/` folder is absent
// when `tsc -b` runs inside the image build. The import is unresolvable there and only there;
// vitest (which never runs in that build) always resolves it.
// @ts-ignore TS2307 inside the Docker build only
import rawFixtures from '../../../../../shared/tetris/fixtures.json'
const fixtures = rawFixtures as Fixtures

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
