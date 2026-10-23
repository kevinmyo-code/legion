import { createHash } from 'node:crypto'
import os from 'node:os'
import path from 'node:path'

import { expect, test, type Page } from '@playwright/test'

import { createEngine, seedHousehold, type Engine } from '../src/test/engine'
import { makeCbz, makeEpub, makePdf } from '../src/test/books-fixtures'

/**
 * The Books reader in a real Chromium (books ticket 10): pick a folder (a faked
 * `showDirectoryPicker`, since a headless browser cannot open the system picker),
 * open an EPUB the phone had already read part of, turn a page, and watch the
 * position PUT go out. The API is the fake engine, answered through `page.route`.
 *
 *     BOOKS_SHOTS=... npx playwright test e2e/books.spec.ts
 */

const OUT = process.env.BOOKS_SHOTS ?? path.join(os.tmpdir(), 'legion-books-shots')

const epub = makeEpub({ title: 'The Harbour Clock', chapters: 4, paragraphs: 40 })
const pdf = makePdf(3)
const cbz = makeCbz()
const sha = (bytes: Uint8Array) => createHash('sha256').update(bytes).digest('hex')
const EPUB_SHA = sha(epub)

async function useEngine(page: Page, engine: Engine) {
  await page.route('**/api/**', async (route) => {
    if (engine.down) {
      await route.abort('failed')
      return
    }
    const request = route.request()
    const url = new URL(request.url())
    const raw = request.postData()
    const reply = engine.handle(request.method(), url.pathname, url.searchParams, raw ? JSON.parse(raw) : undefined)
    await route.fulfill({
      status: reply.status,
      contentType: 'application/json',
      body: reply.body === undefined ? '' : JSON.stringify(reply.body),
    })
  })
}

/** A folder holding the three fixtures, behind `showDirectoryPicker`. */
async function fakeFolder(page: Page) {
  const b64 = (b: Uint8Array) => Buffer.from(b).toString('base64')
  await page.addInitScript(
    ({ epubB64, pdfB64, cbzB64 }) => {
      const bytes = (b64: string) => Uint8Array.from(atob(b64), (c) => c.charCodeAt(0))
      const file = (name: string, b64: string) => ({
        kind: 'file',
        name,
        getFile: async () => new File([bytes(b64)], name, { lastModified: 1_700_000_000_000 }),
      })
      ;(window as unknown as Record<string, unknown>).showDirectoryPicker = async () => ({
        kind: 'directory',
        name: 'Books',
        async *values() {
          yield file('The Harbour Clock.epub', epubB64)
          yield file('Notes.pdf', pdfB64)
          yield file('Comic.cbz', cbzB64)
        },
      })
    },
    { epubB64: b64(epub), pdfB64: b64(pdf), cbzB64: b64(cbz) },
  )
}

function phoneHadReadHalf(): Engine {
  return createEngine({
    ...seedHousehold(),
    books: {
      positions: [
        {
          book_sha256: EPUB_SHA,
          variant: 'original',
          locator: {
            href: 'OEBPS/ch3.xhtml',
            type: 'application/xhtml+xml',
            locations: { progression: 0, position: 1, totalProgression: 0.5 },
          },
          total_progression: 0.5,
          chapter_label: 'Chapter 3',
          recorded_at: new Date(Date.now() - 3_600_000).toISOString(),
          device: 'A25',
          updated_at: new Date(Date.now() - 3_600_000).toISOString(),
        },
      ],
    },
  })
}

async function shot(page: Page, name: string) {
  await page.screenshot({ path: path.join(OUT, `${name}.png`) })
}

test.describe('Books on the laptop', () => {
  test.use({ viewport: { width: 1280, height: 800 } })

  test('opens where the phone left off and records only a turn the person made', async ({ page }) => {
    const engine = phoneHadReadHalf()
    await useEngine(page, engine)
    await fakeFolder(page)
    const puts: string[] = []
    page.on('request', (r) => {
      if (r.method() === 'PUT' && r.url().includes('/api/books/positions/')) puts.push(r.url())
    })

    await page.goto('/books')
    await expect(page.getByRole('heading', { name: 'Books' })).toBeVisible()
    await shot(page, 'books-01-no-folder')
    await page.getByRole('button', { name: 'Choose folder' }).first().click()
    await expect(page.getByText('Reading from')).toBeVisible()
    await expect(page.getByText(/50% on your phone/)).toBeVisible()
    await expect(page.getByText('cbz', { exact: true })).toBeVisible()
    await shot(page, 'books-02-library')

    await page.getByRole('button', { name: /The Harbour Clock/ }).click()
    const reader = page.getByRole('dialog', { name: /Reading/ })
    await expect(reader.getByTestId('sync-note')).toHaveText('Picked up where you left off on your phone.')
    await expect(reader.getByTestId('reader-place')).toContainText('Chapter 3')
    await page.waitForTimeout(2500)
    // The opening jump alone must not be saved: it would beat the phone's real position.
    expect(puts).toHaveLength(0)
    expect(engine.books.positions[0].device).toBe('A25')
    await shot(page, 'books-03-reader-resumed')

    await page.keyboard.press('ArrowRight')
    await expect.poll(() => puts.length, { timeout: 8000 }).toBe(1)
    expect(puts[0]).toContain(`/api/books/positions/${EPUB_SHA}/original`)
    const saved = engine.books.positions[0]
    expect(saved.device).toBe('web')
    expect(saved.locator).not.toBeNull()
    expect(saved.total_progression).toBeGreaterThanOrEqual(0)
  })

  test('with the server unreachable it says progress will sync later and keeps the place', async ({ page }) => {
    const engine = phoneHadReadHalf()
    await useEngine(page, engine)
    await fakeFolder(page)
    await page.goto('/books')
    await page.getByRole('button', { name: 'Choose folder' }).first().click()
    await page.getByRole('button', { name: /The Harbour Clock/ }).click()
    const reader = page.getByRole('dialog', { name: /Reading/ })
    await expect(reader.getByTestId('reader-place')).toContainText('Chapter 3')
    engine.down = true
    await page.keyboard.press('ArrowRight')
    await expect(reader.getByText('Reading progress will sync when the server is reachable.')).toBeVisible({ timeout: 8000 })
    await shot(page, 'books-04-offline')
  })

  test('night theme, warm filter and the settings panel', async ({ page }) => {
    await useEngine(page, phoneHadReadHalf())
    await fakeFolder(page)
    await page.goto('/books')
    await page.getByRole('button', { name: 'Choose folder' }).first().click()
    await page.getByRole('button', { name: /The Harbour Clock/ }).click()
    const reader = page.getByRole('dialog', { name: /Reading/ })
    await expect(reader.getByTestId('reader-place')).toContainText('Chapter 3')
    await reader.getByRole('button', { name: 'Reading settings' }).click()
    await reader.getByRole('button', { name: 'Night' }).click()
    await reader.getByRole('switch', { name: 'Warm filter' }).click()
    await expect(reader.getByTestId('warm-filter')).toBeVisible()
    await page.waitForTimeout(400)
    await shot(page, 'books-05-night-warm-settings')
  })

  test('a PDF opens, and a comic opens, at 390 px', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await useEngine(page, createEngine({ ...seedHousehold() }))
    await fakeFolder(page)
    // No converter on this fake server: the PDF falls back to the original, in words.
    await page.route('**/api/books/convert', (route) =>
      route.fulfill({ status: 502, contentType: 'application/json', body: JSON.stringify({ code: 'failed', message: 'The converter is not running here.' }) }),
    )
    await page.goto('/books')
    await page.getByRole('button', { name: 'Choose folder' }).first().click()
    await page.getByRole('button', { name: /Notes/ }).click()
    const reader = page.getByRole('dialog', { name: /Reading/ })
    await expect(reader.getByText('The converter is not running here.')).toBeVisible()
    await expect(reader.getByRole('img', { name: 'Page 1 of 3' })).toBeVisible()
    await shot(page, 'books-06-pdf-390')
    await reader.getByRole('button', { name: 'Back to the library' }).click()
    await page.getByRole('button', { name: /Comic/ }).click()
    await expect(page.getByRole('dialog', { name: /Reading/ }).getByRole('img', { name: 'Page 1 of 3' }).first()).toBeVisible()
    await shot(page, 'books-07-comic-390')
  })

  test('a bookmark with a note is made, listed, sent to the engine, and deleted', async ({ page }) => {
    const engine = phoneHadReadHalf()
    await useEngine(page, engine)
    await fakeFolder(page)
    await page.goto('/books')
    await page.getByRole('button', { name: 'Choose folder' }).first().click()
    await page.getByRole('button', { name: /The Harbour Clock/ }).click()
    const reader = page.getByRole('dialog', { name: /Reading/ })
    await expect(reader.getByTestId('reader-place')).toContainText('Chapter 3')
    await reader.getByRole('button', { name: 'Bookmarks' }).click()
    await expect(reader.getByText('No bookmarks in this book yet.')).toBeVisible()
    await reader.getByLabel('Note (optional)').fill('the clock paragraph')
    await reader.getByRole('button', { name: 'Bookmark this page' }).click()
    await expect(reader.getByText('the clock paragraph')).toBeVisible()
    await expect.poll(() => engine.books.bookmarks.length).toBe(1)
    expect(engine.books.bookmarks[0]).toMatchObject({ book_sha256: EPUB_SHA, variant: 'original', note: 'the clock paragraph' })
    await shot(page, 'books-08-bookmarks')
    await reader.getByRole('button', { name: /Delete bookmark/ }).click()
    await expect(reader.getByText('No bookmarks in this book yet.')).toBeVisible()
    await expect.poll(() => engine.books.bookmarks[0].deleted_at).not.toBeNull()
  })

  test('a phone locator this copy cannot apply opens near, in words, by totalProgression', async ({ page }) => {
    const engine = phoneHadReadHalf()
    engine.books.positions[0].locator = { href: 'text/part0007.xhtml', type: 'application/xhtml+xml' }
    await useEngine(page, engine)
    await fakeFolder(page)
    await page.goto('/books')
    await page.getByRole('button', { name: 'Choose folder' }).first().click()
    await page.getByRole('button', { name: /The Harbour Clock/ }).click()
    const reader = page.getByRole('dialog', { name: /Reading/ })
    await expect(reader.getByTestId('sync-note')).toHaveText('Opened near where you were on your phone.')
    await expect(reader.getByTestId('reader-place')).toContainText('Chapter')
    await shot(page, 'books-09-opened-near')
  })

  test('a PDF is read as its resizable copy, says so, and offers the original', async ({ page }) => {
    await useEngine(page, createEngine({ ...seedHousehold() }))
    await fakeFolder(page)
    const copy = makeEpub({ title: 'Notes (converted)', chapters: 2, paragraphs: 20 })
    await page.route('**/api/books/convert', (route) =>
      route.fulfill({ status: 200, contentType: 'application/epub+zip', headers: { 'X-Legion-Pages': '3' }, body: Buffer.from(copy) }),
    )
    await page.goto('/books')
    await page.getByRole('button', { name: 'Choose folder' }).first().click()
    await page.getByRole('button', { name: /Notes/ }).click()
    const reader = page.getByRole('dialog', { name: /Reading/ })
    await expect(reader.getByText('Converted from PDF. Figures and tables may be missing.')).toBeVisible()
    await expect(reader.getByTestId('reader-place')).toContainText('0%')
    await shot(page, 'books-10-pdf-resizable')
    await reader.getByRole('button', { name: 'Open original' }).click()
    await expect(reader.getByRole('img', { name: 'Page 1 of 3' })).toBeVisible()
  })
})
