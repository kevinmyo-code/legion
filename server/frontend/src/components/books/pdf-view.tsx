import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url'
import { useEffect, useRef, useState } from 'react'

import { pageFilter } from '@/lib/books/comfort'

import { PagedStage } from './paged-view'
import type { ViewProps } from './view-types'

type PdfDoc = import('pdfjs-dist').PDFDocumentProxy

/** The original PDF, drawn by pdf.js one page at a time. Night inverts lightness; sepia tints. */
export function PdfView({
  bytes,
  start,
  settings,
  controlRef,
  onReady,
  onPlace,
  onMiddleTap,
  onUserTurn,
  onError,
}: ViewProps & { bytes: Uint8Array }) {
  const [doc, setDoc] = useState<PdfDoc | null>(null)

  useEffect(() => {
    let cancelled = false
    let task: { destroy(): Promise<void> } | undefined
    void (async () => {
      try {
        const pdfjs = await import('pdfjs-dist')
        pdfjs.GlobalWorkerOptions.workerSrc = workerUrl
        // pdf.js transfers the buffer to its worker; hand it a copy so a re-open still has the bytes.
        const opening = pdfjs.getDocument({ data: bytes.slice() })
        task = opening
        const loaded = await opening.promise
        if (!cancelled) setDoc(loaded)
      } catch {
        if (!cancelled) onError('This PDF could not be read. The file may be damaged or password-protected.')
      }
    })()
    return () => {
      cancelled = true
      void task?.destroy()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [bytes])

  if (!doc) return null
  return (
    <PagedStage
      count={doc.numPages}
      start={start}
      controlRef={controlRef}
      onReady={onReady}
      onPlace={onPlace}
      onMiddleTap={onMiddleTap}
      onUserTurn={onUserTurn}
      label={(i) => `Page ${i + 1} of ${doc.numPages}`}
      render={(index, size) => (
        <PdfPage doc={doc} index={index} size={size} filter={pageFilter(settings.theme, 'pdf')} />
      )}
    />
  )
}

function PdfPage({
  doc,
  index,
  size,
  filter,
}: {
  doc: PdfDoc
  index: number
  size: { width: number; height: number }
  filter: string
}) {
  const canvas = useRef<HTMLCanvasElement>(null)
  useEffect(() => {
    let task: { cancel(): void; promise: Promise<unknown> } | undefined
    let stale = false
    void (async () => {
      const page = await doc.getPage(index + 1)
      if (stale || !canvas.current) return
      const base = page.getViewport({ scale: 1 })
      const fit = Math.min(size.width / base.width, size.height / base.height)
      const ratio = window.devicePixelRatio || 1
      const viewport = page.getViewport({ scale: fit * ratio })
      const el = canvas.current
      el.width = Math.floor(viewport.width)
      el.height = Math.floor(viewport.height)
      el.style.width = `${Math.floor(viewport.width / ratio)}px`
      el.style.height = `${Math.floor(viewport.height / ratio)}px`
      task = page.render({ canvas: el, viewport })
      await task.promise.catch(() => {})
    })()
    return () => {
      stale = true
      task?.cancel()
    }
  }, [doc, index, size.width, size.height])

  return <canvas ref={canvas} style={{ filter }} className="max-h-full max-w-full bg-white shadow-sm" />
}
