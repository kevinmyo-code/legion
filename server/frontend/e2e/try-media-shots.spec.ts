import fs from 'node:fs'
import path from 'node:path'

import { expect, test, type Page } from '@playwright/test'

import type { DemoContent, DemoModel3D } from '../src/api/demo'
import { createEngine, type Engine } from '../src/test/engine'
import { sampleContent } from '../src/test/engine-demo'

/**
 * A /try project section's media in a REAL browser: the 3D viewers (WebGL, the lazy chunk, the
 * scroll rule) and the section's clip. Fake engine; every name and file is invented unless the
 * owner points it at his own files, which are never committed:
 *
 *     TRY_MEDIA_SHOTS=<dir>      where screenshots go
 *     TRY_MEDIA_DIR=<dir>        model files to show: every <stem>.glb, with <stem>.png as poster
 *     TRY_MEDIA_CLIP=<file.webm> a short clip for the video slot (skipped without one)
 *     TRY_MEDIA_POSTERS=<dir>    also write each model's default view as <stem>.png: the poster
 *                                IS the viewer's first frame, so the swap never jumps
 *
 *     npx playwright test e2e/try-media-shots.spec.ts
 */

const OUT = process.env.TRY_MEDIA_SHOTS ?? path.join(process.cwd(), 'test-results', 'try-media')
const DIR = process.env.TRY_MEDIA_DIR
const CLIP = process.env.TRY_MEDIA_CLIP
const POSTERS = process.env.TRY_MEDIA_POSTERS
const SECTION = 'fake-hardware'

/** A unit cube as a GLB, made here so the spec needs no file of anyone's. */
function cubeGlb(): Buffer {
  const faces: [number[], number[]][] = [
    [[1, 0, 0], [0, 1, 0]], [[-1, 0, 0], [0, 1, 0]], [[0, 1, 0], [0, 0, 1]],
    [[0, -1, 0], [0, 0, 1]], [[0, 0, 1], [1, 0, 0]], [[0, 0, -1], [1, 0, 0]],
  ]
  const positions: number[] = []
  const normals: number[] = []
  const indices: number[] = []
  for (const [n, u] of faces) {
    const v = [n[1] * u[2] - n[2] * u[1], n[2] * u[0] - n[0] * u[2], n[0] * u[1] - n[1] * u[0]]
    const base = positions.length / 3
    for (const [a, b] of [[-1, -1], [1, -1], [1, 1], [-1, 1]]) {
      positions.push(...[0, 1, 2].map((i) => 0.5 * (n[i] + a * u[i] + b * v[i])))
      normals.push(...n)
    }
    indices.push(base, base + 1, base + 2, base, base + 2, base + 3)
  }
  const pos = Buffer.from(new Float32Array(positions).buffer)
  const nor = Buffer.from(new Float32Array(normals).buffer)
  const idx = Buffer.from(new Uint16Array(indices).buffer)
  const bin = Buffer.concat([pos, nor, idx, Buffer.alloc((4 - (idx.length % 4)) % 4)])
  const json = {
    asset: { version: '2.0' },
    scene: 0,
    scenes: [{ nodes: [0] }],
    nodes: [{ mesh: 0 }],
    meshes: [{ primitives: [{ attributes: { POSITION: 0, NORMAL: 1 }, indices: 2, material: 0 }] }],
    materials: [{ pbrMetallicRoughness: { baseColorFactor: [0.55, 0.5, 0.45, 1], roughnessFactor: 0.7, metallicFactor: 0 } }],
    buffers: [{ byteLength: bin.length }],
    bufferViews: [
      { buffer: 0, byteOffset: 0, byteLength: pos.length },
      { buffer: 0, byteOffset: pos.length, byteLength: nor.length },
      { buffer: 0, byteOffset: pos.length + nor.length, byteLength: idx.length },
    ],
    accessors: [
      { bufferView: 0, componentType: 5126, count: 24, type: 'VEC3', min: [-0.5, -0.5, -0.5], max: [0.5, 0.5, 0.5] },
      { bufferView: 1, componentType: 5126, count: 24, type: 'VEC3' },
      { bufferView: 2, componentType: 5123, count: 36, type: 'SCALAR' },
    ],
  }
  let text = Buffer.from(JSON.stringify(json))
  text = Buffer.concat([text, Buffer.alloc((4 - (text.length % 4)) % 4, 0x20)])
  const header = Buffer.alloc(12)
  header.write('glTF', 0)
  header.writeUInt32LE(2, 4)
  header.writeUInt32LE(12 + 8 + text.length + 8 + bin.length, 8)
  const chunk = (data: Buffer, type: number) => {
    const head = Buffer.alloc(8)
    head.writeUInt32LE(data.length, 0)
    head.writeUInt32LE(type, 4)
    return Buffer.concat([head, data])
  }
  return Buffer.concat([header, chunk(text, 0x4e4f534a), chunk(bin, 0x004e4942)])
}

/** A 1x1 paper-coloured PNG: the fixture's poster. */
const PAPER_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/+L1fwAJcAPTqfMRmQAAAABJRU5ErkJggg==',
  'base64',
)

/** name -> bytes the fake engine serves at /api/demo/media/<name>. */
function mediaFiles(): Map<string, Buffer> {
  const files = new Map<string, Buffer>()
  if (DIR) {
    for (const name of fs.readdirSync(DIR)) {
      if (/\.(glb|png)$/.test(name)) files.set(name, fs.readFileSync(path.join(DIR, name)))
    }
  } else {
    files.set('cube.glb', cubeGlb())
    files.set('cube.png', PAPER_PNG)
  }
  return files
}

function models(files: Map<string, Buffer>): DemoModel3D[] {
  return [...files.keys()]
    .filter((name) => name.endsWith('.glb'))
    .map((name) => {
      const stem = name.slice(0, -4)
      return { title: `Fake model ${stem}`, model: `/api/demo/media/${name}`, poster: `/api/demo/media/${stem}.png` }
    })
}

function content(files: Map<string, Buffer>): DemoContent {
  return {
    ...sampleContent(),
    project_sections: [
      {
        id: SECTION,
        title: 'Fake hardware',
        intro: 'A fake project with a fake board and a fake lever.',
        // Long enough that the viewers start more than a screen below the fold, as on the real page.
        parts: Array.from({ length: 24 }, (_, i) => ({
          title: `Fake part ${i + 1}`,
          body: 'Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt.',
        })),
        media: {
          ...(CLIP ? { video: '/fake-media/clip.webm', captions: '/fake-media/clip.vtt' } : {}),
          note: 'Recorded on fake data.',
          models: models(files),
        },
      },
    ],
  }
}

const VTT = 'WEBVTT\n\n00:00.000 --> 00:05.000\nA fake caption.\n'

async function open(page: Page) {
  const files = mediaFiles()
  const engine: Engine = createEngine({ signedIn: false, assistant: { companionName: 'Alfred' } })
  engine.demo.session = { role: 'recruiter', expires_at: new Date(Date.now() + 3_600_000).toISOString(), ref: 'acme' }
  engine.demo.content = content(files)
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    let reply: { status: number; body?: unknown }
    try {
      reply = engine.handle(request.method(), url.pathname, url.searchParams, undefined)
    } catch {
      reply = { status: 204 }
    }
    await route.fulfill({
      status: reply.status,
      contentType: 'application/json',
      body: reply.body === undefined ? '' : JSON.stringify(reply.body),
    })
  })
  // Registered last, so it answers first: the private media route, as the engine would.
  const served: string[] = []
  await page.route('**/api/demo/media/*', async (route) => {
    const name = decodeURIComponent(new URL(route.request().url()).pathname.split('/').pop()!)
    served.push(name)
    const data = files.get(name)
    if (!data) return route.fulfill({ status: 404, body: '{}' })
    await route.fulfill({ status: 200, body: data, contentType: name.endsWith('.glb') ? 'model/gltf-binary' : 'image/png' })
  })
  if (CLIP) {
    await page.route('**/fake-media/clip.webm', (route) =>
      route.fulfill({ status: 200, body: fs.readFileSync(CLIP), contentType: 'video/webm' }),
    )
    await page.route('**/fake-media/clip.vtt', (route) => route.fulfill({ status: 200, body: VTT, contentType: 'text/vtt' }))
  }
  await page.goto('/try')
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  return { files, served }
}

function viewers(page: Page) {
  return page.locator(`#${SECTION} .rc-model-box`)
}

/**
 * Scroll an element to the middle of the window. Not `scrollIntoViewIfNeeded`: that waits for two
 * steady animation frames, and software WebGL (headless) draws a big model slowly enough to time out.
 */
async function centre(locator: ReturnType<Page['locator']>) {
  await locator.evaluate((el) => el.scrollIntoView({ block: 'center' }))
  await locator.page().waitForTimeout(300)
}

/** Bring the section's first viewer on screen and wait for its 3D (never the poster fallback). */
async function ready(page: Page, index = 0) {
  const box = viewers(page).nth(index)
  await centre(box)
  await expect(box).not.toHaveAttribute('data-state', 'poster', { timeout: 15_000 })
  await expect(box).toHaveAttribute('data-state', /ready|failed/, { timeout: 30_000 })
  expect(await box.getAttribute('data-state'), 'the 3D view loaded (not the poster fallback)').toBe('ready')
  return box
}

/** The viewer's pixels, by a page clip (an element shot waits for steady frames; see `centre`). */
async function canvasShot(page: Page, index = 0): Promise<Buffer> {
  const r = (await viewers(page).nth(index).boundingBox())!
  return page.screenshot({ clip: { x: r.x + 2, y: r.y + 2, width: r.width - 4, height: r.height - 4 } })
}

async function shot(page: Page, name: string) {
  fs.mkdirSync(OUT, { recursive: true })
  await page.screenshot({ path: path.join(OUT, `${name}.png`) })
}

test.use({
  // Headless Chromium draws WebGL in software (SwiftShader) only when asked.
  launchOptions: { args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] },
})

// Software WebGL (headless) draws a 100k-triangle board at a few frames a second: room to finish.
test.describe.configure({ timeout: 180_000 })

test.describe('desk (1280 x 800)', () => {
  test.use({ viewport: { width: 1280, height: 800 } })

  test('poster first; the 3D chunk and model load only near the section; drag, keys and reset', async ({ page }) => {
    const chunks: string[] = []
    page.on('request', (request) => {
      if (/model-stage.*\.js$/.test(request.url())) chunks.push(request.url())
    })
    const { served } = await open(page)
    await page.waitForTimeout(800)
    expect(chunks, 'no 3D chunk at the top of the page').toEqual([])
    expect(served.filter((name) => name.endsWith('.glb')), 'no model at the top of the page').toEqual([])
    await shot(page, 'desk-1-top')

    const box = await ready(page)
    expect(chunks.length).toBe(1)
    await centre(box)
    await page.waitForTimeout(600)
    await shot(page, 'desk-2-section')

    // A mouse drag turns it at once.
    const before = await canvasShot(page)
    const rect = (await box.boundingBox())!
    await page.mouse.move(rect.x + rect.width / 2, rect.y + rect.height / 2)
    await page.mouse.down()
    await page.mouse.move(rect.x + rect.width / 2 + 160, rect.y + rect.height / 2, { steps: 8 })
    await page.mouse.up()
    await page.waitForTimeout(400)
    expect((await canvasShot(page)).equals(before), 'a drag turned the model').toBe(false)
    await page.mouse.move(rect.x + rect.width / 2, rect.y - 40) // leave: hands the wheel back

    // Untapped, the wheel over the model scrolls the page (upward: there is always page above).
    await page.mouse.move(rect.x + rect.width / 2, rect.y + rect.height / 2)
    const y0 = await page.evaluate(() => window.scrollY)
    await page.mouse.wheel(0, -300)
    await page.waitForTimeout(400)
    expect(await page.evaluate(() => window.scrollY), 'the wheel scrolled the page').toBeLessThan(y0)

    // Clicked, the wheel zooms the model and the page stays put.
    await centre(box)
    const r2 = (await box.boundingBox())!
    await page.mouse.click(r2.x + r2.width / 2, r2.y + r2.height / 2)
    await expect(box).toHaveAttribute('data-active', 'true')
    const y1 = await page.evaluate(() => window.scrollY)
    const zoomBefore = await canvasShot(page)
    await page.mouse.wheel(0, -400)
    await page.waitForTimeout(500)
    expect(await page.evaluate(() => window.scrollY), 'the page did not scroll while the model had the wheel').toBe(y1)
    expect((await canvasShot(page)).equals(zoomBefore), 'the wheel zoomed the model').toBe(false)
    await shot(page, 'desk-3-active')

    // Keys turn it; Reset puts it back.
    await page.getByRole('button', { name: 'Done' }).dispatchEvent('click')
    await box.focus()
    const keysBefore = await canvasShot(page)
    await page.keyboard.press('ArrowLeft')
    await page.keyboard.press('ArrowLeft')
    await page.waitForTimeout(300)
    expect((await canvasShot(page)).equals(keysBefore), 'arrow keys turned the model').toBe(false)
    await page.getByRole('button', { name: 'Reset view' }).first().dispatchEvent('click')
    await page.waitForTimeout(600)
    await shot(page, 'desk-4-reset')
  })

  test('the 3D chunk is not in the service worker precache and brings no WebAssembly', async () => {
    const dist = path.resolve(process.cwd(), '../static/app')
    const sw = fs.readFileSync(path.join(dist, 'sw.js'), 'utf-8')
    const files = fs.readdirSync(path.join(dist, 'assets'))
    const stage = files.filter((name) => /^model-stage.*\.js$/.test(name))
    expect(stage.length, 'one lazy 3D chunk').toBe(1)
    expect(sw.includes(stage[0]), 'the 3D chunk is precached').toBe(false)
    expect(fs.readFileSync(path.join(dist, 'assets', stage[0]), 'utf-8')).not.toContain('WebAssembly')
    // onnxruntime-web's own ort-wasm file predates this chunk; nothing else of the kind may appear.
    expect(files.filter((name) => /\.(wasm|onnx|glb)$/.test(name) && !/^ort-wasm-/.test(name))).toEqual([])
  })
})

test.describe('phone (390 x 700)', () => {
  test.use({ viewport: { width: 390, height: 700 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2 })

  test('nothing overflows; a swipe over an untapped model scrolls the page; tapped, it is the model\'s', async ({ page }) => {
    await open(page)
    const box = await ready(page)
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390)
    await page.waitForTimeout(600)
    await shot(page, 'phone-1-section')

    const cdp = await page.context().newCDPSession(page)
    /** A finger dragged down the screen over `target`, as raw touch events through the browser's own
     * input pipeline (so `touch-action` and the page's listeners decide what it does). */
    const swipeOver = async (target: ReturnType<Page['locator']>) => {
      const r = (await target.boundingBox())!
      const x = Math.round(r.x + r.width / 2)
      const y = Math.round(r.y + Math.min(r.height / 2, 60))
      const before = await page.evaluate(() => window.scrollY)
      await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] })
      for (let step = 1; step <= 10; step++) {
        await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x, y: y + step * 20 }] })
        await page.waitForTimeout(16)
      }
      await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
      await page.waitForTimeout(600)
      return before - (await page.evaluate(() => window.scrollY))
    }
    // Control: the same gesture over the section's text scrolls the page, so the harness can scroll.
    const intro = page.locator(`#${SECTION} > p`).first()
    await centre(intro)
    expect(await swipeOver(intro), 'control: a swipe over text scrolled the page').toBeGreaterThan(0)
    await centre(box)
    expect(await swipeOver(box), 'a swipe over the untapped model scrolled the page').toBeGreaterThan(0)
    const swipe = () => swipeOver(box)

    await centre(box)
    const t = (await box.boundingBox())!
    await page.touchscreen.tap(t.x + t.width / 2, t.y + t.height / 2)
    await expect(box).toHaveAttribute('data-active', 'true')
    const before = await canvasShot(page)
    expect(await swipe(), 'tapped, a swipe did not move the page').toBe(0)
    expect((await canvasShot(page)).equals(before), 'tapped, a swipe turned the model').toBe(false)
    await shot(page, 'phone-2-active')
    await page.getByRole('button', { name: 'Done' }).dispatchEvent('click')
    await expect(box).toHaveAttribute('data-active', 'false')
  })

  test('the section clip plays inline with captions, never by itself', async ({ page }) => {
    test.skip(!CLIP, 'TRY_MEDIA_CLIP not set')
    await open(page)
    const video = page.locator(`#${SECTION} video`)
    await centre(video)
    expect(await video.evaluate((v: HTMLVideoElement) => v.paused)).toBe(true)
    await page.waitForTimeout(800)
    expect(await video.evaluate((v: HTMLVideoElement) => v.currentTime)).toBe(0)
    await video.evaluate((v: HTMLVideoElement) => v.play())
    await page.waitForTimeout(1500)
    const state = await video.evaluate((v: HTMLVideoElement) => ({
      time: v.currentTime,
      captions: v.textTracks[0]?.mode,
      cues: v.textTracks[0]?.cues?.length ?? 0,
      fullscreen: document.fullscreenElement !== null,
    }))
    expect(state.time).toBeGreaterThan(0.3)
    expect(state.captions).toBe('showing')
    expect(state.cues).toBeGreaterThan(0)
    expect(state.fullscreen).toBe(false)
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390)
    await shot(page, 'phone-3-clip-playing')
  })
})

test.describe('posters (the viewer\'s own first frame)', () => {
  test.use({ viewport: { width: 1280, height: 900 }, deviceScaleFactor: 2, reducedMotion: 'reduce' })

  test('writes each model\'s default view', async ({ page }) => {
    test.skip(!POSTERS, 'TRY_MEDIA_POSTERS not set')
    const { files } = await open(page)
    const names = [...files.keys()].filter((name) => name.endsWith('.glb'))
    fs.mkdirSync(POSTERS!, { recursive: true })
    for (let i = 0; i < names.length; i++) {
      await ready(page, i)
      await page.mouse.move(0, 0)
      await page.waitForTimeout(800)
      await viewers(page).nth(i).locator('canvas').screenshot({ path: path.join(POSTERS!, `${names[i].slice(0, -4)}.png`) })
    }
  })
})
