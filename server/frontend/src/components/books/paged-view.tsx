import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'

import { resolvePaged } from '@/lib/books/locator'

import { tapZone, type StartPoint, type ViewControl, type ViewEvents } from './view-types'

/**
 * One page at a time, for PDF and comics: the page number, taps, and the position
 * reporting are the same for both, so they live once. A subclass of nothing - the
 * format supplies `children`, a function that draws page `index`.
 *
 * The locator for these formats is `{ page }`, 0-based, and `total_progression` is
 * `page / (pageCount - 1)` so the last page reads 100%.
 */

export function pagedPlace(index: number, count: number) {
  return {
    locator: { page: index },
    totalProgression: count > 1 ? index / (count - 1) : 0,
    chapterLabel: null,
  }
}

export function startPage(start: StartPoint, count: number): { page: number; appliedExact: boolean } {
  if (start.kind === 'none') return { page: 0, appliedExact: true }
  if (start.kind === 'exact') {
    const r = resolvePaged({ locator: start.locator, total_progression: start.totalProgression }, count)
    return { page: r.page, appliedExact: r.kind === 'exact' }
  }
  const r = resolvePaged({ locator: null, total_progression: start.totalProgression }, count)
  return { page: r.page, appliedExact: false }
}

export function PagedStage({
  count,
  start,
  controlRef,
  onReady,
  onPlace,
  onMiddleTap,
  onUserTurn,
  render,
  label,
}: {
  count: number
  start: StartPoint
  controlRef: { current: ViewControl | null }
  render: (index: number, size: { width: number; height: number }) => ReactNode
  /** What the page is called to a screen reader: "Page 3 of 10". */
  label: (index: number) => string
} & Pick<ViewEvents, 'onReady' | 'onPlace' | 'onMiddleTap' | 'onUserTurn'>) {
  const box = useRef<HTMLDivElement>(null)
  const [index, setIndex] = useState(0)
  const [size, setSize] = useState({ width: 0, height: 0 })
  const live = useRef({ onReady, onPlace })
  live.current = { onReady, onPlace }
  const indexRef = useRef(0)
  indexRef.current = index

  const go = useCallback(
    (to: number) => {
      const clamped = Math.min(count - 1, Math.max(0, to))
      setIndex(clamped)
      live.current.onPlace(pagedPlace(clamped, count))
    },
    [count],
  )

  useEffect(() => {
    const first = startPage(start, count)
    setIndex(first.page)
    live.current.onPlace(pagedPlace(first.page, count))
    live.current.onReady({ appliedExact: first.appliedExact, pageCount: count })
    // Opened once per book and start point.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [count, start])

  useEffect(() => {
    controlRef.current = {
      next: () => go(indexRef.current + 1),
      prev: () => go(indexRef.current - 1),
      goTo: (loc) => typeof loc.page === 'number' && go(loc.page),
      current: () => pagedPlace(indexRef.current, count),
      pageCount: count,
    }
    return () => {
      controlRef.current = null
    }
  }, [count, go, controlRef])

  useEffect(() => {
    const el = box.current
    if (!el) return
    const measure = () => setSize({ width: el.clientWidth, height: el.clientHeight })
    measure()
    const ro = new ResizeObserver(measure)
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  return (
    <div
      ref={box}
      className="absolute inset-0 flex items-center justify-center overflow-hidden"
      role="img"
      aria-label={label(index)}
      data-testid="paged-stage"
      onClick={(e) => {
        const rect = e.currentTarget.getBoundingClientRect()
        const zone = tapZone(e.clientX - rect.left, rect.width)
        if (zone === 'middle') onMiddleTap()
        else {
          onUserTurn()
          go(zone === 'next' ? index + 1 : index - 1)
        }
      }}
    >
      {size.width > 0 && render(index, size)}
    </div>
  )
}
