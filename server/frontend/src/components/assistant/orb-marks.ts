/**
 * The marks the voice orb's points can gather into, beside a section that names one (Kevin,
 * 2026-11-22): drawn once, black on clear, on a square canvas the orb samples (`markTargets`).
 *
 * - `q`: the finance voice demo's Q, a letter in the heaviest system sans (no logo file).
 * - `larkspur`: the made-up shop "Larkspur & Pine": a stacked pine beside a larkspur flower spike.
 *   An original mark for an invented shop, plain shapes, no lettering. Its numbers (a 100 x 100
 *   box) are the same ones the page's picture of it draws (`store-rep/shop-mark.tsx`).
 */

export type OrbMark = 'q' | 'larkspur'

export const ORB_MARKS: readonly OrbMark[] = ['q', 'larkspur']

export type Triangle = readonly [number, number, number, number, number, number]

export const SHOP_MARK = {
  pine: [
    [38, 12, 22, 42, 54, 42],
    [38, 26, 17, 60, 59, 60],
    [38, 42, 11, 82, 65, 82],
  ] as readonly Triangle[],
  trunk: { x: 34, y: 82, w: 8, h: 12 },
  stem: { x: 77, top: 18, bottom: 94, w: 3 },
  // Florets up the spike, alternating sides, smaller toward the tip: [x, y, radius].
  florets: [
    [70, 84, 5.6],
    [84, 76, 5.3],
    [70, 67, 4.9],
    [84, 59, 4.5],
    [71, 50, 4.1],
    [83, 42, 3.7],
    [72, 34, 3.3],
    [81, 27, 2.9],
    [77, 19, 2.5],
  ] as readonly (readonly [number, number, number])[],
} as const

function drawShop(ctx: CanvasRenderingContext2D, size: number) {
  const k = size / 100
  const { pine, trunk, stem, florets } = SHOP_MARK
  for (const [ax, ay, bx, by, cx, cy] of pine) {
    ctx.beginPath()
    ctx.moveTo(ax * k, ay * k)
    ctx.lineTo(bx * k, by * k)
    ctx.lineTo(cx * k, cy * k)
    ctx.closePath()
    ctx.fill()
  }
  ctx.fillRect(trunk.x * k, trunk.y * k, trunk.w * k, trunk.h * k)
  ctx.fillRect((stem.x - stem.w / 2) * k, stem.top * k, stem.w * k, (stem.bottom - stem.top) * k)
  for (const [x, y, r] of florets) {
    ctx.beginPath()
    ctx.arc(x * k, y * k, r * k, 0, Math.PI * 2)
    ctx.fill()
  }
}

/** Fills `mark` black on a clear canvas `size` pixels square. */
export function drawMark(ctx: CanvasRenderingContext2D, mark: OrbMark, size: number) {
  ctx.fillStyle = '#000'
  if (mark === 'larkspur') {
    drawShop(ctx, size)
    return
  }
  ctx.font = `900 ${Math.round(size * 0.9)}px "Arial Black", "Segoe UI Black", Impact, "Helvetica Neue", Arial, sans-serif`
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.fillText(mark.toUpperCase(), size / 2, size / 2 + 3)
}
