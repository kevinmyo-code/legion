import { Maximize2, Minus, Plus } from 'lucide-react'
import { useEffect, useMemo, useRef, useState, type PointerEvent, type WheelEvent } from 'react'

import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog'

import type { DiagramCard } from './types'

/**
 * An architecture diagram (`show_diagram`): the engine's pre-rendered SVG, drawn on its own
 * light panel so its edge labels read the same in the dark dock and on the typeset page
 * (`server/diagrams/render.py` BACKGROUND). The parts the assistant names are outlined, and
 * said in words under the picture, never by colour alone. The caption is the picture in words.
 *
 * Tap to open it full screen, where a pinch, the wheel or the buttons zoom it.
 */

const PANEL = '#f7f7f5'
const HIGHLIGHT_CLASS = 'dg-hl'
const ZOOM_MIN = 0.5
const ZOOM_MAX = 4

// Outlines the highlighted parts. `!important` because Mermaid's own rules carry the svg's id.
const HIGHLIGHT_CSS = `
[data-diagram] [data-node].${HIGHLIGHT_CLASS} :is(rect, polygon, circle, ellipse, path):not(.arrowMarkerPath) {
  stroke: #b45309 !important;
  stroke-width: 3px !important;
}
[data-diagram] [data-node].${HIGHLIGHT_CLASS} > :is(rect, polygon, circle, ellipse, path):first-of-type {
  fill: #fdf0d5 !important;
}
[data-diagram] svg { height: auto; display: block; }
`

/**
 * The SVG with anything that could run removed: scripts, foreign HTML, event handlers and
 * script links. The engine already refuses such an SVG when it renders and loads one; this is
 * the second fence, at the page.
 */
export function cleanSvg(svg: string): SVGSVGElement | null {
  const doc = new DOMParser().parseFromString(svg, 'image/svg+xml')
  const root = doc.documentElement
  if (root.nodeName.toLowerCase() !== 'svg' || doc.getElementsByTagName('parsererror').length) return null
  for (const bad of Array.from(root.querySelectorAll('script, foreignObject'))) bad.remove()
  for (const el of [root, ...Array.from(root.querySelectorAll('*'))]) {
    for (const attr of Array.from(el.attributes)) {
      const name = attr.name.toLowerCase()
      const value = attr.value.trim().toLowerCase()
      if (name.startsWith('on') || ((name === 'href' || name === 'xlink:href') && value.startsWith('javascript:'))) {
        el.removeAttribute(attr.name)
      }
    }
  }
  return root as unknown as SVGSVGElement
}

/**
 * A callback ref for the element that holds the cleaned SVG, with the highlighted parts
 * outlined. A callback ref (not `useRef`) because the full-screen copy mounts in a portal
 * after its first render.
 */
function useSvg(card: DiagramCard, zoom = 1) {
  const [host, setHost] = useState<HTMLDivElement | null>(null)
  const highlight = useMemo(() => new Set(card.highlight), [card.highlight])
  useEffect(() => {
    if (!host) return
    const svg = cleanSvg(card.svg)
    host.replaceChildren()
    if (!svg) return
    const imported = document.importNode(svg, true)
    imported.setAttribute('aria-hidden', 'true')
    imported.setAttribute('focusable', 'false')
    for (const node of Array.from(imported.querySelectorAll<SVGGElement>('[data-node]'))) {
      node.classList.toggle(HIGHLIGHT_CLASS, highlight.has(node.getAttribute('data-node') ?? ''))
    }
    host.appendChild(imported)
  }, [host, card.svg, highlight])
  useEffect(() => {
    const svg = host?.querySelector('svg')
    if (!svg) return
    svg.style.width = `${zoom * 100}%`
    svg.style.maxWidth = zoom === 1 ? '' : 'none'
  }, [host, zoom, card.svg])
  return setHost
}

function highlightWords(card: DiagramCard): string {
  const labels = new Map(card.nodes.map((n) => [n.id, n.label]))
  return card.highlight.map((id) => labels.get(id) ?? id).join(', ')
}

export function DiagramView({ card }: { card: DiagramCard }) {
  const [open, setOpen] = useState(false)
  const host = useSvg(card)
  const pointed = highlightWords(card)
  return (
    <figure data-card="diagram" className="m-0 flex flex-col gap-2">
      <style>{HIGHLIGHT_CSS}</style>
      <button
        type="button"
        data-card="diagram-open"
        onClick={() => setOpen(true)}
        aria-label={`Open the diagram full screen: ${card.title}`}
        className="group relative block w-full cursor-zoom-in overflow-hidden rounded-md border border-outline-variant text-left focus-visible:outline-2"
        style={{ background: PANEL }}
      >
        <div ref={host} data-diagram="inline" className="max-h-[20rem] overflow-hidden p-2" />
        <span className="absolute right-1.5 bottom-1.5 flex items-center gap-1 rounded bg-black/60 px-1.5 py-0.5 text-[0.75rem] text-white">
          <Maximize2 className="size-3" aria-hidden="true" />
          Full screen
        </span>
      </button>
      <figcaption data-card="caption" className="text-[0.8125rem] text-muted-foreground">
        {card.caption}
      </figcaption>
      {pointed && (
        <p data-card="highlighted" className="text-[0.8125rem]">
          Highlighted: {pointed}
        </p>
      )}
      <Dialog open={open} onOpenChange={setOpen}>
        {open && <FullScreen card={card} />}
      </Dialog>
    </figure>
  )
}

function FullScreen({ card }: { card: DiagramCard }) {
  const [zoom, setZoom] = useState(1)
  const host = useSvg(card, zoom)
  const pointers = useRef(new Map<number, { x: number; y: number }>())
  const pinch = useRef<{ distance: number; zoom: number } | null>(null)
  const clamp = (z: number) => Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, z))

  const distance = () => {
    const [a, b] = Array.from(pointers.current.values())
    return Math.hypot(a.x - b.x, a.y - b.y)
  }
  const onPointerDown = (e: PointerEvent) => {
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY })
    if (pointers.current.size === 2) pinch.current = { distance: distance(), zoom }
  }
  const onPointerMove = (e: PointerEvent) => {
    if (!pointers.current.has(e.pointerId)) return
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY })
    if (pointers.current.size === 2 && pinch.current && pinch.current.distance > 0) {
      setZoom(clamp((pinch.current.zoom * distance()) / pinch.current.distance))
    }
  }
  const onPointerUp = (e: PointerEvent) => {
    pointers.current.delete(e.pointerId)
    if (pointers.current.size < 2) pinch.current = null
  }
  const onWheel = (e: WheelEvent) => {
    if (!e.ctrlKey && !e.metaKey && !e.altKey) return
    setZoom((z) => clamp(z * (e.deltaY < 0 ? 1.15 : 1 / 1.15)))
  }

  return (
    <DialogContent
      data-card="diagram-full"
      className="flex h-[calc(100dvh-1rem)] w-[calc(100vw-1rem)] max-w-none flex-col gap-2 p-3 sm:max-w-none"
    >
      <div className="flex items-start gap-2 pr-10">
        <div className="min-w-0 flex-1">
          <DialogTitle className="text-base leading-tight">{card.title}</DialogTitle>
          <DialogDescription className="text-[0.8125rem]">{card.caption}</DialogDescription>
        </div>
        <div className="flex flex-none items-center gap-1" role="group" aria-label="Zoom">
          <button type="button" className="rounded border border-outline-variant p-1.5" onClick={() => setZoom((z) => clamp(z / 1.25))} aria-label="Zoom out">
            <Minus className="size-4" aria-hidden="true" />
          </button>
          <span className="w-12 text-center text-[0.8125rem] tabular-nums" aria-live="polite">
            {Math.round(zoom * 100)}%
          </span>
          <button type="button" className="rounded border border-outline-variant p-1.5" onClick={() => setZoom((z) => clamp(z * 1.25))} aria-label="Zoom in">
            <Plus className="size-4" aria-hidden="true" />
          </button>
        </div>
      </div>
      <div
        ref={host}
        data-diagram="full"
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
        onWheel={onWheel}
        className="min-h-0 flex-1 overflow-auto rounded-md border border-outline-variant p-2"
        style={{ background: PANEL, touchAction: 'pan-x pan-y' }}
      />
      {card.highlight.length > 0 && <p className="text-[0.8125rem]">Highlighted: {highlightWords(card)}</p>}
    </DialogContent>
  )
}
