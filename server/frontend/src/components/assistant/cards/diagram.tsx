import { Maximize2 } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'

import { FitView, type Size } from './fit-view'
import { usePresenter } from './presenter-context'
import type { DiagramCard } from './types'

/**
 * An architecture diagram (`show_diagram`): the engine's pre-rendered SVG, drawn on its own
 * light panel so its edge labels read the same in the dark dock and on the typeset page
 * (`server/diagrams/render.py` BACKGROUND). The parts the assistant names are outlined, and
 * said in words beside the picture, never by colour alone. The caption is the picture in words.
 *
 * In the chat it is a small thumbnail; the picture itself opens over the whole page, fitted to
 * it (`screen-modal.tsx`, demo-modals: Kevin 2026-11-05, "the diagrams should kinda like pop up
 * over the whole page as a modal"). The modal opens by itself when the card arrives, and the
 * thumbnail opens it again.
 */

export const PANEL = '#f7f7f5'
const HIGHLIGHT_CLASS = 'dg-hl'

// Outlines the highlighted parts. `!important` because Mermaid's own rules carry the svg's id.
// `non-scaling-stroke` keeps the outline 3 screen pixels wide however small the fit makes the
// picture, so a highlighted part stays visible on a phone (demo-modals).
export const HIGHLIGHT_CSS = `
[data-diagram] [data-node].${HIGHLIGHT_CLASS} :is(rect, polygon, circle, ellipse, path):not(.arrowMarkerPath) {
  stroke: #b45309 !important;
  stroke-width: 3px !important;
  vector-effect: non-scaling-stroke;
}
[data-diagram] [data-node].${HIGHLIGHT_CLASS} > :is(rect, polygon, circle, ellipse, path):first-of-type {
  fill: #fdf0d5 !important;
}
[data-diagram] svg { display: block; }
[data-diagram="inline"] svg { width: 100%; height: auto; }
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

/** The picture's own size, from its `viewBox` (Mermaid always writes one). */
export function svgSize(svg: string): Size | null {
  const box = /<svg\b[^>]*\bviewBox="([^"]+)"/.exec(svg)?.[1]
  if (!box) return null
  const [, , width, height] = box.trim().split(/[\s,]+/).map(Number)
  return width > 0 && height > 0 ? { width, height } : null
}

/**
 * A callback ref for the element that holds the cleaned SVG, with the highlighted parts
 * outlined. A callback ref (not `useRef`) because the modal's copy mounts in a portal.
 * `size` (the modal) pins the SVG to exactly that many pixels; without it the SVG fills the
 * width (the thumbnail).
 */
function useSvg(card: DiagramCard, size?: Size) {
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
    imported.removeAttribute('width')
    imported.removeAttribute('height')
    imported.style.removeProperty('max-width')
    for (const node of Array.from(imported.querySelectorAll<SVGGElement>('[data-node]'))) {
      node.classList.toggle(HIGHLIGHT_CLASS, highlight.has(node.getAttribute('data-node') ?? ''))
    }
    host.appendChild(imported)
  }, [host, card.svg, highlight])
  const width = size?.width
  const height = size?.height
  useEffect(() => {
    const svg = host?.querySelector('svg')
    if (!svg || width === undefined || height === undefined) return
    svg.style.width = `${width}px`
    svg.style.height = `${height}px`
  }, [host, width, height, card.svg])
  return setHost
}

export function highlightWords(card: DiagramCard): string {
  const labels = new Map(card.nodes.map((n) => [n.id, n.label]))
  return card.highlight.map((id) => labels.get(id) ?? id).join(', ')
}

/** The thumbnail in the chat. Tapping it opens the diagram over the page. */
export function DiagramView({ card }: { card: DiagramCard }) {
  const presenter = usePresenter()
  const host = useSvg(card)
  const pointed = highlightWords(card)
  return (
    <figure data-card="diagram" className="m-0 flex flex-col gap-2">
      <style>{HIGHLIGHT_CSS}</style>
      <button
        type="button"
        data-card="diagram-open"
        onClick={() => presenter?.show(card)}
        disabled={!presenter}
        aria-label={`Open the diagram over the page: ${card.title}`}
        className="group relative block w-full cursor-zoom-in overflow-hidden rounded-md border border-outline-variant text-left focus-visible:outline-2 disabled:cursor-default"
        style={{ background: PANEL }}
      >
        <div ref={host} data-diagram="inline" className="max-h-[12rem] overflow-hidden p-2" />
        {presenter && (
          <span className="absolute right-1.5 bottom-1.5 flex items-center gap-1 rounded bg-black/60 px-1.5 py-0.5 text-[0.75rem] text-white">
            <Maximize2 className="size-3" aria-hidden="true" />
            Open
          </span>
        )}
      </button>
      <figcaption data-card="caption" className="text-[0.8125rem] text-muted-foreground">
        {card.caption}
      </figcaption>
      {pointed && (
        <p data-card="highlighted" className="text-[0.8125rem]">
          Highlighted: {pointed}
        </p>
      )}
    </figure>
  )
}

/** The diagram in the modal: fitted to the space, highlighted parts outlined and named. */
export function DiagramBody({ card }: { card: DiagramCard }) {
  const natural = useMemo(() => svgSize(card.svg), [card.svg])
  const pointed = highlightWords(card)
  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <style>{HIGHLIGHT_CSS}</style>
      <div className="flex min-h-0 flex-1 flex-col rounded-md border border-outline-variant p-1" style={{ background: PANEL }}>
        <FitView natural={natural} fit="contain" label={card.title} resetKey={`${card.project}/${card.view}`}>
          {(size) => <PinnedSvg card={card} size={size} />}
        </FitView>
      </div>
      {pointed && (
        <p data-modal="highlighted" className="pt-1.5 text-[0.875rem]">
          <span className="font-semibold">Highlighted:</span> {pointed}
        </p>
      )}
    </div>
  )
}

function PinnedSvg({ card, size }: { card: DiagramCard; size: Size }) {
  const host = useSvg(card, size)
  return <div ref={host} data-diagram="full" />
}
