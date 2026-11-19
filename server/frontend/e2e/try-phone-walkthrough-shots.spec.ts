import fs from 'node:fs'
import path from 'node:path'

import { expect, test, type Page } from '@playwright/test'

import type { DemoContent } from '../src/api/demo'
import { createEngine, type Engine } from '../src/test/engine'
import { sampleContent } from '../src/test/engine-demo'

/**
 * The /try phone video (2026-11-19) for LOOKING: the section at 390 x 700 and 1280 x 800, once
 * per chapter (seeked there), plus a frame strip of one chapter change. Invented content; the
 * video and clips are local files served under `/local-media/` from `WALK_MEDIA` (a folder with
 * walk.webm, walk.png, walk.vtt, chapters.json and clip-1..3.webm: WebM because Playwright's
 * Chromium has no H.264). Fake engine; nothing leaves the machine.
 *
 *     WALK_MEDIA=<dir> TRY_SHOTS=<out dir> [WALK_BASE_URL=http://127.0.0.1:<port>] \
 *       npx playwright test e2e/try-phone-walkthrough-shots.spec.ts
 *
 * Also checks, in numbers: no page-width overflow at 390 px, nothing loads a video before a
 * press, and a chapter press seeks the video to that chapter.
 */

// Another worktree's preview may already hold the config's port (it is reused): WALK_BASE_URL
// points at this tree's own preview instead.
if (process.env.WALK_BASE_URL) test.use({ baseURL: process.env.WALK_BASE_URL })

const OUT = process.env.TRY_SHOTS ?? path.join(process.cwd(), 'test-results', 'try-phone-walk')
const MEDIA = process.env.WALK_MEDIA ?? ''

const CHAPTERS = JSON.parse(
  MEDIA && fs.existsSync(path.join(MEDIA, 'chapters.json'))
    ? fs.readFileSync(path.join(MEDIA, 'chapters.json'), 'utf-8')
    : '[{"title":"Invented one","line":"An invented line.","at":0},{"title":"Invented two","line":"Another.","at":4}]',
) as { title: string; line: string; at: number }[]

function content(): DemoContent {
  return {
    ...sampleContent(),
    phone: {
      title: 'LEGION on the phone',
      intro: 'An invented intro for the phone section.',
      clips: [1, 2, 3].map((n) => ({
        title: `Invented full clip ${n}`,
        body: `An invented line about clip ${n}.`,
        video: `/local-media/clip-${n}.webm`,
      })),
      walkthrough: {
        video: '/local-media/walk.webm',
        poster: '/local-media/walk.png',
        captions: '/local-media/walk.vtt',
        chapters: CHAPTERS,
      },
    },
  }
}

const TYPES: Record<string, string> = { '.webm': 'video/webm', '.mp4': 'video/mp4', '.png': 'image/png', '.vtt': 'text/vtt' }

async function serve(page: Page, engine: Engine, fetched: string[]) {
  // WALK_MEDIA_SERVED=1: the files sit in the preview's own folder (`local-media/`), served with
  // range requests, so seeking works as it does from the bucket; only the requests are watched.
  page.on('request', (request) => {
    if (request.url().includes('/local-media/') && process.env.WALK_MEDIA_SERVED) fetched.push(path.basename(new URL(request.url()).pathname))
  })
  if (process.env.WALK_MEDIA_SERVED) return serveApi(page, engine)
  await page.route('**/local-media/**', async (route) => {
    const name = path.basename(new URL(route.request().url()).pathname)
    fetched.push(name)
    const file = path.join(MEDIA, name)
    if (!MEDIA || !fs.existsSync(file)) return route.fulfill({ status: 404 })
    await route.fulfill({ status: 200, contentType: TYPES[path.extname(name)] ?? 'application/octet-stream', body: fs.readFileSync(file) })
  })
  await serveApi(page, engine)
}

async function serveApi(page: Page, engine: Engine) {
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    let reply: { status: number; body?: unknown }
    try {
      reply = engine.handle(request.method(), url.pathname, url.searchParams, undefined)
    } catch {
      reply = { status: 204 }
    }
    await route.fulfill({ status: reply.status, contentType: 'application/json', body: reply.body === undefined ? '' : JSON.stringify(reply.body) })
  })
}

function visitor(): Engine {
  const engine = createEngine({ signedIn: false, assistant: { companionName: 'Alfred' } })
  engine.demo.session = { role: 'recruiter', expires_at: new Date(Date.now() + 2 * 3_600_000).toISOString(), ref: 'acme' }
  engine.demo.content = content()
  return engine
}

async function run(page: Page, tag: string) {
  fs.mkdirSync(OUT, { recursive: true })
  const fetched: string[] = []
  await serve(page, visitor(), fetched)
  await page.goto('/try')
  const section = page.locator('#phone')
  await section.scrollIntoViewIfNeeded()
  await page.evaluate(() => document.fonts.ready)
  await page.waitForTimeout(600)
  // Nothing but the poster is fetched before a press.
  expect(fetched.filter((name) => /\.(mp4|webm)$/.test(name))).toEqual([])
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(page.viewportSize()!.width)
  await page.evaluate(() => document.getElementById('phone')?.scrollIntoView({ block: 'start' }))
  await page.screenshot({ path: path.join(OUT, `${tag}-0-poster.png`) })

  const buttons = section.getByRole('list', { name: 'Chapters' }).getByRole('button')
  const video = section.locator('video.rc-walk-video')
  const count = await buttons.count()
  // A build without the walkthrough draws no chapters: say so rather than pass on posters alone.
  expect(count).toBe(CHAPTERS.length)
  for (let i = 0; i < count; i++) {
    await buttons.nth(i).click()
    // Held still at once: a short chapter would otherwise play on into the next before the shot.
    await video.evaluate((v: HTMLVideoElement) => v.pause())
    await expect.poll(() => video.evaluate((v: HTMLVideoElement) => v.currentTime), { timeout: 5000 }).toBeGreaterThanOrEqual(CHAPTERS[i].at - 0.05)
    await expect(buttons.nth(i)).toHaveAttribute('aria-current', 'step')
    await page.evaluate(() => document.getElementById('phone')?.scrollIntoView({ block: 'start' }))
    await page.waitForTimeout(250)
    await page.screenshot({ path: path.join(OUT, `${tag}-${String(i + 1).padStart(2, '0')}-${CHAPTERS[i].title.replace(/[^A-Za-z0-9]+/g, '-')}.png`) })
  }

  // A frame strip of one chapter change: the moment the playing video crosses into chapter 2.
  if (count > 1) {
    await buttons.nth(0).click()
    await video.evaluate((v: HTMLVideoElement, at: number) => {
      v.currentTime = Math.max(at - 0.6, 0)
      void v.play()
    }, CHAPTERS[1].at)
    for (let f = 0; f < 6; f++) {
      await page.waitForTimeout(250)
      await page.evaluate(() => document.getElementById('phone')?.scrollIntoView({ block: 'start' }))
      await page.screenshot({ path: path.join(OUT, `${tag}-strip-${f}.png`) })
    }
    await video.evaluate((v: HTMLVideoElement) => v.pause())
  }
}

test.describe('desk (1280 x 800)', () => {
  test.use({ viewport: { width: 1280, height: 800 } })
  test('the phone video, poster then every chapter', async ({ page }) => {
    await run(page, 'desk')
  })
})

test.describe('phone (390 x 700)', () => {
  test.use({ viewport: { width: 390, height: 700 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2 })
  test('the phone video, poster then every chapter', async ({ page }) => {
    await run(page, 'phone')
  })
})
