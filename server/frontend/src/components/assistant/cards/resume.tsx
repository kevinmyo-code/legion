import { Download, FileText } from 'lucide-react'
import { useState } from 'react'

import { FitView, type Size } from './fit-view'
import { usePresenter } from './presenter-context'
import type { ResumeCard } from './types'

/**
 * Kevin's one-page resume (`show_resume`, demo-modals: Kevin 2026-11-05, "asking for the resume
 * should pop up a modal thats scrollable showing my 1 page resume"). Opens over the page by
 * itself; in the chat it is a line that opens it again.
 *
 * The page is an image, not the PDF: Android Chrome draws no PDF inside a page. It is fitted
 * to the width and scrolls down; zoom makes the small type readable on a phone. The PDF is the
 * download, the same file the page's own link serves.
 */

export const RESUME_DOWNLOAD_NAME = 'Kevin-Win-Resume.pdf'

/** The engine's words for the page image (`demo/tools.py` RESUME_ALT), for the page's own link. */
export const RESUME_ALT =
  "Kevin's one-page resume, as an image of the page. Download the PDF for the same page with selectable text."

export function ResumeView({ card }: { card: ResumeCard }) {
  const presenter = usePresenter()
  return (
    <div data-card="resume" className="flex flex-wrap items-center gap-2">
      <button
        type="button"
        onClick={() => presenter?.show(card)}
        disabled={!presenter}
        className="flex min-h-11 items-center gap-2 rounded-full border border-outline-variant px-4 text-sm"
      >
        <FileText className="size-4" aria-hidden="true" />
        Open the resume
      </button>
      <DownloadPdf href={card.pdf} />
    </div>
  )
}

export function DownloadPdf({ href, className = '' }: { href: string; className?: string }) {
  // `download`: the browser keeps the page and saves the file. A phone ends the conversation when
  // the page is hidden (`AssistantProvider` endOnHidden), so this must never open a new tab.
  return (
    <a
      href={href}
      download={RESUME_DOWNLOAD_NAME}
      data-modal="download"
      className={`inline-flex min-h-11 items-center gap-2 rounded-full bg-primary px-4 text-sm font-medium text-primary-foreground no-underline ${className}`}
    >
      <Download className="size-4" aria-hidden="true" />
      Download PDF
    </a>
  )
}

export function ResumeBody({ card }: { card: ResumeCard }) {
  const [natural, setNatural] = useState<Size | null>(null)
  const [broken, setBroken] = useState(false)
  if (!card.image || broken) {
    return (
      <div className="flex min-h-0 flex-1 flex-col items-center justify-center gap-3 text-center">
        <p className="max-w-[32ch] text-[0.9375rem]">
          {broken
            ? "The resume's page could not be shown here. The PDF has the same page."
            : 'A picture of the page is not loaded here. The PDF has the resume.'}
        </p>
        <DownloadPdf href={card.pdf} />
      </div>
    )
  }
  return (
    <div className="flex min-h-0 flex-1 flex-col">
      {/* Loaded once, off screen, for its size: the fitted copy below needs it to lay out. */}
      {!natural && (
        <img
          src={card.image}
          alt=""
          aria-hidden="true"
          className="pointer-events-none absolute size-px opacity-0"
          onLoad={(e) => setNatural({ width: e.currentTarget.naturalWidth, height: e.currentTarget.naturalHeight })}
          onError={() => setBroken(true)}
        />
      )}
      <div className="flex min-h-0 flex-1 flex-col rounded-md border border-outline-variant bg-white p-1">
        <FitView natural={natural} fit="width" label={card.title} resetKey={card.image} maxWidth={880}>
          {(size) => (
            <img
              src={card.image ?? undefined}
              alt={card.alt}
              data-modal="resume-page"
              width={Math.round(size.width)}
              height={Math.round(size.height)}
              style={{ width: size.width, height: size.height, maxWidth: 'none' }}
              draggable={false}
            />
          )}
        </FitView>
      </div>
    </div>
  )
}
