import { BookOpen, FolderOpen, RefreshCw } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

import type { PositionRow } from '@/api/books'
import { Reader, type ReaderBook } from '@/components/books/reader'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { EmptySentence, ErrorSentence, PageHeader, Panel } from '@/components/workbench/page'
import { readingSync } from '@/lib/books/engine'
import { HashCache } from '@/lib/books/hash'
import { kv } from '@/lib/books/kv'
import {
  CBR_SENTENCE,
  NO_FOLDER_ACCESS,
  canPickFiles,
  canPickFolder,
  pickFiles,
  pickFolder,
  scanFolder,
  titleOf,
  type BookFile,
  type DirHandleLike,
} from '@/lib/books/scan'

/**
 * `/books` (ticket 10, ADR 0061 amended): read EPUB, PDF and comic files from a
 * folder on this computer, with the place and bookmarks synced through the engine.
 *
 * The files are read in the browser and never uploaded, with one exception said
 * here in words: a PDF is sent to the household's server once to make its
 * resizable copy (ticket 04), and nothing is kept there.
 */

export const BOOKS_COPY = {
  subtitle:
    'Read EPUB, PDF and comic files from a folder on this computer. Your place and bookmarks sync with your phone. The files stay in this browser.',
  pdfDisclosure:
    'A PDF is sent to your household server once so it can be refit to the window; the server keeps nothing. The resizable copy is saved in this browser.',
  noFolder: 'No folder chosen yet. Pick the folder that holds your books, or the one your phone copies them from.',
  emptyFolder: (name: string) => `There are no EPUB, PDF or CBZ files in ${name}.`,
  needsAccess: (name: string) => `This browser needs your permission to read ${name} again.`,
  syncUnreachable: 'Could not reach the server, so the progress shown is only what this browser knows.',
} as const

type Status =
  | { kind: 'loading' }
  | { kind: 'ready'; folder: string | null }
  | { kind: 'needs-access'; handle: DirHandleLike }
  | { kind: 'error'; message: string }

interface Item extends BookFile {
  size?: number
  sha256?: string
}

function ago(iso: string): string {
  const ms = Date.now() - Date.parse(iso)
  const days = Math.floor(ms / 86_400_000)
  if (days >= 1) return days === 1 ? 'yesterday' : `${days} days ago`
  const hours = Math.floor(ms / 3_600_000)
  if (hours >= 1) return `${hours} hour${hours === 1 ? '' : 's'} ago`
  return 'just now'
}

/** "43% on your phone, yesterday" from the newest known position for a file's hash. */
export function progressLabel(rows: PositionRow[], sha256: string | undefined): string | null {
  if (!sha256) return null
  const mine = rows.filter((r) => r.book_sha256 === sha256)
  if (mine.length === 0) return null
  const row = mine.reduce((a, b) => (Date.parse(b.recorded_at) > Date.parse(a.recorded_at) ? b : a))
  const where = row.device === 'web' ? 'on the web' : 'on your phone'
  return `${Math.round(row.total_progression * 100)}% ${where}, ${ago(row.recorded_at)}`
}

export function BooksScreen() {
  const sync = readingSync()
  const hashes = useMemo(() => new HashCache(kv()), [])
  const [status, setStatus] = useState<Status>({ kind: 'loading' })
  const [items, setItems] = useState<Item[]>([])
  const [checking, setChecking] = useState<{ done: number; total: number } | null>(null)
  const [positions, setPositions] = useState<PositionRow[]>([])
  const [reachable, setReachable] = useState(true)
  const [open, setOpen] = useState<ReaderBook | null>(null)
  const [openError, setOpenError] = useState<string | null>(null)
  const folder = useRef<DirHandleLike | null>(null)

  const refreshPositions = useCallback(async () => {
    const [remote, local] = await Promise.all([sync.allRemote(), sync.allLocal()])
    setReachable(remote !== null)
    setPositions([...(remote ?? []), ...local])
  }, [sync])

  const scan = useCallback(
    async (handle: DirHandleLike) => {
      folder.current = handle
      const files = await scanFolder(handle)
      setItems(files)
      setStatus({ kind: 'ready', folder: handle.name })
      void refreshPositions()
      // Hash smallest first so the list names its first books quickly; cached after the first run.
      const sized = await Promise.all(files.map(async (f) => ({ f, file: await f.handle.getFile() })))
      sized.sort((a, b) => a.file.size - b.file.size)
      setChecking({ done: 0, total: sized.length })
      let done = 0
      for (const { f, file } of sized) {
        if (f.format !== 'cbr') {
          const { sha256 } = await hashes.hash(file)
          setItems((prev) => prev.map((p) => (p.path === f.path ? { ...p, sha256, size: file.size } : p)))
        }
        done += 1
        setChecking({ done, total: sized.length })
      }
      setChecking(null)
      void refreshPositions()
    },
    [hashes, refreshPositions],
  )

  // On arrival: the remembered folder, if there is one and it may still be read.
  useEffect(() => {
    let cancelled = false
    void (async () => {
      try {
        const handle = await kv().get<DirHandleLike>('handles', 'folder')
        if (cancelled) return
        if (!handle) {
          setStatus({ kind: 'ready', folder: null })
          void refreshPositions()
          return
        }
        const permission = (await handle.queryPermission?.({ mode: 'read' })) ?? 'granted'
        if (cancelled) return
        if (permission === 'granted') await scan(handle)
        else setStatus({ kind: 'needs-access', handle })
      } catch {
        if (!cancelled) setStatus({ kind: 'ready', folder: null })
      }
    })()
    return () => {
      cancelled = true
    }
    // Once, on arrival.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const choose = async () => {
    try {
      const handle = await pickFolder()
      // A handle that cannot be remembered still reads this session; it only costs a re-pick next visit.
      await kv().set('handles', 'folder', handle).catch(() => {})
      await scan(handle)
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') return
      setStatus({ kind: 'error', message: err instanceof Error ? err.message : NO_FOLDER_ACCESS })
    }
  }

  const allowAgain = async (handle: DirHandleLike) => {
    const result = (await handle.requestPermission?.({ mode: 'read' })) ?? 'granted'
    if (result === 'granted') await scan(handle)
    else setStatus({ kind: 'error', message: `Access to ${handle.name} was not granted, so its books cannot be listed.` })
  }

  const chooseFiles = async () => {
    try {
      const handles = await pickFiles()
      const files: Item[] = handles
        .map((h) => ({ path: h.name, name: h.name, handle: h, format: (h.name.split('.').pop() ?? '').toLowerCase() as BookFile['format'] }))
        .filter((f) => ['epub', 'pdf', 'cbz', 'cbr'].includes(f.format))
      setItems(files)
      setStatus({ kind: 'ready', folder: null })
      void refreshPositions()
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') return
      setStatus({ kind: 'error', message: err instanceof Error ? err.message : NO_FOLDER_ACCESS })
    }
  }

  const openBook = async (item: Item) => {
    setOpenError(null)
    try {
      const file = await item.handle.getFile()
      const { sha256 } = await hashes.hash(file)
      setOpen({ file: item, sha256 })
    } catch {
      setOpenError(`${item.name} could not be read. It may have been moved or deleted.`)
    }
  }

  const closeReader = () => {
    setOpen(null)
    void refreshPositions()
  }

  return (
    <div className="mx-auto flex w-full max-w-3xl flex-col gap-4 pb-16">
      <PageHeader title="Books" subtitle={BOOKS_COPY.subtitle} />

      <Panel
        title="Folder"
        description={BOOKS_COPY.pdfDisclosure}
        action={
          status.kind === 'ready' && (
            <>
              {folder.current && (
                <Button variant="outline" size="sm" onClick={() => void scan(folder.current!)}>
                  <RefreshCw aria-hidden="true" />
                  Rescan
                </Button>
              )}
              {canPickFolder() && (
                <Button variant="secondary" size="sm" onClick={() => void choose()}>
                  <FolderOpen aria-hidden="true" />
                  {status.folder ? 'Change folder' : 'Choose folder'}
                </Button>
              )}
            </>
          )
        }
      >
        {status.kind === 'loading' && <Skeleton className="h-12 rounded-control" />}
        {status.kind === 'error' && <ErrorSentence>{status.message}</ErrorSentence>}
        {status.kind === 'needs-access' && (
          <div className="flex flex-wrap items-center gap-3">
            <p className="text-[0.9375rem]">{BOOKS_COPY.needsAccess(status.handle.name)}</p>
            <Button size="sm" onClick={() => void allowAgain(status.handle)}>
              Allow access
            </Button>
          </div>
        )}
        {status.kind === 'ready' && (
          <div className="flex flex-col gap-3">
            {status.folder ? (
              <p className="text-[0.9375rem]">
                Reading from <span className="font-medium">{status.folder}</span>
                {checking && (
                  <span className="ml-2 text-muted-foreground" role="status">
                    Checking files, {checking.done} of {checking.total}
                  </span>
                )}
              </p>
            ) : (
              <p className="text-[0.9375rem] text-muted-foreground">
                {canPickFolder() ? BOOKS_COPY.noFolder : NO_FOLDER_ACCESS}
              </p>
            )}
            {!canPickFolder() && canPickFiles() && (
              <Button variant="secondary" className="self-start" onClick={() => void chooseFiles()}>
                Pick files
              </Button>
            )}
            {!canPickFolder() && !canPickFiles() && (
              <ErrorSentence role="status">
                This browser cannot read local files at all, so books cannot be opened here.
              </ErrorSentence>
            )}
            {canPickFolder() && !status.folder && (
              <Button className="self-start" onClick={() => void choose()}>
                <FolderOpen aria-hidden="true" />
                Choose folder
              </Button>
            )}
          </div>
        )}
      </Panel>

      {!reachable && status.kind === 'ready' && <ErrorSentence role="status">{BOOKS_COPY.syncUnreachable}</ErrorSentence>}
      {openError && <ErrorSentence>{openError}</ErrorSentence>}

      {status.kind === 'ready' && status.folder && items.length === 0 && !checking && (
        <EmptySentence>{BOOKS_COPY.emptyFolder(status.folder)}</EmptySentence>
      )}

      {items.length > 0 && (
        <Panel title="Library" description={`${items.length} ${items.length === 1 ? 'book' : 'books'}`}>
          <ul className="flex flex-col gap-1">
            {items.map((item) => {
              const label = progressLabel(positions, item.sha256)
              const readable = item.format !== 'cbr'
              return (
                <li key={item.path}>
                  <button
                    type="button"
                    disabled={!readable}
                    onClick={() => void openBook(item)}
                    className="flex min-h-14 w-full items-center gap-3 rounded-control px-3 py-2 text-left transition-colors hover:bg-surface-2 focus-visible:outline-2 focus-visible:outline-primary disabled:opacity-60"
                  >
                    <BookOpen className="size-5 shrink-0 text-muted-foreground" aria-hidden="true" />
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-[0.9375rem]">{titleOf(item.name)}</span>
                      <span className="block truncate text-[0.8125rem] text-muted-foreground">
                        {!readable ? CBR_SENTENCE : (label ?? (item.sha256 ? 'Not started' : 'Checking the file'))}
                      </span>
                    </span>
                    <span className="shrink-0 rounded-full bg-primary-container px-2.5 py-0.5 text-[0.75rem] font-medium text-primary-container-foreground uppercase">
                      {item.format}
                    </span>
                  </button>
                </li>
              )
            })}
          </ul>
        </Panel>
      )}

      {open && <Reader book={open} sync={sync} onClose={closeReader} />}
    </div>
  )
}
