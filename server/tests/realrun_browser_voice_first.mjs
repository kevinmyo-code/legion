// One voice-first scenario for `realrun_browser_voice_first.py` (ADR 0103): headless Chromium at
// phone width (360 x 780) with Chrome's fake microphone playing a WAV, against the local engine.
// Writes <out>/<scenario>.json and screenshots. Never run against a deployed engine.
// Usage: node <this> '<json args>'
//
// Scenarios:
//  vf-echo   the demo link; tap the orb; the mic hears only the opening's own words at a lower level
//            while it plays (the reply heard back): no barge-in, the opening plays to its end.
//  vf-barge  the demo link; a question, then the person talks over the long answer: playback stops,
//            the engine's turn is cancelled, what they said is the next turn.
//  vf-tap   a question, then a tap on the orb while the answer plays and at once a typed question:
//            the cancel is posted (204) and the new turn is answered, not refused busy.
//  vf-eot    a question, timed: how long after the speech ends the turn is posted (models).
//  vf-eot-energy  the same with the model files refused: the 1.2 s energy threshold.
//  vf-desk   the same link at desk width, for a screenshot.
import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'

const A = JSON.parse(process.argv[2])
const require = createRequire(path.join(process.env.NODE_PATH, 'noop.js'))
const { chromium } = require('playwright')
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const R = { scenario: A.scenario, checks: {}, turns: [], console: [], foreign: [], notes: [], weight: {} }
const check = (name, ok, detail) => { R.checks[name] = { ok: Boolean(ok), detail } }

const browser = await chromium.launch({
  headless: true,
  args: [
    '--use-fake-ui-for-media-stream',
    '--use-fake-device-for-media-stream',
    `--use-file-for-fake-audio-capture=${A.wav}%noloop`,
    '--autoplay-policy=user-gesture-required',
  ],
})
const desk = A.scenario === 'vf-desk'
const context = await browser.newContext({
  viewport: desk ? { width: 1440, height: 900 } : { width: 360, height: 780 },
  deviceScaleFactor: desk ? 1 : 2,
  isMobile: !desk,
  hasTouch: !desk,
  serviceWorkers: 'block',
})
await context.grantPermissions(['microphone'], { origin: A.base })
const page = await context.newPage()
if (A.scenario === 'vf-eot-energy') await page.route('**/turn/*.onnx', (route) => route.fulfill({ status: 404, body: '' }))

await page.addInitScript(() => {
  const audit = { buffers: [], micAt: null }
  window.__audit = audit
  const gum = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices)
  navigator.mediaDevices.getUserMedia = async (c) => {
    const s = await gum(c)
    audit.micAt = performance.now()
    return s
  }
  window.__turnBodies = []
  const realFetch = window.fetch
  window.fetch = async function (input, init) {
    const url = typeof input === 'string' ? input : input.url
    const at = performance.now()
    const res = await realFetch.call(this, input, init)
    if (url && url.includes('/api/assistant/turn') && !url.includes('/turn/cancel')) {
      const entry = { at, status: res.status, text: '', done: false, error: null }
      window.__turnBodies.push(entry)
      res.clone().text().then((t) => { entry.text = t; entry.done = true }, (e) => { entry.error = String(e); entry.done = true })
    }
    return res
  }
  const start = AudioBufferSourceNode.prototype.start
  AudioBufferSourceNode.prototype.start = function (...args) {
    const b = this.buffer
    if (b) audit.buffers.push({ t: performance.now(), when: args[0] ?? 0, ctx: this.context.currentTime, len: b.length, rate: b.sampleRate })
    return start.apply(this, args)
  }
  const stop = AudioBufferSourceNode.prototype.stop
  audit.stops = []
  AudioBufferSourceNode.prototype.stop = function (...args) {
    audit.stops.push(performance.now())
    return stop.apply(this, args)
  }
})

page.on('console', (m) => { if (m.type() === 'error') R.console.push(m.text().slice(0, 300)) })
page.on('pageerror', (e) => R.console.push(`pageerror ${String(e).slice(0, 300)}`))
let tapAt = null
const bytes = { beforeTap: 0, afterTap: 0, models: 0 }
page.on('requestfinished', async (req) => {
  const url = new URL(req.url())
  if (!['localhost', '127.0.0.1'].some((h) => url.host.startsWith(h)) && !url.protocol.startsWith('data')) R.foreign.push(req.url())
  try {
    const sizes = await req.sizes()
    const n = sizes.responseBodySize + sizes.responseHeadersSize
    if (tapAt === null) bytes.beforeTap += n
    else bytes.afterTap += n
    if (/\/turn\/.*\.onnx$|\.wasm$/.test(url.pathname)) bytes.models += n
  } catch {}
})
const turns = []
const cancels = []
page.on('response', (res) => {
  if (new URL(res.url()).pathname === '/api/assistant/turn/cancel') cancels.push({ status: res.status(), wall: Date.now() })
})
page.on('request', (req) => {
  const url = new URL(req.url())
  if (url.pathname === '/api/assistant/turn' && req.method() === 'POST') {
    const s = (req.postDataBuffer() ?? Buffer.alloc(0)).toString('latin1')
    const kind = s.includes('name="greet"') ? 'greet' : s.includes('name="tour"') ? 'tour' : s.includes('name="spoken"') ? 'spoken-text' : s.includes('name="text"') ? 'text' : 'audio'
    turns.push({ kind, req, wall: Date.now() })
  }
  if (url.pathname === '/api/assistant/transcribe') turns.push({ kind: 'transcribe', req, wall: Date.now() })
})
page.on('requestfailed', (req) => {
  const t = turns.find((x) => x.req === req)
  if (t) t.failed = req.failure()?.errorText ?? 'failed'
})

function parse(text) {
  return text.split('\n\n').filter(Boolean).map((b) => {
    const kind = b.split('\n')[0].replace('event: ', '')
    let data = {}
    try { data = JSON.parse(b.split('\n').slice(1).join('').replace('data: ', '')) } catch {}
    return { kind, data }
  })
}
async function sync() {
  const bodies = await page.evaluate(() => window.__turnBodies.map((b) => ({ at: b.at, status: b.status, done: b.done, text: b.text, error: b.error })))
  const posts = turns.filter((t) => t.kind !== 'transcribe')
  bodies.forEach((b, i) => {
    const t = posts[i]
    if (!t) return
    t.at = b.at
    t.status = b.status
    if (!b.done) return
    if (b.error) t.failed = t.failed ?? b.error
    const ev = parse(b.text)
    t.heard = ev.find((e) => e.kind === 'heard')?.data.text
    t.said = ev.filter((e) => e.kind === 'sentence').map((e) => e.data.text).join(' ')
    t.tools = ev.filter((e) => e.kind === 'tool').map((e) => `${e.data.name}:${e.data.ms}ms`)
    t.fillers = ev.filter((e) => e.kind === 'filler').map((e) => `${e.data.tool}:${e.data.text}`)
    t.done = ev.find((e) => e.kind === 'done')?.data
    t.complete = ev.some((e) => e.kind === 'done' || e.kind === 'error')
  })
}
const waitFor = async (fn, ms) => { const end = Date.now() + ms; while (Date.now() < end) { await sync().catch(() => {}); if (await fn()) return true; await sleep(200) } return false }
const audit = () => page.evaluate(() => window.__audit)
const playMs = (bufs) => bufs.reduce((n, b) => n + (b.len / b.rate) * 1000, 0)

// ---- open the link: the orb, no gate ------------------------------------------------------------
await page.goto(`${A.base}/try?code=${encodeURIComponent(A.code)}&ref=browser-vf`)
await page.locator('[data-stage="orb"]').waitFor({ timeout: 30000 })
await sleep(1500)
R.weight.beforeTapBytes = bytes.beforeTap
const shot = (name) => page.screenshot({ path: path.join(A.out, `${A.scenario}-${name}.png`), fullPage: false })
await shot('before-tap')
check('no_gate_on_link', (await page.getByLabel('Access code').count()) === 0, null)

tapAt = Date.now()
const tapPerf = await page.evaluate(() => performance.now())
await page.locator('[data-stage="orb"]').click()
const greeted = await waitFor(async () => (await audit()).buffers.length > 0, 30000)
const a0 = await audit()
R.firstAudioAfterTapMs = greeted ? Math.round(a0.buffers[0].t - tapPerf) : null
check('opening_plays_inside_the_tap', greeted, { firstAudioAfterTapMs: R.firstAudioAfterTapMs })
await waitFor(async () => turns.some((t) => t.kind === 'greet' && t.complete), 30000)
await sleep(800)
const modelsLoaded = await waitFor(async () => bytes.models > 0, 30000)
R.notes.push(`models fetched ${modelsLoaded} bytes=${bytes.models}`)
await shot('after-tap')

const greet = turns.find((t) => t.kind === 'greet')
const greetBufs = (await audit()).buffers.filter((b) => b.t >= tapPerf)
R.opening = { said: greet?.said, audioMs: Math.round(playMs(greetBufs)) }
check('opening_is_about_5s', R.opening.audioMs > 0 && R.opening.audioMs <= 7000 && !/train/i.test(greet?.said ?? ''), R.opening)

if (A.scenario === 'vf-echo') {
  // The mic hears the opening's own words at a lower level while it plays: no barge-in.
  await sleep(6000)
  await sync()
  const a = await audit()
  const cut = a.stops.filter((t) => t > tapPerf)
  const spokenTurns = turns.filter((t) => t.kind === 'audio' || t.kind === 'spoken-text')
  check('playback_alone_does_not_self_trigger', cut.length === 0 && !greet?.failed, {
    stops: cut.length, greetFailed: greet?.failed ?? null, openingMs: R.opening.audioMs, turnsAfter: spokenTurns.map((t) => t.heard),
  })
}

if (A.scenario === 'vf-barge') {
  // A question (in the WAV after the opening), its long answer, then the person talks over it.
  const asked = await waitFor(async () => turns.some((t) => (t.kind === 'audio' || t.kind === 'spoken-text') && t.complete !== undefined), 90000)
  const q = turns.find((t) => t.kind === 'audio' || t.kind === 'spoken-text')
  const playing = await waitFor(async () => (await audit()).buffers.some((b) => q?.at !== undefined && b.t > q.at), 60000)
  const overlapped = await waitFor(async () => turns.filter((t) => t.kind === 'audio' || t.kind === 'spoken-text').length >= 2, 90000)
  await sync()
  const a = await audit()
  const second = turns.filter((t) => t.kind === 'audio' || t.kind === 'spoken-text')[1]
  const stopsBefore = a.stops.filter((t) => q?.at !== undefined && t > q.at && (second?.at === undefined || t < second.at))
  check('barge_in_stops_playback_and_cancels_turn', asked && playing && overlapped && stopsBefore.length > 0 && (q.complete === false || Boolean(q.failed) || q.done?.status === 'interrupted' || !q.complete), {
    first: { heard: q?.heard, said: (q?.said ?? '').slice(0, 120), failed: q?.failed ?? null, complete: q?.complete },
    second: { heard: second?.heard }, stops: stopsBefore.length,
  })
  await waitFor(async () => second?.complete, 60000)
  check('barge_in_posts_a_cancel_and_the_next_turn_is_answered', cancels.some((c) => c.status === 204) && second?.status === 200 && Boolean(second?.said), {
    cancels, second: { status: second?.status, said: (second?.said ?? '').slice(0, 160), done: second?.done },
  })
  await shot('barge')
}

if (A.scenario === 'vf-tap') {
  // A spoken question; while its answer plays, tap the orb and at once type a new question.
  const asked = await waitFor(async () => turns.some((t) => t.kind === 'audio' || t.kind === 'spoken-text'), 90000)
  const q = turns.find((t) => t.kind === 'audio' || t.kind === 'spoken-text')
  // Tap only once the orb offers "Interrupt" (the answer is playing), and note whether the first
  // stream had already ended: then no cancel is expected.
  const orb = page.locator('[data-stage="orb"]')
  const playing = await waitFor(async () => /^Interrupt/.test((await orb.getAttribute('aria-label')) ?? ''), 60000)
  await sync()
  const firstEndedAtTap = Boolean(q?.complete)
  // A diagram the answer opened covers the orb (seen 2026-11-17): noted, closed, then the tap.
  // A diagram the answer opened covers the orb (seen 2026-11-17): the dialog's own Interrupt
  // control is the tap then; the dialog is closed afterwards to reach the typed box.
  const dialog = page.locator('[role="dialog"][data-state="open"]')
  const modalOpen = (await dialog.count()) > 0
  R.tapVia = modalOpen ? 'dialog' : 'orb'
  if (modalOpen) {
    await dialog.getByRole('button', { name: 'Interrupt', exact: true }).click({ timeout: 5000 })
    await page.keyboard.press('Escape')
  } else {
    await orb.click({ timeout: 5000 })
  }
  await page.getByLabel(/^Type to /).fill('When is the car due for service?')
  await page.getByLabel(/^Type to /).press('Enter')
  const answered = await waitFor(async () => turns.some((t) => t.kind === 'text' && t.status === 200 && t.complete), 90000)
  await sync()
  const texts = turns.filter((t) => t.kind === 'text')
  const next = texts.find((t) => t.status === 200) ?? texts[texts.length - 1]
  const busyBefore = texts.filter((t) => t.status === 409).length
  const a = await audit()
  const nextAudio = a.buffers.filter((b) => next?.at !== undefined && b.t > next.at).length
  check('tap_then_new_question_is_answered', asked && playing && answered && next?.status === 200 && Boolean(next?.said) && next?.done?.status !== 'cancelled', {
    first: { heard: q?.heard, done: q?.done, failed: q?.failed ?? null, endedAtTap: firstEndedAtTap }, cancels, busyBefore, tapVia: R.tapVia,
    next: { status: next?.status, said: (next?.said ?? '').slice(0, 160), done: next?.done, audioChunks: nextAudio },
  })
  check('tap_posts_a_cancel', firstEndedAtTap || cancels.some((c) => c.status === 204), { cancels, firstEndedAtTap })
  await shot('tap')
}

if (A.scenario.startsWith('vf-eot')) {
  // The WAV: silence to `lead` s after the mic opens, the question (`speechMs`), then silence.
  const posted = await waitFor(async () => turns.some((t) => t.kind === 'audio' || t.kind === 'spoken-text'), 60000)
  await sync()
  const a = await audit()
  const t = turns.find((x) => x.kind === 'audio' || x.kind === 'spoken-text')
  const speechEnd = a.micAt + (A.lead + A.speechMs / 1000) * 1000
  R.endOfTurn = { postedAfterSpeechEndMs: posted && t?.at ? Math.round(t.at - speechEnd) : null, kind: t?.kind, modelsLoaded: bytes.models > 0 }
  await waitFor(async () => t?.complete, 60000)
  const replyBuf = (await audit()).buffers.find((b) => t?.at !== undefined && b.t > t.at)
  R.endOfTurn.firstAudioAfterSpeechEndMs = replyBuf ? Math.round(replyBuf.t - speechEnd) : null
  R.endOfTurn.heard = t?.heard
  R.endOfTurn.transcribes = turns.filter((x) => x.kind === 'transcribe').length
  check('end_of_turn_measured', R.endOfTurn.postedAfterSpeechEndMs !== null, R.endOfTurn)
  await shot('answer')
}

await sync().catch(() => {})
R.weight.afterTapBytes = bytes.afterTap
R.weight.modelBytes = bytes.models
check('nothing_from_another_host', R.foreign.length === 0, R.foreign)
R.turns = turns.map(({ req, ...t }) => t)
R.cancels = cancels
fs.writeFileSync(path.join(A.out, `${A.scenario}.json`), JSON.stringify(R, null, 1))
await browser.close()
console.log(JSON.stringify(R.checks))
