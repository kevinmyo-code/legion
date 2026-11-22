import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type CSSProperties, type ReactNode } from 'react'

import { useSceneSteps, useStoryMotion, useStoryWide } from '@/components/demo/scroll-story'

/**
 * The LEGION section as one scrollytelling stage (Kevin, 2026-11-22: the words should move with
 * the diagram's highlights, not scroll away under it, and the highlights should move more). The
 * stage is pinned (`position: sticky`) while the page scrolls past one invisible marker per part;
 * the part at the middle of the screen is the step. The step's words sit in a card that is part of
 * the stage, under the boxes it lights (at desk width the card slides under them; on a phone the
 * diagram zooms to them, the card under it). Its boxes light with a glow and a slow pulse, the
 * connections between them draw on, and packets travel along them. Nothing listens to scroll: the
 * step comes from an IntersectionObserver, and the motion is CSS (transform, opacity and SVG stroke).
 * Reduced motion: the same steps, with instant changes and a short fade; nothing travels or pulses.
 *
 * The diagram is the owner's own SVG, still drawn as an image (never injected as markup, so it runs
 * nothing). Its boxes, their headings and its lines are read from it as numbers and words, and the
 * highlights are drawn by this page on a layer of its own over the image. A part names the boxes it
 * is about by their headings (`lights`); a part that names none lights the layer at the same share
 * of the way through (six parts over three layers: 0, 0, 1, 1, 2, 2).
 */

export interface Band {
  /** Percent of the picture's width and height. */
  left: number
  width: number
  top: number
  height: number
}

/** A box of the diagram, in its own units, with the heading written in it ("" when none). */
export interface DiagramBox {
  x: number
  y: number
  w: number
  h: number
  label: string
}

/** A connection, in the diagram's units; `both` when it has an arrow at each end. */
export interface DiagramEdge {
  x1: number
  y1: number
  x2: number
  y2: number
  both: boolean
}

export interface Diagram {
  view: { x: number; y: number; w: number; h: number }
  boxes: DiagramBox[]
  edges: DiagramEdge[]
}

type Span = { x0: number; x1: number; y0: number; y1: number }

/** Boxes whose spans on one axis overlap, grouped; in order along that axis. */
function groups<T extends Span>(boxes: T[], axis: 'x' | 'y'): T[][] {
  const lo = axis === 'x' ? 'x0' : 'y0'
  const hi = axis === 'x' ? 'x1' : 'y1'
  const sorted = [...boxes].sort((a, b) => a[lo] - b[lo])
  const out: { end: number; boxes: T[] }[] = []
  for (const box of sorted) {
    const last = out[out.length - 1]
    if (last && box[lo] <= last.end) {
      last.end = Math.max(last.end, box[hi])
      last.boxes.push(box)
    } else out.push({ end: box[hi], boxes: [box] })
  }
  return out.map((g) => g.boxes)
}

function num(el: Element, name: string): number {
  return Number(el.getAttribute(name) ?? 0)
}

/**
 * The diagram read as data: its viewBox, its boxes (never a marker's or a pattern's, never a backdrop
 * under it all) each with the first line of text inside it, and its straight connections. Null for
 * anything that is not a readable SVG.
 */
export function readDiagram(svg: string): Diagram | null {
  let doc: Document
  try {
    doc = new DOMParser().parseFromString(svg, 'image/svg+xml')
  } catch {
    return null
  }
  const root = doc.documentElement
  if (root.nodeName.toLowerCase() !== 'svg' || doc.getElementsByTagName('parsererror').length) return null
  const vb = (root.getAttribute('viewBox') ?? '').trim().split(/[\s,]+/).map(Number)
  const [x, y, w, h] = vb.length === 4 && vb[2] > 0 && vb[3] > 0 ? vb : [0, 0, num(root, 'width'), num(root, 'height')]
  if (!(w > 0) || !(h > 0)) return null
  const outside = (el: Element) => Boolean(el.closest('defs, marker, pattern, symbol'))
  const texts = Array.from(root.querySelectorAll('text'))
    .filter((t) => !outside(t))
    .map((t) => ({ x: num(t, 'x'), y: num(t, 'y'), words: (t.textContent ?? '').trim() }))
  const boxes: DiagramBox[] = []
  for (const rect of Array.from(root.querySelectorAll('rect'))) {
    if (outside(rect)) continue
    const bx = num(rect, 'x')
    const by = num(rect, 'y')
    const bw = num(rect, 'width')
    const bh = num(rect, 'height')
    if (!(bw > 0) || !(bh > 0) || !Number.isFinite(bx) || !Number.isFinite(by)) continue
    if (bw > w * 0.8 && bh > h * 0.8) continue
    const inside = texts.filter((t) => t.x >= bx && t.x <= bx + bw && t.y >= by && t.y <= by + bh && t.words)
    inside.sort((a, b) => a.y - b.y)
    boxes.push({ x: bx, y: by, w: bw, h: bh, label: inside[0]?.words ?? '' })
  }
  const edges: DiagramEdge[] = Array.from(root.querySelectorAll('line'))
    .filter((line) => !outside(line))
    .map((line) => ({
      x1: num(line, 'x1'),
      y1: num(line, 'y1'),
      x2: num(line, 'x2'),
      y2: num(line, 'y2'),
      both: line.hasAttribute('marker-start') && line.hasAttribute('marker-end'),
    }))
    .filter((e) => [e.x1, e.y1, e.x2, e.y2].every(Number.isFinite) && (e.x1 !== e.x2 || e.y1 !== e.y2))
  return { view: { x, y, w, h }, boxes, edges }
}

type IndexedBox = DiagramBox & Span & { index: number }

function spanOf(b: DiagramBox, index: number): IndexedBox {
  return { ...b, index, x0: b.x, x1: b.x + b.w, y0: b.y, y1: b.y + b.h }
}

/** The diagram's layers as groups of its boxes (with their indexes): rows, or columns when that finds more. */
function layerGroups(diagram: Diagram): IndexedBox[][] {
  const spans = diagram.boxes.map(spanOf)
  const rows = groups(spans, 'y')
  const columns = groups(spans, 'x')
  return columns.length > rows.length ? columns : rows
}

/**
 * The diagram's layers, in reading order: its boxes grouped into rows, or into columns when that
 * finds more (a tall box that spans rows joins them, as a hub between columns does). Each layer is
 * the box around its group. None for a picture without boxes.
 */
export function diagramLayers(svg: string): Band[] {
  const diagram = readDiagram(svg)
  if (!diagram) return []
  const { x: vx, y: vy, w: vw, h: vh } = diagram.view
  const pct = (value: number, span: number) => Math.round((value / span) * 10000) / 100
  return layerGroups(diagram).map((group) => {
    const b = bounds(group)
    return { left: pct(b.x - vx, vw), width: pct(b.w, vw), top: pct(b.y - vy, vh), height: pct(b.h, vh) }
  })
}

/** Width over height, from the diagram's viewBox, else its own width and height; null for neither. */
export function diagramAspect(svg: string): number | null {
  const box = /viewBox\s*=\s*["']([^"']+)["']/.exec(svg)?.[1].trim().split(/[\s,]+/).map(Number)
  if (box && box.length === 4 && box[2] > 0 && box[3] > 0) return box[2] / box[3]
  const root = /<svg\b[^>]*>/i.exec(svg)?.[0] ?? ''
  const width = Number(/\swidth\s*=\s*["']?([\d.]+)/i.exec(root)?.[1])
  const height = Number(/\sheight\s*=\s*["']?([\d.]+)/i.exec(root)?.[1])
  return width > 0 && height > 0 ? width / height : null
}

/** The layer step `step` of `steps` lights: the same share of the way through. */
export function layerFor(step: number, steps: number, layers: number): number {
  if (layers <= 0 || steps <= 0) return -1
  return Math.min(layers - 1, Math.floor((Math.max(0, step) * layers) / steps))
}

function norm(words: string): string {
  return words.trim().toLowerCase().replace(/\s+/g, ' ')
}

/**
 * The boxes a step lights (their indexes): the ones its part names by heading; else, or when none of
 * its names is found, the boxes of the layer at its share of the way through.
 */
export function litBoxes(diagram: Diagram, lights: readonly string[] | undefined, step: number, steps: number): number[] {
  if (lights && lights.length > 0) {
    const wanted = new Set(lights.map(norm))
    const named = diagram.boxes.flatMap((b, i) => (wanted.has(norm(b.label)) ? [i] : []))
    if (named.length > 0) return named
  }
  const layers = layerGroups(diagram)
  const layer = layers[layerFor(step, steps, layers.length)] ?? []
  return layer.map((box) => box.index).sort((a, b) => a - b)
}

// A connection's end counts as on a box within this many units (its arrowhead stops short of it).
const TOUCH = 4

function touches(px: number, py: number, b: DiagramBox): boolean {
  return px >= b.x - TOUCH && px <= b.x + b.w + TOUCH && py >= b.y - TOUCH && py <= b.y + b.h + TOUCH
}

/**
 * The connections a step lights: the ones between two of its lit boxes; when there are none (a
 * single box, or boxes side by side with no line between them), every one that reaches a lit box.
 */
export function litEdges(diagram: Diagram, lit: readonly number[]): number[] {
  const boxes = lit.map((i) => diagram.boxes[i]).filter(Boolean)
  const on = (px: number, py: number) => boxes.some((b) => touches(px, py, b))
  const ends = diagram.edges.map((e) => [on(e.x1, e.y1), on(e.x2, e.y2)])
  const between = ends.flatMap(([a, b], i) => (a && b ? [i] : []))
  if (between.length > 0) return between
  return ends.flatMap(([a, b], i) => (a || b ? [i] : []))
}

function bounds(boxes: readonly { x: number; y: number; w: number; h: number }[]): { x: number; y: number; w: number; h: number } {
  const x0 = Math.min(...boxes.map((b) => b.x))
  const y0 = Math.min(...boxes.map((b) => b.y))
  const x1 = Math.max(...boxes.map((b) => b.x + b.w))
  const y1 = Math.max(...boxes.map((b) => b.y + b.h))
  return { x: x0, y: y0, w: x1 - x0, h: y1 - y0 }
}

export interface Framing {
  /** Pixels per diagram unit, and where the diagram's origin lands in the frame. */
  scale: number
  x: number
  y: number
}

// A phone zooms in at most this far past fitting the whole diagram (beyond it the image blurs).
const MAX_ZOOM = 3
// The image's own layout scale: drawn this many pixels per diagram unit, then transformed.
const LAYOUT = 2
// Room kept around the lit boxes when zoomed to them, in diagram units.
const PAD = 18

// On a phone the picture is never shown smaller than this many pixels per diagram unit: the
// diagram's headings are 15 units tall, so they read at 12 px or more (blind review F11).
export const PHONE_MIN_SCALE = 0.8

function fitScale(boxes: readonly DiagramBox[], width: number, height: number): number {
  const b = bounds(boxes)
  return Math.min(width / (b.w + 2 * PAD), height / (b.h + 2 * PAD))
}

/**
 * A phone's shots of a step: its lit boxes in groups that each fit the frame at `minScale` or more,
 * in reading order (left to right, then down). One group when they all fit; otherwise the stage
 * pans from one group to the next, a zoomed sequence, rather than shrinking the picture unreadable.
 */
export function phoneShots(diagram: Diagram, lit: readonly number[], width: number, height: number, minScale = PHONE_MIN_SCALE): number[][] {
  const order = [...lit].filter((i) => diagram.boxes[i]).sort((a, b) => diagram.boxes[a].x - diagram.boxes[b].x || diagram.boxes[a].y - diagram.boxes[b].y)
  const shots: number[][] = []
  for (const index of order) {
    const current = shots[shots.length - 1]
    if (current && fitScale([...current, index].map((i) => diagram.boxes[i]), width, height) >= minScale) current.push(index)
    else shots.push([index])
  }
  return shots
}

/**
 * Where the diagram sits in a `width` x `height` frame: the whole of it, centred; or, zooming, the
 * boxes given with a little room around them, centred, never past `MAX_ZOOM` times the whole and
 * never under `minScale` (a box too big for the frame at that scale is centred and cropped).
 */
export function frameDiagram(
  diagram: Diagram,
  lit: readonly number[],
  width: number,
  height: number,
  zoom: boolean,
  minScale = 0,
): Framing {
  const v = diagram.view
  const whole = Math.min(width / v.w, height / v.h)
  const boxes = lit.map((i) => diagram.boxes[i]).filter(Boolean)
  const target = zoom && boxes.length > 0 ? bounds(boxes) : null
  const area = target ? { x: target.x - PAD, y: target.y - PAD, w: target.w + 2 * PAD, h: target.h + 2 * PAD } : { x: v.x, y: v.y, w: v.w, h: v.h }
  const scale = target ? Math.max(Math.min(width / area.w, height / area.h, whole * MAX_ZOOM), minScale) : whole
  const cx = area.x + area.w / 2
  const cy = area.y + area.h / 2
  return { scale, x: width / 2 - (cx - v.x) * scale, y: height / 2 - (cy - v.y) * scale }
}

/** Where the card goes along the frame: under the lit boxes, inside the frame. */
export function cardOffset(litCentre: number, frameWidth: number, cardWidth: number): number {
  return Math.round(Math.min(Math.max(litCentre - cardWidth / 2, 0), Math.max(0, frameWidth - cardWidth)))
}

export interface ScenePart {
  title: string
  body: string
  /** The headings of the boxes this part is about. */
  lights?: string[]
  /** A shorter wording of `body`, read on a phone (the same facts, fewer words). */
  short?: string
}

export function LegionScene({
  svg,
  caption,
  parts,
  lead,
}: {
  svg: string
  caption: string
  parts: ScenePart[]
  /** The part's run-in title, as the page sets it elsewhere. */
  lead: (title: string) => ReactNode
}) {
  const root = useRef<HTMLDivElement>(null)
  const frame = useRef<HTMLDivElement>(null)
  const card = useRef<HTMLOListElement>(null)
  const [step, setStep] = useState(0)
  const diagram = useMemo(() => readDiagram(svg), [svg])
  const src = useMemo(() => `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`, [svg])
  const aspect = useMemo(() => diagramAspect(svg), [svg])
  // The step, and the shot within it, are the marker at the middle of the screen: a phone step
  // whose lit boxes need more than one readable shot has one marker per shot, so the visitor's own
  // scroll moves the stage between them (nothing pans on its own).
  const [shot, setShot] = useState(0)
  const onStep = useCallback((index: number, element: HTMLElement) => {
    setStep(index)
    setShot(Number(element.dataset.shot ?? 0) || 0)
  }, [])
  const motion = useStoryMotion()
  // 900 px and up the whole diagram shows and the card slides under the lit boxes, motion or not;
  // narrower the diagram zooms to them and the card is as wide as the stage.
  const wide = useStoryWide()
  const lit = useMemo(
    () => (diagram ? litBoxes(diagram, parts[step]?.lights, step, parts.length) : []),
    [diagram, parts, step],
  )
  const edges = useMemo(() => (diagram ? litEdges(diagram, lit) : []), [diagram, lit])

  // The frame's and the card's sizes, measured when they change (never on scroll).
  const [size, setSize] = useState({ width: 0, height: 0, card: 0 })
  useLayoutEffect(() => {
    const f = frame.current
    if (!f) return
    const measure = () => setSize({ width: f.clientWidth, height: f.clientHeight, card: card.current?.offsetWidth ?? 0 })
    measure()
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure)
    observer?.observe(f)
    if (card.current) observer?.observe(card.current)
    return () => observer?.disconnect()
  }, [])

  // The loops (pulse, packets) run only while the scene is on screen.
  const [visible, setVisible] = useState(true)
  useEffect(() => {
    const el = root.current
    if (!el || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver((entries) => {
      const latest = entries[entries.length - 1]
      if (latest) setVisible(latest.isIntersecting)
    })
    observer.observe(el)
    return () => observer.disconnect()
  }, [])

  // A phone: each part's lit boxes in readable shots (one marker each, below). Wide: one shot.
  const shotsPerPart = useMemo(
    () =>
      parts.map((part, index) => {
        if (!diagram || wide || size.width === 0) return 1
        const boxes = litBoxes(diagram, part.lights, index, parts.length)
        return Math.max(1, phoneShots(diagram, boxes, size.width, size.height).length)
      }),
    [diagram, wide, parts, size.width, size.height],
  )
  const shots = useMemo(
    () => (diagram && !wide && size.width > 0 ? phoneShots(diagram, lit, size.width, size.height) : [lit]),
    [diagram, wide, lit, size.width, size.height],
  )
  useSceneSteps(root, true, onStep, '-50% 0px -50% 0px', shotsPerPart.join(','))
  const inView = shots[Math.min(shot, shots.length - 1)] ?? lit
  const framing =
    diagram && size.width > 0
      ? frameDiagram(diagram, wide ? lit : inView, size.width, size.height, !wide, wide ? 0 : PHONE_MIN_SCALE)
      : null
  const litArea = diagram && lit.length > 0 ? bounds(lit.map((i) => diagram.boxes[i])) : null
  const litCentre = framing && litArea ? framing.x + (litArea.x - diagram!.view.x + litArea.w / 2) * framing.scale : size.width / 2
  const shift = wide ? cardOffset(litCentre, size.width, size.card) : 0
  const pointer = Math.min(Math.max(litCentre - shift, 16), Math.max(16, size.card - 16))
  const v = diagram?.view

  return (
    <div
      ref={root}
      className="rc-scene"
      data-scene="legion"
      data-motion={motion ? 'on' : 'off'}
      data-visible={visible ? 'true' : 'false'}
      data-framing={wide ? 'whole' : 'zoom'}
      data-step-now={step}
      data-shot-now={Math.min(shot, shots.length - 1)}
      data-shots={shots.length}
      style={{ '--scene-steps': parts.length } as CSSProperties}
    >
      <div className="rc-scene-stage">
        <figure className="rc-figure rc-scene-figure">
          <div ref={frame} className="rc-scene-frame" style={aspect ? ({ '--diagram-aspect': aspect } as CSSProperties) : undefined}>
            {!v && (
              // A picture whose shape cannot be read: shown whole, with no highlights over it.
              <img className="rc-scene-plain" src={src} alt={caption || 'Diagram'} />
            )}
            {v && (
              <div
                className="rc-scene-layer"
                style={{
                  // Laid out at twice the diagram's units and scaled down, so a zoomed-in image stays sharp.
                  width: `${v.w * LAYOUT}px`,
                  height: `${v.h * LAYOUT}px`,
                  transform: framing
                    ? `translate(${framing.x.toFixed(1)}px, ${framing.y.toFixed(1)}px) scale(${(framing.scale / LAYOUT).toFixed(4)})`
                    : undefined,
                }}
              >
                <img src={src} alt={caption || 'Diagram'} />
                <svg className="rc-scene-overlay" viewBox={`${v.x} ${v.y} ${v.w} ${v.h}`} aria-hidden="true" focusable="false">
                  {diagram!.edges.map((e, i) => {
                    const on = edges.includes(i)
                    return (
                      <g key={`e${i}`} className="rc-scene-edge" data-on={on ? 'true' : 'false'}>
                        <line x1={e.x1} y1={e.y1} x2={e.x2} y2={e.y2} pathLength={1} />
                        {on && (
                          <>
                            <circle key={`f${step}`} className="rc-scene-packet" cx={e.x1} cy={e.y1} r={4} style={{ '--dx': `${e.x2 - e.x1}px`, '--dy': `${e.y2 - e.y1}px` } as CSSProperties} />
                            {e.both && (
                              <circle
                                key={`b${step}`}
                                className="rc-scene-packet rc-scene-packet-back"
                                cx={e.x2}
                                cy={e.y2}
                                r={4}
                                style={{ '--dx': `${e.x1 - e.x2}px`, '--dy': `${e.y1 - e.y2}px` } as CSSProperties}
                              />
                            )}
                          </>
                        )}
                      </g>
                    )
                  })}
                  {diagram!.boxes.map((b, i) => (
                    <g key={`b${i}`} className="rc-scene-node" data-on={lit.includes(i) ? 'true' : 'false'}>
                      <rect className="rc-scene-glow" x={b.x - 3} y={b.y - 3} width={b.w + 6} height={b.h + 6} rx={6} />
                      <rect className="rc-scene-ring" x={b.x - 1} y={b.y - 1} width={b.w + 2} height={b.h + 2} rx={4} />
                      {lit.includes(i) && <rect key={`p${step}`} className="rc-scene-pulse" x={b.x - 1} y={b.y - 1} width={b.w + 2} height={b.h + 2} rx={4} />}
                    </g>
                  ))}
                </svg>
              </div>
            )}
          </div>
          {/* In the stage at desk width; on a phone below the scene (CSS), so the card has room. */}
          {caption && <figcaption className="rc-figcaption rc-scene-caption">{caption}</figcaption>}
        </figure>
        {/* The words, once each, in order: the step's shows, under the boxes it lights. */}
        <ol
          ref={card}
          className="rc-scene-card rc-runin"
          style={{ transform: shift ? `translateX(${shift}px)` : undefined, '--pointer-x': `${pointer}px` } as CSSProperties}
        >
          {parts.map((part, index) => (
            <li key={part.title} data-on={index === step ? 'true' : 'false'} aria-current={index === step ? 'step' : undefined}>
              <p>
                <b>{lead(part.title)}</b>{' '}
                {part.short ? (
                  // A phone reads the shorter wording (CSS shows one of the two, never both).
                  <>
                    <span className="rc-part-full">{part.body}</span>
                    <span className="rc-part-short">{part.short}</span>
                  </>
                ) : (
                  part.body
                )}
              </p>
            </li>
          ))}
        </ol>
      </div>
      {/* One marker per part (per shot on a phone), scrolling past the pinned stage: the one at
          the middle is the step. */}
      <div className="rc-scene-steps" aria-hidden="true">
        {parts.map((part, index) => (
          <div key={part.title} className="rc-scene-part">
            {Array.from({ length: shotsPerPart[index] }, (_, k) => (
              <div key={k} data-step={index} data-shot={k} />
            ))}
          </div>
        ))}
      </div>
      {caption && (
        <p className="rc-figcaption rc-scene-caption-after" aria-hidden="true">
          {caption}
        </p>
      )}
    </div>
  )
}
