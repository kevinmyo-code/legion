import {
  ArrowLeft,
  BookOpen,
  Bookmark,
  List,
  Maximize,
  Minimize,
  Moon,
  SlidersHorizontal,
  Sun,
  Trash2,
  type LucideIcon,
} from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

import { BooksHttpError, convertPdf, type BookmarkRow, type Variant } from '@/api/books'
import { Button } from '@/components/ui/button'
import { Switch } from '@/components/ui/switch'
import {
  PALETTE,
  THEME_LABEL,
  THEMES,
  TEXT_SIZE_MAX,
  TEXT_SIZE_MIN,
  TEXT_SIZE_STEP,
  clampTextSize,
  loadSettings,
  nextTheme,
  saveSettings,
  warmOpacity,
  type ReaderSettings,
  type Theme,
} from '@/lib/books/comfort'
import { EpubError, openEpub, type OpenedEpub } from '@/lib/books/epub'
import { kv } from '@/lib/books/kv'
import { appliedSentence } from '@/lib/books/locator'
import type { ReadingSync } from '@/lib/books/reading'
import type { BookFile } from '@/lib/books/scan'
import { CBR_SENTENCE } from '@/lib/books/scan'
import { OFFLINE_SENTENCE, PositionRecorder, SYNC_UNREACHABLE_ON_OPEN, type Place } from '@/lib/books/sync'
import { cn } from '@/lib/utils'

import { ComicView } from './comic-view'
import { EpubView } from './epub-view'
import { PdfView } from './pdf-view'
import type { StartPoint, ViewControl } from './view-types'

/**
 * The reader (ticket 10): an overlay over the Books page, content first, the way
 * every reader studied does it (`docs/design/books.md`). The stage keeps one fixed
 * size whether the bars are shown or not, so hiding them never re-flows the text.
 */

export const CONVERTED_LINE = 'Converted from PDF. Figures and tables may be missing.'
export const CONVERT_UNREACHABLE =
  'Could not reach the server to make a resizable copy, so this PDF opens as the original.'

export interface ReaderBook {
  file: BookFile
  sha256: string
}

type Loaded =
  | { kind: 'epub'; opened: OpenedEpub; variant: Variant; fromPdf: boolean }
  | { kind: 'pdf'; bytes: Uint8Array; variant: 'original' }
  | { kind: 'cbz'; bytes: Uint8Array; variant: 'original' }

const THEME_ICON: Record<Theme, LucideIcon> = { day: Sun, sepia: BookOpen, night: Moon }

export function Reader({
  book,
  sync,
  onClose,
  convert = convertPdf,
}: {
  book: ReaderBook
  sync: ReadingSync
  onClose: () => void
  convert?: typeof convertPdf
}) {
  const [settings, setSettings] = useState<ReaderSettings>(() => loadSettings())
  const [loaded, setLoaded] = useState<Loaded | null>(null)
  const [start, setStart] = useState<StartPoint | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [convertNote, setConvertNote] = useState<string | null>(null)
  const [syncNote, setSyncNote] = useState<string | null>(null)
  const [pending, setPending] = useState(0)
  const [place, setPlace] = useState<Place | null>(null)
  const [chrome, setChrome] = useState(true)
  const [panel, setPanel] = useState<'contents' | 'bookmarks' | 'settings' | null>(null)
  const [fullscreen, setFullscreen] = useState(false)
  const [forceOriginal, setForceOriginal] = useState(false)
  const [bookmarks, setBookmarks] = useState<BookmarkRow[]>([])
  const [bookmarkNote, setBookmarkNote] = useState('')
  const root = useRef<HTMLDivElement>(null)
  const control = useRef<ViewControl | null>(null)
  const decision = useRef<{ from: 'remote' | 'local' | 'none'; device?: string }>({ from: 'none' })
  const recorderRef = useRef<PositionRecorder | null>(null)
  const armTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)

  const update = useCallback((patch: Partial<ReaderSettings>) => {
    setSettings((s) => {
      const next = { ...s, ...patch }
      saveSettings(next)
      return next
    })
  }, [])

  const variant: Variant = loaded?.variant ?? 'original'

  // ---- open the file, work out the variant and where to start ---------------
  useEffect(() => {
    let cancelled = false
    setLoaded(null)
    setStart(null)
    setError(null)
    setSyncNote(null)
    setPlace(null)
    void (async () => {
      try {
        if (book.file.format === 'cbr') throw new Error(CBR_SENTENCE)
        const file = await book.file.handle.getFile()
        const bytes = new Uint8Array(await file.arrayBuffer())
        let next: Loaded
        let note: string | null = null
        if (book.file.format === 'epub') {
          next = { kind: 'epub', opened: openEpub(bytes), variant: 'original', fromPdf: false }
        } else if (book.file.format === 'cbz') {
          next = { kind: 'cbz', bytes, variant: 'original' }
        } else if (forceOriginal) {
          next = { kind: 'pdf', bytes, variant: 'original' }
        } else {
          const cached = await kv().get<Uint8Array>('converted', book.sha256)
          let epub = cached
          if (!epub) {
            try {
              epub = (await convert(bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer)).epub
              await kv().set('converted', book.sha256, epub)
            } catch (err) {
              note = err instanceof BooksHttpError ? err.message : CONVERT_UNREACHABLE
            }
          }
          next = epub
            ? { kind: 'epub', opened: openEpub(epub), variant: 'resizable', fromPdf: true }
            : { kind: 'pdf', bytes, variant: 'original' }
        }
        const { decision: d, reachable } = await sync.decideStart(book.sha256, next.variant)
        if (cancelled) return
        let point: StartPoint = { kind: 'none' }
        if (d.from !== 'none') {
          const row = d.row
          const loc = row.locator
          point = loc
            ? { kind: 'exact', locator: loc, totalProgression: row.total_progression }
            : { kind: 'near', totalProgression: row.total_progression }
          decision.current = { from: d.from, device: row.device }
          // An exact locator whose resource is missing falls to near inside the view.
        } else decision.current = { from: 'none' }
        setConvertNote(note)
        setSyncNote(reachable ? null : SYNC_UNREACHABLE_ON_OPEN)
        setLoaded(next)
        setStart(point)
        void sync.pullBookmarks().then(async () => {
          if (!cancelled) setBookmarks(await sync.bookmarksFor(book.sha256, next.variant))
        })
        setPending((await sync.queue.pendingCount()) ?? 0)
      } catch (err) {
        if (cancelled) return
        setError(
          err instanceof EpubError || err instanceof Error
            ? err.message
            : 'This book could not be opened.',
        )
      }
    })()
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [book, forceOriginal])

  // ---- saving: only turns the person made, only once the opening jump settled ----
  useEffect(() => {
    if (!loaded) return
    const recorder = new PositionRecorder((p, at) => {
      void sync.savePosition(book.sha256, loaded.variant, p, at).then((r) => setPending(r.pending))
    })
    recorderRef.current = recorder
    const hide = () => {
      if (document.visibilityState === 'hidden') {
        recorder.flush()
        void sync.flush({ keepalive: true })
      }
    }
    document.addEventListener('visibilitychange', hide)
    window.addEventListener('pagehide', hide)
    const online = () => void sync.flush().then((r) => setPending(r.pending))
    window.addEventListener('online', online)
    return () => {
      document.removeEventListener('visibilitychange', hide)
      window.removeEventListener('pagehide', hide)
      window.removeEventListener('online', online)
      clearTimeout(armTimer.current)
      recorder.flush()
      recorderRef.current = null
    }
  }, [loaded, book.sha256, sync])

  const onReady = useCallback(
    (info: { appliedExact: boolean; pageCount?: number }) => {
      if (decision.current.from === 'remote') {
        setSyncNote(appliedSentence(info.appliedExact ? 'exact' : 'near', info.pageCount !== undefined, decision.current.device))
      }
      // The opening jump reports positions of its own; let those pass before listening.
      clearTimeout(armTimer.current)
      armTimer.current = setTimeout(() => recorderRef.current?.arm(), 600)
    },
    [],
  )

  const onPlace = useCallback((p: Place) => {
    setPlace(p)
    recorderRef.current?.moved(p)
  }, [])

  // ---- keyboard, fullscreen -------------------------------------------------
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement | null
      if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA')) return
      if (e.key === 'ArrowRight' || e.key === 'PageDown' || (e.key === ' ' && !e.shiftKey)) {
        recorderRef.current?.userTurn()
        control.current?.next()
        e.preventDefault()
      } else if (e.key === 'ArrowLeft' || e.key === 'PageUp' || (e.key === ' ' && e.shiftKey)) {
        recorderRef.current?.userTurn()
        control.current?.prev()
        e.preventDefault()
      } else if (e.key === 'Escape' && !document.fullscreenElement) {
        if (panel) setPanel(null)
        else onClose()
      }
    }
    const onFs = () => setFullscreen(document.fullscreenElement === root.current)
    window.addEventListener('keydown', onKey)
    document.addEventListener('fullscreenchange', onFs)
    return () => {
      window.removeEventListener('keydown', onKey)
      document.removeEventListener('fullscreenchange', onFs)
    }
  }, [onClose, panel])

  const toggleFullscreen = () => {
    if (document.fullscreenElement) void document.exitFullscreen()
    else void root.current?.requestFullscreen?.()
  }

  useEffect(() => {
    return () => {
      if (document.fullscreenElement) void document.exitFullscreen()
    }
  }, [])

  const palette = PALETTE[settings.theme]
  const isEpub = loaded?.kind === 'epub'
  const percent = place ? Math.round(place.totalProgression * 100) : null
  const ThemeIcon = THEME_ICON[settings.theme]

  const viewProps = useMemo(
    () => ({
      start: start ?? ({ kind: 'none' } as StartPoint),
      settings,
      controlRef: control,
      onReady,
      onPlace,
      onMiddleTap: () => setChrome((c) => !c),
      onUserTurn: () => recorderRef.current?.userTurn(),
      onError: setError,
    }),
    [start, settings, onReady, onPlace],
  )

  const addBookmark = async () => {
    const here = control.current?.current()
    if (!here?.locator) return
    await sync.addBookmark({
      sha256: book.sha256,
      variant,
      locator: here.locator,
      totalProgression: here.totalProgression,
      label: here.chapterLabel ?? (percent !== null ? `${percent}%` : null),
      note: bookmarkNote.trim() || null,
    })
    setBookmarkNote('')
    setBookmarks(await sync.bookmarksFor(book.sha256, variant))
    setPending(await sync.queue.pendingCount())
  }

  const removeBookmark = async (id: string) => {
    await sync.removeBookmark(id)
    setBookmarks(await sync.bookmarksFor(book.sha256, variant))
    setPending(await sync.queue.pendingCount())
  }

  const barClass = cn(
    'absolute inset-x-0 z-10 flex items-center gap-1 px-2 transition-opacity',
    chrome ? 'opacity-100' : 'pointer-events-none opacity-0',
  )

  return (
    <div
      ref={root}
      role="dialog"
      aria-label={`Reading ${book.file.name}`}
      className="fixed inset-0 z-50 flex flex-col"
      style={{ background: palette.background, color: palette.text }}
    >
      <div className={cn(barClass, 'top-0 h-12 border-b')} style={{ borderColor: `${palette.text}22`, background: palette.background }}>
        <ReaderButton label="Back to the library" onClick={onClose} icon={ArrowLeft} />
        <div className="min-w-0 flex-1 px-2 text-center text-[0.9375rem]">
          <span className="block truncate font-medium">{loaded?.kind === 'epub' ? loaded.opened.title : book.file.name}</span>
        </div>
        {isEpub && <ReaderButton label="Contents" onClick={() => setPanel(panel === 'contents' ? null : 'contents')} icon={List} />}
        <ReaderButton label="Bookmarks" onClick={() => setPanel(panel === 'bookmarks' ? null : 'bookmarks')} icon={Bookmark} />
        <ReaderButton
          label={`Theme: ${THEME_LABEL[settings.theme]}. Switch to ${THEME_LABEL[nextTheme(settings.theme)]}`}
          onClick={() => update({ theme: nextTheme(settings.theme) })}
          icon={ThemeIcon}
        />
        <ReaderButton label="Reading settings" onClick={() => setPanel(panel === 'settings' ? null : 'settings')} icon={SlidersHorizontal} />
        <ReaderButton label={fullscreen ? 'Leave full screen' : 'Full screen'} onClick={toggleFullscreen} icon={fullscreen ? Minimize : Maximize} />
      </div>

      <div className="absolute inset-x-0 top-12 bottom-10">
        {error ? (
          <p role="alert" className="mx-auto mt-16 max-w-md rounded-control bg-destructive-container px-4 py-3 text-[0.9375rem] text-destructive-container-foreground">
            {error}
          </p>
        ) : !loaded || !start ? (
          <p role="status" className="mt-16 text-center text-[0.9375rem] opacity-70">
            {book.file.format === 'pdf' && !forceOriginal ? 'Opening. A PDF is turned into a resizable copy the first time.' : 'Opening.'}
          </p>
        ) : loaded.kind === 'epub' ? (
          <EpubView key={`${book.sha256}-${loaded.variant}`} opened={loaded.opened} {...viewProps} />
        ) : loaded.kind === 'pdf' ? (
          <PdfView key={`${book.sha256}-pdf`} bytes={loaded.bytes} {...viewProps} />
        ) : (
          <ComicView key={`${book.sha256}-cbz`} bytes={loaded.bytes} {...viewProps} />
        )}
      {settings.warmOn && settings.warmStrength > 0 && (
        <div
          aria-hidden="true"
          data-testid="warm-filter"
          className="pointer-events-none absolute inset-0 z-[5]"
          style={{ background: '#ff9a2e', mixBlendMode: 'multiply', opacity: warmOpacity(settings.warmStrength) }}
        />
      )}
      </div>

      <div
        className={cn(barClass, 'bottom-0 h-10 justify-between border-t text-[0.8125rem]')}
        style={{ borderColor: `${palette.text}22`, background: palette.background }}
      >
        <span className="min-w-0 truncate px-2" data-testid="reader-place">
          {place?.chapterLabel ? `${place.chapterLabel} · ` : ''}
          {percent !== null ? `${percent}%` : ''}
        </span>
        <span className="flex min-w-0 items-center gap-3 px-2">
          {pending > 0 && <span role="status">{OFFLINE_SENTENCE}</span>}
        </span>
      </div>

      <div className="pointer-events-none absolute inset-x-0 bottom-12 z-20 flex flex-col items-center gap-2 px-4">
        {(syncNote || convertNote || (loaded?.kind === 'epub' && loaded.fromPdf)) && (
          <div role="status" className="pointer-events-auto flex max-w-xl flex-col gap-1 rounded-control px-4 py-2 text-[0.8125rem] shadow-md" style={{ background: palette.text, color: palette.background }}>
            {syncNote && <span data-testid="sync-note">{syncNote}</span>}
            {convertNote && <span>{convertNote}</span>}
            {loaded?.kind === 'epub' && loaded.fromPdf && (
              <span className="flex items-center gap-3">
                {CONVERTED_LINE}
                <button type="button" className="underline" onClick={() => setForceOriginal(true)}>
                  Open original
                </button>
              </span>
            )}
          </div>
        )}
        {loaded?.kind === 'pdf' && book.file.format === 'pdf' && forceOriginal && (
          <button type="button" className="pointer-events-auto rounded-full px-3 py-1 text-[0.8125rem] underline" style={{ background: palette.background }} onClick={() => setForceOriginal(false)}>
            Open resizable copy
          </button>
        )}
      </div>

      {panel && (
        <aside
          aria-label={panel === 'contents' ? 'Contents' : panel === 'bookmarks' ? 'Bookmarks' : 'Reading settings'}
          className="absolute top-12 right-0 bottom-10 z-30 w-full max-w-sm overflow-y-auto border-l p-4 text-[0.9375rem] shadow-lg"
          style={{ background: palette.background, borderColor: `${palette.text}22` }}
        >
          {panel === 'contents' && loaded?.kind === 'epub' && (
            <ul className="flex flex-col">
              {loaded.opened.toc.length === 0 && <li className="opacity-70">This book has no table of contents.</li>}
              {loaded.opened.toc.map((t, i) => (
                <li key={`${t.href}-${i}`} style={{ paddingLeft: t.depth * 16 }}>
                  <button
                    type="button"
                    className="w-full rounded-md px-2 py-2 text-left hover:bg-black/5"
                    onClick={() => {
                      control.current?.goTo({ href: t.href.split('#')[0], type: 'application/xhtml+xml', locations: { progression: 0 } })
                      setPanel(null)
                    }}
                  >
                    {t.title}
                  </button>
                </li>
              ))}
            </ul>
          )}
          {panel === 'bookmarks' && (
            <div className="flex flex-col gap-3">
              <label className="flex flex-col gap-1">
                <span className="text-[0.8125rem] opacity-70">Note (optional)</span>
                <input
                  value={bookmarkNote}
                  onChange={(e) => setBookmarkNote(e.target.value)}
                  className="h-10 rounded-control border bg-transparent px-3"
                  style={{ borderColor: `${palette.text}55` }}
                />
              </label>
              <Button onClick={() => void addBookmark()}>Bookmark this page</Button>
              {bookmarks.length === 0 ? (
                <p className="opacity-70">No bookmarks in this book yet.</p>
              ) : (
                <ul className="flex flex-col">
                  {bookmarks.map((b) => (
                    <li key={b.id} className="flex items-center gap-1">
                      <button
                        type="button"
                        className="min-w-0 flex-1 rounded-md px-2 py-2 text-left hover:bg-black/5"
                        onClick={() => {
                          control.current?.goTo(b.locator)
                          setPanel(null)
                        }}
                      >
                        <span className="block truncate">{b.label ?? `${Math.round(b.total_progression * 100)}%`}</span>
                        {b.note && <span className="block truncate text-[0.8125rem] opacity-70">{b.note}</span>}
                      </button>
                      <ReaderButton label={`Delete bookmark ${b.label ?? ''}`.trim()} onClick={() => void removeBookmark(b.id)} icon={Trash2} />
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}
          {panel === 'settings' && (
            <div className="flex flex-col gap-5">
              <fieldset className="flex flex-col gap-2">
                <legend className="mb-1 text-[0.8125rem] opacity-70">Theme</legend>
                <div className="flex gap-2">
                  {THEMES.map((t) => (
                    <button
                      key={t}
                      type="button"
                      aria-pressed={settings.theme === t}
                      onClick={() => update({ theme: t })}
                      className={cn('h-10 flex-1 rounded-full border text-sm', settings.theme === t && 'border-2 font-medium')}
                      style={{ background: PALETTE[t].background, color: PALETTE[t].text, borderColor: settings.theme === t ? '#5b5fc7' : '#8884' }}
                    >
                      {THEME_LABEL[t]}
                    </button>
                  ))}
                </div>
                {loaded?.kind === 'cbz' && <p className="text-[0.8125rem] opacity-70">Comics are never recoloured; the warm filter still applies.</p>}
              </fieldset>
              <div className="flex flex-col gap-2">
                <div className="flex items-center justify-between">
                  <label htmlFor="warm-switch">Warm filter</label>
                  <Switch id="warm-switch" checked={settings.warmOn} onCheckedChange={(on) => update({ warmOn: on })} />
                </div>
                <label className="flex flex-col gap-1 text-[0.8125rem]">
                  <span className="opacity-70">Strength {settings.warmStrength}%</span>
                  <input
                    type="range"
                    min={0}
                    max={100}
                    value={settings.warmStrength}
                    onChange={(e) => update({ warmStrength: Number(e.target.value) })}
                    aria-label="Warm filter strength"
                    className="accent-[#5b5fc7]"
                  />
                </label>
              </div>
              {isEpub ? (
                <div className="flex items-center justify-between">
                  <span>Text size {settings.textSize}%</span>
                  <span className="flex gap-2">
                    <SizeButton label="Smaller text" disabled={settings.textSize <= TEXT_SIZE_MIN} onClick={() => update({ textSize: clampTextSize(settings.textSize - TEXT_SIZE_STEP) })} color={palette.text}>
                      A-
                    </SizeButton>
                    <SizeButton label="Larger text" disabled={settings.textSize >= TEXT_SIZE_MAX} onClick={() => update({ textSize: clampTextSize(settings.textSize + TEXT_SIZE_STEP) })} color={palette.text}>
                      A+
                    </SizeButton>
                  </span>
                </div>
              ) : (
                <p className="text-[0.8125rem] opacity-70">
                  {loaded?.kind === 'pdf'
                    ? 'This PDF is shown as the original, so its text cannot be resized. Pages are fit to the window.'
                    : 'Comic pages are fit to the window.'}
                </p>
              )}
            </div>
          )}
        </aside>
      )}

    </div>
  )
}

function SizeButton({ label, disabled, onClick, color, children }: { label: string; disabled: boolean; onClick: () => void; color: string; children: string }) {
  return (
    <button
      type="button"
      aria-label={label}
      disabled={disabled}
      onClick={onClick}
      className="inline-flex size-10 items-center justify-center rounded-full border text-sm disabled:opacity-40"
      style={{ borderColor: `${color}66`, color }}
    >
      {children}
    </button>
  )
}

function ReaderButton({ label, onClick, icon: Icon }: { label: string; onClick: () => void; icon: LucideIcon }) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      onClick={onClick}
      className="inline-flex size-10 shrink-0 items-center justify-center rounded-full outline-none hover:bg-black/10 focus-visible:outline-2 focus-visible:outline-offset-0 focus-visible:outline-current"
    >
      <Icon className="size-5" aria-hidden="true" />
    </button>
  )
}

