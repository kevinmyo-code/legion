import { useCallback, useMemo, useRef, useState, type CSSProperties, type ReactNode } from 'react'

import { useSceneSteps, useStoryGlide } from '@/components/demo/scroll-story'

/**
 * The LEGION section as a pinned scene (Kevin, 2026-11-19, "B bolder"): the diagram stays at the
 * top of the screen (`position: sticky`) while its parts scroll up under it, and the layer the part
 * crossing the lower reading line is about lights up. Full width at every size, so it stays as
 * legible as the plain page's diagram (which scrolls sideways on a phone, and still does). The page scrolls at the visitor's own speed: nothing here
 * listens to scroll events, the step in view comes from an IntersectionObserver.
 *
 * The diagram is the owner's own SVG, still drawn as an image (never injected as markup, so it runs
 * nothing). Its layers are read from it as data: the rows or columns its boxes sit in. Part N
 * lights the layer at the same share of the way through (six parts over three layers: 0, 0, 1, 1,
 * 2, 2); content can say nothing finer today.
 */

export interface Band {
  /** Percent of the picture's width and height. */
  left: number
  width: number
  top: number
  height: number
}

type Box = { x0: number; x1: number; y0: number; y1: number }

/** Boxes whose spans on one axis overlap, grouped; in order along that axis. */
function groups(boxes: Box[], axis: 'x' | 'y'): Box[][] {
  const lo = axis === 'x' ? 'x0' : 'y0'
  const hi = axis === 'x' ? 'x1' : 'y1'
  const sorted = [...boxes].sort((a, b) => a[lo] - b[lo])
  const out: { end: number; boxes: Box[] }[] = []
  for (const box of sorted) {
    const last = out[out.length - 1]
    if (last && box[lo] <= last.end) {
      last.end = Math.max(last.end, box[hi])
      last.boxes.push(box)
    } else out.push({ end: box[hi], boxes: [box] })
  }
  return out.map((g) => g.boxes)
}

/**
 * The diagram's layers, in reading order: its boxes grouped into rows, or into columns when that
 * finds more (a tall box that spans rows joins them, as a hub between columns does). Each layer is
 * the box around its group. None for a picture without boxes.
 */
export function diagramLayers(svg: string): Band[] {
  let doc: Document
  try {
    doc = new DOMParser().parseFromString(svg, 'image/svg+xml')
  } catch {
    return []
  }
  const root = doc.documentElement
  if (root.nodeName.toLowerCase() !== 'svg' || doc.getElementsByTagName('parsererror').length) return []
  const view = (root.getAttribute('viewBox') ?? '').trim().split(/[\s,]+/).map(Number)
  const [vx, vy, vw, vh] = view.length === 4 && view[2] > 0 && view[3] > 0 ? view : [0, 0, Number(root.getAttribute('width')), Number(root.getAttribute('height'))]
  if (!(vw > 0) || !(vh > 0)) return []
  const boxes: Box[] = []
  // Only the drawing's own boxes: never a marker's or a pattern's, never a backdrop under it all.
  for (const rect of Array.from(root.querySelectorAll('rect'))) {
    if (rect.closest('defs, marker, pattern, symbol')) continue
    const x = Number(rect.getAttribute('x') ?? 0)
    const y = Number(rect.getAttribute('y') ?? 0)
    const w = Number(rect.getAttribute('width') ?? 0)
    const h = Number(rect.getAttribute('height') ?? 0)
    if (!(w > 0) || !(h > 0) || !Number.isFinite(x) || !Number.isFinite(y)) continue
    if (w > vw * 0.8 && h > vh * 0.8) continue
    boxes.push({ x0: x, x1: x + w, y0: y, y1: y + h })
  }
  const rows = groups(boxes, 'y')
  const columns = groups(boxes, 'x')
  const chosen = columns.length > rows.length ? columns : rows
  const pct = (value: number, span: number) => Math.round((value / span) * 10000) / 100
  return chosen.map((group) => {
    const x0 = Math.min(...group.map((b) => b.x0))
    const x1 = Math.max(...group.map((b) => b.x1))
    const y0 = Math.min(...group.map((b) => b.y0))
    const y1 = Math.max(...group.map((b) => b.y1))
    return { left: pct(x0 - vx, vw), width: pct(x1 - x0, vw), top: pct(y0 - vy, vh), height: pct(y1 - y0, vh) }
  })
}

/** Width over height, from the diagram's own box; null when it states none. */
export function diagramAspect(svg: string): number | null {
  const box = /viewBox\s*=\s*["']([^"']+)["']/.exec(svg)?.[1].trim().split(/[\s,]+/).map(Number)
  return box && box.length === 4 && box[2] > 0 && box[3] > 0 ? box[2] / box[3] : null
}

/** The layer step `step` of `steps` lights: the same share of the way through. */
export function layerFor(step: number, steps: number, layers: number): number {
  if (layers <= 0 || steps <= 0) return -1
  return Math.min(layers - 1, Math.floor((Math.max(0, step) * layers) / steps))
}

export function LegionScene({
  svg,
  caption,
  parts,
  lead,
}: {
  svg: string
  caption: string
  parts: { title: string; body: string }[]
  /** The part's run-in title, as the page sets it elsewhere. */
  lead: (title: string) => ReactNode
}) {
  const root = useRef<HTMLDivElement>(null)
  const [step, setStep] = useState(0)
  const layers = useMemo(() => diagramLayers(svg), [svg])
  const src = useMemo(() => `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`, [svg])
  const onStep = useCallback((index: number) => setStep(index), [])
  // The reading line sits below the pinned diagram (about the top half of the screen).
  useSceneSteps(root, true, onStep, '-72% 0px -24% 0px')
  const lit = layerFor(step, parts.length, layers.length)
  const aspect = useMemo(() => diagramAspect(svg), [svg])
  // 900 px and up the whole diagram fits; narrower its frame scrolls sideways and says so.
  const wide = useStoryGlide()
  return (
    <div ref={root} className="rc-scene" data-scene="legion">
      <figure className="rc-scene-pin rc-figure">
        <div role="region" className="rc-frame" tabIndex={0} aria-label={wide ? 'Diagram' : 'Diagram, scrolls sideways'}>
          {/* Its height capped by the screen's (CSS), so the parts always have room under it. */}
          <div className="rc-scene-frame" style={aspect ? ({ '--diagram-aspect': aspect } as CSSProperties) : undefined}>
            <img src={src} alt={caption || 'Diagram'} />
            {layers.map((layer, index) => (
              <span
                key={`${layer.left}-${layer.top}`}
                aria-hidden="true"
                className="rc-scene-band"
                data-on={index === lit ? 'true' : 'false'}
                style={{ left: `${layer.left}%`, width: `${layer.width}%`, top: `${layer.top}%`, height: `${layer.height}%` }}
              />
            ))}
          </div>
        </div>
        {!wide && (
          <p className="rc-swipe" aria-hidden="true">
            Swipe sideways for the rest of the diagram.
          </p>
        )}
        {caption && <figcaption className="rc-figcaption">{caption}</figcaption>}
      </figure>
      <div className="rc-scene-steps rc-runin">
        {parts.map((part, index) => (
          <p key={part.title} data-step={index} data-on={index === step ? 'true' : 'false'}>
            <b>{lead(part.title)}</b> {part.body}
          </p>
        ))}
      </div>
    </div>
  )
}
