import { EpubNavigator } from '@readium/navigator'
import { Locator } from '@readium/shared'
import { useEffect, useRef } from 'react'

import { PALETTE, type ReaderSettings } from '@/lib/books/comfort'
import { type OpenedEpub } from '@/lib/books/epub'
import { resolveEpub } from '@/lib/books/locator'

import { tapZone, type ViewControl, type ViewProps } from './view-types'

/**
 * EPUB through Readium's TypeScript navigator, so a locator saved here is the same
 * Readium Locator JSON the phone's Readium Kotlin emits (ticket 07).
 */

export function epubPreferences(settings: ReaderSettings) {
  const palette = PALETTE[settings.theme]
  return {
    fontSize: settings.textSize / 100,
    backgroundColor: palette.background,
    textColor: palette.text,
    linkColor: palette.link,
    visitedColor: palette.link,
    columnCount: 1,
    pageGutter: 24,
    optimalLineLength: 70,
  }
}

function placeOf(opened: OpenedEpub, locator: Locator) {
  const json = locator.serialize() as Record<string, unknown>
  const href = locator.href.split('#')[0]
  return {
    locator: json,
    totalProgression: locator.locations.totalProgression ?? 0,
    chapterLabel: locator.title ?? opened.chapterByHref.get(href) ?? null,
  }
}

/** The stored position closest to a 0-1 fraction of the whole book. */
function nearestPosition(positions: Locator[], fraction: number): Locator {
  let best = positions[0]
  for (const p of positions) {
    if ((p.locations.totalProgression ?? 0) <= fraction) best = p
    else break
  }
  return best
}

export function EpubView({
  opened,
  start,
  settings,
  controlRef,
  onReady,
  onPlace,
  onMiddleTap,
  onUserTurn,
  onError,
}: ViewProps & { opened: OpenedEpub }) {
  const host = useRef<HTMLDivElement>(null)
  const nav = useRef<EpubNavigator | null>(null)
  // The callbacks and settings change every render; the navigator is built once.
  const live = useRef({ onReady, onPlace, onMiddleTap, onUserTurn, onError, settings })
  live.current = { onReady, onPlace, onMiddleTap, onUserTurn, onError, settings }

  useEffect(() => {
    const container = host.current
    if (!container) return
    let destroyed = false

    let initial: Locator | undefined
    let appliedExact = start.kind === 'none'
    if (start.kind === 'exact') {
      const resolved = resolveEpub(
        { locator: start.locator, total_progression: start.totalProgression },
        opened.publication.readingOrder.items.map((l) => l.href),
      )
      if (resolved.kind === 'exact') {
        initial = Locator.deserialize(resolved.locator)
        appliedExact = !!initial
      }
      if (!initial) {
        appliedExact = false
        initial = nearestPosition(
          opened.positions,
          resolved.kind === 'near' ? resolved.totalProgression : start.totalProgression,
        )
      }
    } else if (start.kind === 'near') {
      initial = nearestPosition(opened.positions, start.totalProgression)
    }

    const navigator = new EpubNavigator(
      container,
      opened.publication,
      {
        positionChanged: (locator) => live.current.onPlace(placeOf(opened, locator)),
        tap: (e) => {
          const zone = tapZone(e.x, container.clientWidth)
          if (zone === 'middle') live.current.onMiddleTap()
          else {
            live.current.onUserTurn()
            if (zone === 'next') navigator.goForward(false, () => {})
            else navigator.goBackward(false, () => {})
          }
          return true
        },
        click: () => false,
        // Keys pressed while focus is inside the book's own frame never reach the page, so
        // they are handed up: the reader shell owns the arrows, space and Escape.
        frameLoaded: (frameWindow) => {
          frameWindow.addEventListener('keydown', (e) => {
            if (!['ArrowLeft', 'ArrowRight', 'PageUp', 'PageDown', ' ', 'Escape'].includes(e.key)) return
            e.preventDefault()
            window.dispatchEvent(new KeyboardEvent('keydown', { key: e.key, shiftKey: e.shiftKey, bubbles: true }))
          })
        },
        viewportChanged: () => {},
        timelineItemChanged: () => {},
        zoom: () => {},
        miscPointer: () => {},
        scroll: () => {},
        customEvent: () => {},
        handleLocator: () => false,
        textSelected: () => {},
        contentProtection: () => {},
        contextMenu: () => {},
        peripheral: () => {},
      },
      opened.positions,
      initial,
      { preferences: epubPreferences(settings), defaults: {} },
    )
    nav.current = navigator

    const control: ViewControl = {
      next: () => navigator.goForward(false, () => {}),
      prev: () => navigator.goBackward(false, () => {}),
      goTo: (json) => {
        const target = Locator.deserialize(json)
        if (target) navigator.go(target, false, () => {})
      },
      current: () => {
        try {
          return placeOf(opened, navigator.currentLocator)
        } catch {
          return undefined
        }
      },
    }
    controlRef.current = control

    navigator
      .load()
      .then(() => {
        if (!destroyed) live.current.onReady({ appliedExact })
      })
      .catch(() => {
        if (!destroyed) live.current.onError('This book could not be drawn. The file may be damaged.')
      })

    return () => {
      destroyed = true
      controlRef.current = null
      nav.current = null
      navigator.destroy()
    }
    // The navigator is built once per opened book and start point.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [opened, start])

  useEffect(() => {
    void nav.current?.submitPreferences(epubPreferences(settings) as never)
  }, [settings.theme, settings.textSize])

  return <div ref={host} className="readium-host relative mx-auto h-full" data-testid="epub-host" />
}
