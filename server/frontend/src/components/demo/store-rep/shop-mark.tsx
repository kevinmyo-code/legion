import { SHOP_MARK } from '@/components/assistant/orb-marks'

/**
 * The made-up shop's mark ("Larkspur & Pine", Kevin 2026-11-22) as a picture: the pine in the
 * section's accent, the larkspur in its flower colour (`index.css`). The same numbers the orb's
 * points gather into (`orb-marks.ts`), so the picture and the morph are one shape. An original mark
 * for an invented shop: plain shapes, no lettering, nobody's logo.
 */
export function ShopMark({ className, title = 'Larkspur & Pine, a made-up shop' }: { className?: string; title?: string }) {
  const { pine, trunk, stem, florets } = SHOP_MARK
  return (
    <svg viewBox="0 0 100 100" className={className} role="img" aria-label={title} data-mark="larkspur">
      <g className="rc-shop-mark-pine">
        {pine.map(([ax, ay, bx, by, cx, cy]) => (
          <polygon key={`${ax}-${ay}`} points={`${ax},${ay} ${bx},${by} ${cx},${cy}`} />
        ))}
        <rect x={trunk.x} y={trunk.y} width={trunk.w} height={trunk.h} />
      </g>
      <g className="rc-shop-mark-larkspur">
        <rect x={stem.x - stem.w / 2} y={stem.top} width={stem.w} height={stem.bottom - stem.top} />
        {florets.map(([x, y, r]) => (
          <circle key={`${x}-${y}`} cx={x} cy={y} r={r} />
        ))}
      </g>
    </svg>
  )
}
