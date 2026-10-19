import { useEffect, useRef } from 'react'

import { cn } from '@/lib/utils'

import { decodeBoard } from './board-wire'
import { type Cell } from './game'
import { BOARD_WIDTH, HIDDEN_ROWS, VISIBLE_ROWS } from './rules'
import { type BoardTheme } from './themes'

/**
 * The other player's board in a race (ADR 0060, amended 2026-10-19): the
 * 220-character snapshot the engine relays about once a second, drawn small in
 * this player's own theme. It takes no input. When nothing new has arrived for a
 * while the board dims and says so in words; it never pretends to be live.
 */

const CELL = 12
const WALL = 4
const WIDTH = BOARD_WIDTH * CELL + 2 * WALL
const HEIGHT = VISIBLE_ROWS * CELL + WALL

function drawMini(canvas: HTMLCanvasElement | null, rows: Cell[][] | null, theme: BoardTheme) {
  let ctx: CanvasRenderingContext2D | null = null
  try {
    ctx = canvas?.getContext('2d') ?? null
  } catch {
    ctx = null
  }
  if (!canvas || !ctx) return
  const wellH = VISIBLE_ROWS * CELL
  ctx.fillStyle = theme.wall
  ctx.fillRect(0, 0, canvas.width, canvas.height)
  ctx.fillStyle = theme.well
  ctx.fillRect(WALL, 0, BOARD_WIDTH * CELL, wellH)
  if (!rows) return
  for (let y = HIDDEN_ROWS; y < rows.length; y++) {
    rows[y].forEach((cell, x) => {
      if (!cell) return
      const px = WALL + x * CELL
      const py = (y - HIDDEN_ROWS) * CELL
      ctx.fillStyle = theme.fill[cell]
      ctx.fillRect(px + 1, py + 1, CELL - 2, CELL - 2)
      ctx.strokeStyle = theme.edge[cell]
      ctx.lineWidth = 1
      ctx.strokeRect(px + 1.5, py + 1.5, CELL - 3, CELL - 3)
    })
  }
}

export interface OpponentBoardProps {
  name: string
  board: string
  theme: BoardTheme
  /** Nothing new for a while: dimmed, and the words say so. */
  stale: boolean
  className?: string
}

export function OpponentBoard({ name, board, theme, stale, className }: OpponentBoardProps) {
  const canvas = useRef<HTMLCanvasElement | null>(null)
  // Redrawn only when a new snapshot or theme arrives, not on every clock tick.
  useEffect(() => {
    drawMini(canvas.current, decodeBoard(board), theme)
  }, [board, theme])
  const label = stale
    ? `${name}'s board, not updating: waiting for ${name}.`
    : `${name}'s board, live.`
  return (
    <canvas
      ref={canvas}
      width={WIDTH}
      height={HEIGHT}
      role="img"
      aria-label={label}
      data-stale={stale ? 'true' : 'false'}
      className={cn('block h-auto w-full rounded-control transition-opacity', stale && 'opacity-40', className)}
    />
  )
}
