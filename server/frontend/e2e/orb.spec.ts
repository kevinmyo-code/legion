import path from 'node:path'

import { expect, test, type Page } from '@playwright/test'

import { createEngine, type Engine } from '../src/test/engine'

/**
 * The recruiter dock's voice orb at desk width, for LOOKING (Kevin, 2026-11-04).
 * Fake engine, scripted Google socket, fake microphone; nothing calls Google.
 *
 *     ORB_SHOTS=<dir> npx playwright test e2e/orb.spec.ts
 */

const OUT = process.env.ORB_SHOTS ?? path.join(process.cwd(), 'test-results', 'orb')

/** The conversation record goes up as something other than JSON; the fake engine ignores it. */
function parseBody(raw: string): unknown {
  try {
    return JSON.parse(raw)
  } catch {
    return undefined
  }
}

async function useEngine(page: Page, engine: Engine) {
  await page.route('**/api/**', async (route) => {
    if (engine.down) {
      await route.abort('failed')
      return
    }
    const request = route.request()
    const url = new URL(request.url())
    const raw = request.postData()
    let reply: { status: number; body?: unknown }
    try {
      reply = engine.handle(request.method(), url.pathname, url.searchParams, raw ? parseBody(raw) : undefined)
    } catch {
      // The conversation record's upload: not what these pictures are about.
      reply = { status: 204 }
    }
    await route.fulfill({
      status: reply.status,
      contentType: 'application/json',
      body: reply.body === undefined ? '' : JSON.stringify(reply.body),
    })
  })
}

/** Replace Google's WebSocket with a scripted one the test drives from `window.__live`. */
async function fakeGoogle(page: Page) {
  await page.addInitScript(() => {
    type Frame = Record<string, unknown>
    class FakeWS {
      url: string
      readyState = 0
      binaryType = 'blob'
      sent: Frame[] = []
      onopen: ((e: unknown) => void) | null = null
      onmessage: ((e: { data: string }) => void) | null = null
      onerror: ((e: unknown) => void) | null = null
      onclose: ((e: { code: number; reason: string }) => void) | null = null
      constructor(url: string) {
        this.url = url
        ;(window as unknown as { __live: FakeWS[] }).__live.push(this)
        setTimeout(() => {
          this.readyState = 1
          this.onopen?.({})
        }, 0)
      }
      send(data: string) {
        const frame = JSON.parse(data) as Frame
        this.sent.push(frame)
        if ('setup' in frame) setTimeout(() => this.serve({ setupComplete: {} }), 0)
      }
      close() {
        this.readyState = 3
      }
      serve(message: unknown) {
        this.onmessage?.({ data: JSON.stringify(message) })
      }
    }
    ;(window as unknown as { __live: FakeWS[] }).__live = []
    ;(window as unknown as { WebSocket: unknown }).WebSocket = FakeWS
  })
}

function serve(page: Page, message: unknown) {
  return page.evaluate((m) => {
    const sockets = (window as unknown as { __live: { serve(m: unknown): void }[] }).__live
    sockets[sockets.length - 1].serve(m)
  }, message)
}

async function shot(page: Page, name: string) {
  await page.evaluate(() => document.fonts.ready)
  await page.waitForTimeout(900)
  await page.screenshot({ path: path.join(OUT, `${name}.png`) })
}

function visitor(): Engine {
  const engine = createEngine({ signedIn: false, assistant: { companionName: 'Alfred' } })
  engine.demo.session = { role: 'recruiter', expires_at: new Date(Date.now() + 2 * 3_600_000).toISOString(), ref: 'acme' }
  return engine
}

test.use({
  permissions: ['microphone'],
  viewport: { width: 1440, height: 900 },
  launchOptions: { args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'] },
})

test('the orb through a voice conversation, then the chat unfolded', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (error) => {
    // The sandboxed report-sample frame cannot reach a service worker; that is the frame, not the orb.
    if (!error.message.includes("'serviceWorker'")) errors.push(error.message)
  })
  await useEngine(page, visitor())
  await fakeGoogle(page)
  await page.goto('/try')
  const dock = page.getByRole('complementary', { name: /^Conversation with/ })
  await expect(dock.getByRole('button', { name: 'Talk to Alfred' })).toBeVisible()
  await shot(page, 'orb-1-idle')

  await dock.getByRole('button', { name: 'Talk to Alfred' }).click()
  await expect(dock.getByRole('status')).toHaveText(/checking/)
  await shot(page, 'orb-2-thinking')

  // Four seconds of a syllable-shaped tone at 24 kHz, so the output tap has something to follow.
  const speech = await page.evaluate(() => {
    const pcm = new Int16Array(96_000)
    for (let i = 0; i < pcm.length; i += 1) {
      const t = i / 24_000
      const envelope = Math.max(0, Math.sin(t * 2 * Math.PI * 3.2)) * (0.6 + 0.4 * Math.sin(t * 5))
      pcm[i] = Math.round(Math.sin(2 * Math.PI * 180 * t) * 12_000 * envelope)
    }
    const bytes = new Uint8Array(pcm.buffer)
    let binary = ''
    for (const byte of bytes) binary += String.fromCharCode(byte)
    return btoa(binary)
  })
  await serve(page, {
    serverContent: {
      modelTurn: { parts: [{ inlineData: { mimeType: 'audio/pcm;rate=24000', data: speech } }] },
      outputTranscription: { text: 'Good afternoon. I can walk you through what Kevin built, or the demo household.' },
    },
  })
  await expect(dock.getByRole('status')).toHaveText('Alfred is speaking')
  await shot(page, 'orb-3-speaking')
  // Cut him off, as a visitor would by talking: the orb goes back to listening.
  await serve(page, { serverContent: { interrupted: true } })
  await serve(page, { serverContent: { turnComplete: true } })
  await expect(dock.getByRole('status')).toHaveText('Listening')
  await shot(page, 'orb-4-listening')

  await dock.getByRole('button', { name: 'Type instead' }).click()
  await expect(dock.getByRole('textbox', { name: 'Message Alfred' })).toBeFocused()
  await shot(page, 'orb-5-chat-open')
  expect(errors).toEqual([])
})
