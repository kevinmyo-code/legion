import os from 'node:os'
import path from 'node:path'

import { expect, test, type Page } from '@playwright/test'

import { createEngine, seedHousehold, type Engine } from '../src/test/engine'

/**
 * The assistant in a real Chromium (web-assistant 08): the real bundle, the real
 * AudioWorklet and Web Audio, against the fake engine (`page.route`) and a
 * scripted Google socket. Chromium runs with a FAKE microphone (a generated
 * tone), so capture, resampling and the 20 ms chunking are exercised for real;
 * nothing here calls Google.
 *
 *     npx playwright test e2e/assistant.spec.ts
 *
 * What this cannot say: how an iPhone behaves (mic prompt, crackle, earpiece,
 * lock). That is owed on Mia's phone (ticket 10). Pictures go to `ASSISTANT_SHOTS`
 * (default: the OS temp dir), never into the repo.
 */

const OUT = process.env.ASSISTANT_SHOTS ?? path.join(os.tmpdir(), 'legion-assistant-shots')

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

function sent(page: Page, key: string) {
  return page.evaluate((k) => {
    const sockets = (window as unknown as { __live: { sent: Record<string, unknown>[] }[] }).__live
    return (sockets[sockets.length - 1]?.sent ?? []).filter((m) => k in m)
  }, key)
}

async function shot(page: Page, name: string) {
  await page.evaluate(() => document.fonts.ready)
  await page.screenshot({ path: path.join(OUT, `${name}.png`) })
}

function household(): Engine {
  return createEngine({
    ...seedHousehold(),
    householdName: 'The Test House',
    assistant: {
      companionName: 'Dorothy',
      tools: { search_purchases: { text: 'Shampoo: 3 matches, last bought Sep 28.' } },
    },
  })
}

// Top level: launch options force a new worker, so they cannot sit in a describe.
// Every test gets the fake device; the refused-mic test replaces getUserMedia.
test.use({
  permissions: ['microphone'],
  launchOptions: { args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'] },
})

test.describe('Mia on her phone (390 px), microphone allowed (fake device)', () => {
  test.use({ viewport: { width: 390, height: 844 } })

  test('types a question, the tool runs through the engine, the reply is text', async ({ page }) => {
    const engine = household()
    await useEngine(page, engine)
    await fakeGoogle(page)
    await page.goto('/')
    await page.getByRole('button', { name: 'Ask Dorothy' }).click()
    const box = page.getByRole('textbox', { name: 'Message Dorothy' })
    await expect(box).toBeFocused()
    await shot(page, '390-sheet-empty')

    await box.fill('When did we last buy shampoo?')
    await page.getByRole('button', { name: 'Send' }).click()
    await expect.poll(() => sent(page, 'clientContent').then((m) => m.length)).toBe(1)
    await serve(page, { toolCall: { functionCalls: [{ id: 't1', name: 'search_purchases', args: { query: 'shampoo' } }] } })
    await expect(page.getByText('Done: Shampoo: 3 matches, last bought Sep 28.')).toBeVisible()
    await serve(page, { serverContent: { outputTranscription: { text: 'Three, the last on Sep 28.' }, turnComplete: true } })
    await expect(page.getByText('Three, the last on Sep 28.')).toBeVisible()
    expect(engine.assistant.toolCalls).toHaveLength(1)
    await shot(page, '390-typed-turn')

    await serve(page, { toolCall: { functionCalls: [{ id: 't2', name: 'play_music', args: {} }] } })
    await expect(page.getByText(/^Did not run: Nothing was read or written/)).toBeVisible()
    await shot(page, '390-tool-refused')
  })

  test('the mic tap streams real 16 kHz chunks from the worklet, plays a reply, and an interruption ends it', async ({ page }) => {
    const engine = household()
    await useEngine(page, engine)
    await fakeGoogle(page)
    const errors: string[] = []
    page.on('pageerror', (error) => errors.push(error.message))
    await page.goto('/')
    await page.getByRole('button', { name: 'Ask Dorothy' }).click()
    await page.getByRole('button', { name: 'Talk to Dorothy' }).click()
    await expect(page.getByRole('region', { name: 'Voice conversation' }).getByText('Listening')).toBeVisible()
    await expect.poll(() => sent(page, 'realtimeInput').then((m) => m.length), { timeout: 10_000 }).toBeGreaterThan(5)
    const first = (await sent(page, 'realtimeInput'))[0] as { realtimeInput: { audio: { mimeType: string; data: string } } }
    expect(first.realtimeInput.audio.mimeType).toBe('audio/pcm;rate=16000')
    // 20 ms at 16 kHz, 16-bit mono: 320 samples, 640 bytes.
    expect(atob(first.realtimeInput.audio.data)).toHaveLength(640)
    await shot(page, '390-listening')

    // A spoken reply: 0.5 s of a 440 Hz tone at 24 kHz, as base64 PCM16.
    const tone = await page.evaluate(() => {
      const pcm = new Int16Array(12_000)
      for (let i = 0; i < pcm.length; i += 1) pcm[i] = Math.round(Math.sin((2 * Math.PI * 440 * i) / 24_000) * 8000)
      const bytes = new Uint8Array(pcm.buffer)
      let binary = ''
      for (const byte of bytes) binary += String.fromCharCode(byte)
      return btoa(binary)
    })
    await serve(page, { serverContent: { inputTranscription: { text: 'what is on today' } } })
    await serve(page, {
      serverContent: {
        modelTurn: { parts: [{ inlineData: { mimeType: 'audio/pcm;rate=24000', data: tone } }] },
        outputTranscription: { text: 'Two things today.' },
      },
    })
    await expect(page.getByRole('region', { name: 'Voice conversation' }).getByText('Dorothy is speaking')).toBeVisible()
    await shot(page, '390-speaking')
    await serve(page, { serverContent: { interrupted: true } })
    await expect(page.getByText('(cut off)')).toBeVisible()
    await expect(page.getByRole('region', { name: 'Voice conversation' }).getByText('Listening')).toBeVisible()

    // Minimise: the orb keeps the conversation and says so.
    await page.getByRole('button', { name: /Minimise/ }).click()
    await expect(page.getByText('Listening. Tap to see.')).toBeVisible()
    await shot(page, '390-minimised-listening')
    expect(errors).toEqual([])
  })

  test('Google ending the session says so and New conversation starts clean', async ({ page }) => {
    await useEngine(page, household())
    await fakeGoogle(page)
    await page.goto('/')
    await page.getByRole('button', { name: 'Ask Dorothy' }).click()
    await page.getByRole('textbox', { name: 'Message Dorothy' }).fill('hello')
    await page.getByRole('button', { name: 'Send' }).click()
    await expect.poll(() => sent(page, 'clientContent').then((m) => m.length)).toBe(1)
    await serve(page, { goAway: { timeLeft: '30s' } })
    await expect(page.getByText('The conversation reached its time limit. Start a new one.')).toBeVisible()
    await shot(page, '390-ended')
    await page.getByRole('button', { name: 'New conversation' }).last().click()
    await expect(page.getByRole('heading', { name: 'Ask Dorothy' })).toBeVisible()
  })

  test('the engine is down: said in words, nothing started', async ({ page }) => {
    const engine = household()
    await useEngine(page, engine)
    await fakeGoogle(page)
    await page.goto('/')
    await page.getByRole('button', { name: 'Ask Dorothy' }).click()
    engine.down = true
    await page.getByRole('textbox', { name: 'Message Dorothy' }).fill('hello')
    await page.getByRole('button', { name: 'Send' }).click()
    await expect(page.getByRole('alert')).toContainText("Can't reach the engine, so nothing was started.")
    await shot(page, '390-engine-down')
  })
})

test.describe('Mia on her phone (390 px), microphone refused', () => {
  test.use({ viewport: { width: 390, height: 844 } })

  test('a blocked microphone is a sentence and typing still starts a conversation', async ({ page }) => {
    const engine = household()
    await useEngine(page, engine)
    await fakeGoogle(page)
    // What a refused prompt does, whatever the browser's own prompt policy.
    await page.addInitScript(() => {
      navigator.mediaDevices.getUserMedia = () => Promise.reject(new DOMException('denied', 'NotAllowedError'))
    })
    await page.goto('/')
    await page.getByRole('button', { name: 'Ask Dorothy' }).click()
    await page.getByRole('button', { name: 'Talk to Dorothy' }).click()
    await expect(page.getByRole('alert')).toContainText('The microphone is blocked for this app')
    await expect(page.getByRole('alert')).toContainText('Typing still works')
    expect(engine.assistant.minted).toBe(0)
    await shot(page, '390-mic-denied')
    await page.getByRole('textbox', { name: 'Message Dorothy' }).fill('typing works')
    await page.getByRole('button', { name: 'Send' }).click()
    await expect.poll(() => engine.assistant.minted).toBe(1)
  })
})

test.describe('Kevin at his desk (1280 px)', () => {
  test.use({ viewport: { width: 1280, height: 900 } })

  test('the rail entry and "/" open the docked panel beside the page', async ({ page }) => {
    await useEngine(page, household())
    await fakeGoogle(page)
    await page.goto('/')
    await expect(page.getByRole('button', { name: 'Dorothy' })).toBeVisible()
    await shot(page, '1280-closed')
    await page.keyboard.press('/')
    const panel = page.getByRole('complementary', { name: 'Conversation with Dorothy' })
    await expect(panel).toBeVisible()
    await expect(panel.getByRole('textbox', { name: 'Message Dorothy' })).toBeFocused()
    await panel.getByRole('textbox', { name: 'Message Dorothy' }).fill('What is on today?')
    await page.getByRole('button', { name: 'Send' }).click()
    await expect.poll(() => sent(page, 'clientContent').then((m) => m.length)).toBe(1)
    await serve(page, { toolCall: { functionCalls: [{ id: 't1', name: 'search_purchases', args: { query: 'x' } }] } })
    await expect(panel.getByText(/^Done: Shampoo/)).toBeVisible()
    await serve(page, { serverContent: { outputTranscription: { text: 'Soccer pickup at 11:45.' }, turnComplete: true } })
    await expect(panel.getByText('Soccer pickup at 11:45.')).toBeVisible()
    await shot(page, '1280-docked-conversation')
  })
})
