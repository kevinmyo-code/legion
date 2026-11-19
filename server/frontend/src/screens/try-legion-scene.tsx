import { useCallback, useMemo, useRef, useState, type ReactNode } from 'react'

import { useSceneSteps } from '@/components/demo/scroll-story'

/**
 * The LEGION section as a pinned scene (Kevin, 2026-11-19, "B bolder"): the diagram stays at the
 * top of the screen (`position: sticky`) while its parts scroll up under it, and the layer the part
 * crossing the lower reading line is about lights up. Full width at every size, so it stays as
 * legible as the plain page's diagram (which scrolls sideways on a phone, and still does). The page scrolls at the visitor's own speed: nothing here
 * listens to scroll events, the step in view comes from an IntersectionObserver.
 *
 * The diagram is the owner's own SVG, still drawn as an image (never injected as markup, so it runs
 * nothing). Its layers are read from it as data: the rows its boxes sit in, top to bottom. Part N
 * lights the layer at the same share of the way down (six parts over four layers: 0, 0, 1, 2, 2,
 * 3); content can say nothing finer today.
 */

export interface Band {
  /** Percent of the picture's height. */
  top: number
  height: number
}

/** The horizontal rows the diagram's boxes sit in, top to bottom; none for a picture without boxes. */
export function diagramLayers(svg: string): Band[] {
  let doc: Document
  try {
    doc = new DOMParser().parseFromString(svg, 'image/svg+xml')
  } catch {
    return []
  }
  const root = doc.documentElement
  if (root.nodeName.toLowerCase() !== 'svg' || doc.getElementsByTagName('parsererror').length) return []
  const box = (root.getAttribute('viewBox') ?? '').trim().split(/[\s,]+/).map(Number)
  const height = box.length === 4 && box[3] > 0 ? box[3] : Number(root.getAttribute('height'))
  if (!(height > 0)) return []
  const rows: [number, number][] = []
  // Only the drawing's own boxes: never a marker's or a pattern's, never a full-height backdrop.
  for (const rect of Array.from(root.querySelectorAll('rect'))) {
    if (rect.closest('defs, marker, pattern, symbol')) continue
    const y = Number(rect.getAttribute('y') ?? 0)
    const h = Number(rect.getAttribute('height') ?? 0)
    if (!(h > 0) || !Number.isFinite(y) || h > height * 0.8) continue
    rows.push([y, y + h])
  }
  rows.sort((a, b) => a[0] - b[0])
  const merged: [number, number][] = []
  for (const [start, end] of rows) {
    const last = merged[merged.length - 1]
    if (last && start <= last[1]) last[1] = Math.max(last[1], end)
    else merged.push([start, end])
  }
  return merged.map(([start, end]) => ({ top: (start / height) * 100, height: ((end - start) / height) * 100 }))
}

/** The layer step `step` of `steps` lights: the same share of the way down. */
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
  return (
    <div ref={root} className="rc-scene" data-scene="legion">
      <figure className="rc-scene-pin rc-figure">
        <div role="region" className="rc-frame" tabIndex={0} aria-label="Diagram, scrolls sideways">
          <div className="rc-scene-frame">
            <img src={src} alt={caption || 'Diagram'} />
            {layers.map((layer, index) => (
              <span
                key={`${layer.top}-${layer.height}`}
                aria-hidden="true"
                className="rc-scene-band"
                data-on={index === lit ? 'true' : 'false'}
                style={{ top: `${layer.top}%`, height: `${layer.height}%` }}
              />
            ))}
          </div>
        </div>
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
