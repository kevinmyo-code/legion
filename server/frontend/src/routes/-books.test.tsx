import { fireEvent, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

import { resetReadingSync } from '@/lib/books/engine'
import { sha256Hex } from '@/lib/books/hash'
import { MemoryKV, setKv } from '@/lib/books/kv'
import { NO_FOLDER_ACCESS } from '@/lib/books/scan'
import { BOOKS_COPY } from '@/screens/books'
import { createEngine, seedHousehold } from '@/test/engine'
import { renderApp } from '@/test/render-app'

/**
 * The Books page (books ticket 10), without the reader: the folder states and the
 * library's progress labels. The reader itself drives Readium, which needs a real
 * browser, so it is exercised by `e2e/books.spec.ts`.
 */

beforeEach(() => {
  setKv(new MemoryKV())
  resetReadingSync()
})

afterEach(() => {
  setKv(undefined)
  resetReadingSync()
  vi.unstubAllGlobals()
  delete (window as unknown as Record<string, unknown>).showDirectoryPicker
})

function folderWith(files: Record<string, string>) {
  return {
    kind: 'directory' as const,
    name: 'Books',
    async *values() {
      for (const [name, text] of Object.entries(files)) {
        yield {
          kind: 'file' as const,
          name,
          getFile: async () => new File([text], name, { lastModified: 1_700_000_000_000 }),
        }
      }
    },
  }
}

describe('the Books page', () => {
  test('a browser that cannot open a folder says so in words and what to do', async () => {
    renderApp('/books', createEngine(seedHousehold()))
    expect(await screen.findByText(NO_FOLDER_ACCESS)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Choose folder' })).not.toBeInTheDocument()
  })

  test('with no folder chosen it says so, and the PDF disclosure is in words, not behind a link', async () => {
    ;(window as unknown as Record<string, unknown>).showDirectoryPicker = async () => folderWith({})
    renderApp('/books', createEngine(seedHousehold()))
    expect(await screen.findByText(BOOKS_COPY.noFolder)).toBeInTheDocument()
    expect(screen.getByText(BOOKS_COPY.pdfDisclosure)).toBeInTheDocument()
  })

  test('an empty folder says there are no books in it', async () => {
    ;(window as unknown as Record<string, unknown>).showDirectoryPicker = async () => folderWith({ 'notes.txt': 'x' })
    renderApp('/books', createEngine(seedHousehold()))
    fireEvent.click((await screen.findAllByRole('button', { name: 'Choose folder' }))[0])
    expect(await screen.findByText(BOOKS_COPY.emptyFolder('Books'))).toBeInTheDocument()
  })

  test('lists books with where the phone got to, and a comic archive it cannot read says so', async () => {
    const text = 'not really an epub'
    const sha = await sha256Hex(new TextEncoder().encode(text))
    ;(window as unknown as Record<string, unknown>).showDirectoryPicker = async () =>
      folderWith({ 'Harbour_Clock.epub': text, 'old.cbr': 'rar' })
    const engine = createEngine({
      ...seedHousehold(),
      books: {
        positions: [
          {
            book_sha256: sha,
            variant: 'original',
            locator: null,
            total_progression: 0.43,
            chapter_label: null,
            recorded_at: new Date(Date.now() - 86_400_000 * 2).toISOString(),
            device: 'A25',
            updated_at: new Date().toISOString(),
          },
        ],
      },
    })
    renderApp('/books', engine)
    fireEvent.click((await screen.findAllByRole('button', { name: 'Choose folder' }))[0])
    expect(await screen.findByText('Harbour Clock')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByText('43% on your phone, 2 days ago')).toBeInTheDocument())
    expect(screen.getByText('CBR is not supported. Convert it to CBZ to read it here.')).toBeInTheDocument()
  })

  test('with the server unreachable it says the progress shown is only what this browser knows', async () => {
    ;(window as unknown as Record<string, unknown>).showDirectoryPicker = async () => folderWith({ 'a.epub': 'x' })
    const engine = createEngine(seedHousehold())
    renderApp('/books', engine)
    fireEvent.click((await screen.findAllByRole('button', { name: 'Choose folder' }))[0])
    await screen.findByText('a')
    engine.refusals['GET /api/books/positions'] = { status: 503 }
    fireEvent.click(screen.getByRole('button', { name: 'Rescan' }))
    expect(await screen.findByText(BOOKS_COPY.syncUnreachable)).toBeInTheDocument()
  })
})
