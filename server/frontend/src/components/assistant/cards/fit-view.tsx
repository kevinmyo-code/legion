import { Maximize, Minus, Plus } from 'lucide-react'
import { useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactNode, type WheelEvent } from 'react'

/**
 * A picture fitted to the space it is given, with zoom and pan (demo-modals, Kevin 2026-11-05:
 * "when i click to zoom its too zoomed in. so a pop up fit to page modal works better").
 *
 * `fit="contain"` scales the whole picture to the box's width AND height, keeping its aspect
 * ratio (a diagram). `fit="width"` scales it to the box's width and scrolls the rest (a page of
 * text). Zoom multiplies that fitted size; the box scrolls to pan; "Fit" puts both back.
 * A pinch or ctrl/cmd + wheel zooms around the fingers or the pointer; a plain wheel scrolls.
 *
 * `children(size)` draws the picture at exactly that size, in CSS pixels.
 */

export interface Size {
  width: number
  height: number
}

const ZOOM_MIN = 1
const ZOOM_MAX = 5
const STEP = 1.4

export function fitScale(box: Size, natural: Size, fit: 'contain' | 'width', maxWidth = Infinity): number {
  if (natural.width <= 0 || natural.height <= 0 || box.width <= 0 || box.height <= 0) return 1
  const across = Math.min(box.width, maxWidth) / natural.width
  return fit === 'width' ? across : Math.min(across, box.height / natural.height)
}

/** A wide picture in a tall box (a diagram on a phone held upright) draws small; say how to fix it. */
export function turnHint(box: Size, natural: Size | null): boolean {
  if (!natural || box.width <= 0) return false
  return natural.width / natural.height > 1.3 && box.height > box.width * 1.2
}

export function FitView({
  natural,
  fit,
  label,
  resetKey,
  maxWidth,
  children,
}: {
  /** The picture's own size (an SVG's viewBox, an image's pixels). */
  natural: Size | null
  fit: 'contain' | 'width'
  /** What the picture is, for the zoom controls' group label. */
  label: string
  /** A new value fits again (a new diagram); the same value keeps the visitor's zoom. */
  resetKey?: string
  /** The fitted width never passes this (a page of text on a desk stays a page). */
  maxWidth?: number
  children: (size: Size) => ReactNode
}) {
  const box = useRef<HTMLDivElement>(null)
  const [room, setRoom] = useState<Size>({ width: 0, height: 0 })
  const [zoom, setZoom] = useState(1)
  const zoomRef = useRef(1)
  zoomRef.current = zoom

  useLayoutEffect(() => {
    const el = box.current
    if (!el) return
    const measure = () => setRoom({ width: el.clientWidth, height: el.clientHeight })
    measure()
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(measure)
    observer.observe(el)
    return () => observer.disconnect()
  }, [])

  const reset = useCallback(() => {
    setZoom(1)
    box.current?.scrollTo?.({ left: 0, top: 0 })
  }, [])
  useEffect(() => {
    reset()
  }, [resetKey, reset])

  /** Zoom to `next`, keeping the point at (x, y) in the box under the same spot. */
  const zoomAt = useCallback((next: number, x?: number, y?: number) => {
    const el = box.current
    const clamped = Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, next))
    const before = zoomRef.current
    if (!el || clamped === before) {
      setZoom(clamped)
      return
    }
    const px = x ?? el.clientWidth / 2
    const py = y ?? el.clientHeight / 2
    const ratio = clamped / before
    const left = (el.scrollLeft + px) * ratio - px
    const top = (el.scrollTop + py) * ratio - py
    zoomRef.current = clamped
    setZoom(clamped)
    requestAnimationFrame(() => el.scrollTo?.({ left, top }))
  }, [])

  // Two fingers zoom. A non-passive listener, so the page itself does not zoom under them; one
  // finger is left to the browser, which scrolls the box (that is the pan).
  useEffect(() => {
    const el = box.current
    if (!el) return
    let pinch: { distance: number; zoom: number } | null = null
    const spread = (t: TouchList) => Math.hypot(t[0].clientX - t[1].clientX, t[0].clientY - t[1].clientY)
    const onStart = (e: TouchEvent) => {
      if (e.touches.length === 2) pinch = { distance: spread(e.touches), zoom: zoomRef.current }
    }
    const onMove = (e: TouchEvent) => {
      if (!pinch || e.touches.length !== 2 || pinch.distance <= 0) return
      e.preventDefault()
      const rect = el.getBoundingClientRect()
      const cx = (e.touches[0].clientX + e.touches[1].clientX) / 2 - rect.left
      const cy = (e.touches[0].clientY + e.touches[1].clientY) / 2 - rect.top
      zoomAt((pinch.zoom * spread(e.touches)) / pinch.distance, cx, cy)
    }
    const onEnd = (e: TouchEvent) => {
      if (e.touches.length < 2) pinch = null
    }
    el.addEventListener('touchstart', onStart, { passive: true })
    el.addEventListener('touchmove', onMove, { passive: false })
    el.addEventListener('touchend', onEnd)
    el.addEventListener('touchcancel', onEnd)
    return () => {
      el.removeEventListener('touchstart', onStart)
      el.removeEventListener('touchmove', onMove)
      el.removeEventListener('touchend', onEnd)
      el.removeEventListener('touchcancel', onEnd)
    }
  }, [zoomAt])

  const onWheel = (e: WheelEvent) => {
    if (!e.ctrlKey && !e.metaKey) return
    const rect = e.currentTarget.getBoundingClientRect()
    zoomAt(zoomRef.current * (e.deltaY < 0 ? 1.15 : 1 / 1.15), e.clientX - rect.left, e.clientY - rect.top)
  }

  const scale = natural ? fitScale(room, natural, fit, maxWidth) * zoom : 0
  const size = natural ? { width: natural.width * scale, height: natural.height * scale } : null

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div
        ref={box}
        data-fit-box=""
        onWheel={onWheel}
        className="relative min-h-0 flex-1 overflow-auto overscroll-contain"
        style={{ touchAction: 'pan-x pan-y' }}
      >
        {size && size.width > 0 && (
          <div
            className="flex items-center justify-center"
            style={{
              width: Math.max(size.width, room.width),
              height: fit === 'width' ? size.height : Math.max(size.height, room.height),
            }}
          >
            {children(size)}
          </div>
        )}
      </div>
      {fit === 'contain' && zoom === 1 && turnHint(room, natural) && (
        <p data-fit-hint="" className="flex-none pt-1.5 text-center text-[0.8125rem] text-muted-foreground">
          Turn your phone sideways for a bigger picture, or zoom in.
        </p>
      )}
      <div role="group" aria-label={`Zoom: ${label}`} data-fit-controls="" className="flex flex-none items-center justify-center gap-1 pt-2">
        <button type="button" className="grid size-11 place-items-center rounded-full border border-outline-variant" onClick={() => zoomAt(zoom / STEP)} disabled={zoom <= ZOOM_MIN} aria-label="Zoom out">
          <Minus className="size-4" aria-hidden="true" />
        </button>
        <button type="button" className="flex h-11 items-center gap-1.5 rounded-full border border-outline-variant px-4 text-sm" onClick={reset} aria-label={fit === 'width' ? 'Fit to width' : 'Fit to screen'}>
          <Maximize className="size-4" aria-hidden="true" />
          Fit
        </button>
        <span className="w-12 text-center text-[0.8125rem] tabular-nums" aria-live="polite">
          {Math.round(zoom * 100)}%
        </span>
        <button type="button" className="grid size-11 place-items-center rounded-full border border-outline-variant" onClick={() => zoomAt(zoom * STEP)} disabled={zoom >= ZOOM_MAX} aria-label="Zoom in">
          <Plus className="size-4" aria-hidden="true" />
        </button>
      </div>
    </div>
  )
}
