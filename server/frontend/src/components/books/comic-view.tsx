import { unzipSync } from 'fflate'
import { useEffect, useMemo, useState } from 'react'

import { comicPageNames } from '@/lib/books/scan'

import { PagedStage } from './paged-view'
import type { ViewProps } from './view-types'

function mimeOf(name: string): string {
  const ext = name.slice(name.lastIndexOf('.') + 1).toLowerCase()
  return ext === 'jpg' ? 'image/jpeg' : `image/${ext}`
}

/** A CBZ: a zip of images in natural order. Never recoloured, whatever the theme (art). */
export function ComicView({ bytes, start, controlRef, onReady, onPlace, onMiddleTap, onUserTurn, onError }: ViewProps & { bytes: Uint8Array }) {
  const [pages, setPages] = useState<string[]>([])

  const entries = useMemo(() => {
    try {
      return unzipSync(bytes)
    } catch {
      return undefined
    }
  }, [bytes])

  useEffect(() => {
    if (!entries) {
      onError('This file is not a readable CBZ (it is not a valid zip archive).')
      return
    }
    const names = comicPageNames(Object.keys(entries))
    if (names.length === 0) {
      onError('This CBZ has no page images in it.')
      return
    }
    const urls = names.map((n) => URL.createObjectURL(new Blob([entries[n].slice()], { type: mimeOf(n) })))
    setPages(urls)
    return () => urls.forEach((u) => URL.revokeObjectURL(u))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [entries])

  if (pages.length === 0) return null
  return (
    <PagedStage
      count={pages.length}
      start={start}
      controlRef={controlRef}
      onReady={onReady}
      onPlace={onPlace}
      onMiddleTap={onMiddleTap}
      onUserTurn={onUserTurn}
      label={(i) => `Page ${i + 1} of ${pages.length}`}
      render={(index) => (
        <img src={pages[index]} alt={`Page ${index + 1} of ${pages.length}`} className="max-h-full max-w-full object-contain" />
      )}
    />
  )
}
