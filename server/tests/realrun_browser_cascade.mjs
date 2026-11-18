// One browser scenario for `realrun_browser_cascade.py` (ADR 0102): a real headless Chromium with
// Chrome's fake microphone playing a WAV, against the local engine. Writes <out>/<scenario>.json
// and screenshots. Never run against a deployed engine. Usage: node <this> '<json args>'
import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'

const A = JSON.parse(process.argv[2])
const require = createRequire(path.join(process.env.NODE_PATH, 'noop.js'))
const { chromium } = require('playwright')
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const t0 = Date.now()
const now = () => Date.now() - t0
const R = { scenario: A.scenario, checks: {}, turns: [], console: [], foreign: [], leaks: [], notes: [] }
const check = (name, ok, detail) => { R.checks[name] = { ok: Boolean(ok), detail } }

const browser = await chromium.launch({
  headless: true,
  args: [
    '--use-fake-ui-for-media-stream',
    '--use-fake-device-for-media-stream',
    `--use-file-for-fake-audio-capture=${A.wav}`,
    '--autoplay-policy=user-gesture-required',
  ],
})
const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, serviceWorkers: 'block' })
await context.grantPermissions(['microphone'], { origin: A.base })
const page = await context.newPage()

// Instrument Web Audio: every buffer scheduled for playback, and every context's state.
await page.addInitScript(() => {
  const audit = { contexts: [], buffers: [] }
  window.__audit = audit
  const Base = window.AudioContext
  window.AudioContext = class extends Base {
    constructor(options) { super(options); audit.contexts.push(this) }
  }
  // The turn's Server-Sent Events, read from a clone of each response (CDP cannot read a stream).
  window.__turnBodies = []
  const realFetch = window.fetch
  window.fetch = async function (input, init) {
    const res = await realFetch.call(this, input, init)
    const url = typeof input === 'string' ? input : input.url
    if (url && url.includes('/api/assistant/turn')) {
      const entry = { at: performance.now(), status: res.status, text: '', done: false, error: null }
      window.__turnBodies.push(entry)
      res.clone().text().then((t) => { entry.text = t; entry.done = true }, (e) => { entry.error = String(e); entry.done = true })
    }
    return res
  }
  const start = AudioBufferSourceNode.prototype.start
  AudioBufferSourceNode.prototype.start = function (...args) {
    const b = this.buffer
    if (b) audit.buffers.push({ t: performance.now(), len: b.length, rate: b.sampleRate, state: this.context.state })
    return start.apply(this, args)
  }
})
const audit = () => page.evaluate(() => ({
  states: window.__audit.contexts.map((c) => c.state),
  buffers: window.__audit.buffers,
  origin: performance.timeOrigin,
}))

page.on('console', (m) => { if (m.type() === 'error') R.console.push(m.text().slice(0, 300)) })
page.on('pageerror', (e) => R.console.push(`pageerror ${String(e).slice(0, 300)}`))
const turns = []
page.on('request', (req) => {
  const url = new URL(req.url())
  if (!url.host.startsWith('localhost') && !url.host.startsWith('127.0.0.1') && !url.protocol.startsWith('data')) R.foreign.push(req.url())
  if (url.pathname === '/api/assistant/turn' && req.method() === 'POST') {
    const body = req.postDataBuffer() ?? Buffer.alloc(0)
    const s = body.toString('latin1')
    // A Blob part's bytes may be absent from the captured body, so a turn with neither greet nor text is the utterance.
    const kind = s.includes('name="greet"') ? 'greet' : s.includes('name="text"') ? 'text' : 'audio'
    const turn = { kind, at: now(), bytes: body.length, req }
    turns.push(turn)
  }
})
page.on('requestfailed', (req) => {
  const turn = turns.find((t) => t.req === req)
  if (turn) { turn.failed = req.failure()?.errorText ?? 'failed'; turn.failedAt = now() }
})
R.httpErrors = []
page.on('response', async (res) => {
  const url = new URL(res.url())
  if (res.status() >= 400) R.httpErrors.push(`${res.request().method()} ${url.pathname} ${res.status()}`)
  if (!url.pathname.startsWith('/api/')) return
  let text = ''
  try { text = (await res.body()).toString('utf8') } catch (e) { R.notes.push(`body ${url.pathname}: ${String(e).slice(0, 120)}`); return }
  if (/AIza[0-9A-Za-z_-]{10}|auth_tokens\//.test(text)) R.leaks.push(url.pathname)
  void text

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
  const bodies = await page.evaluate(() => window.__turnBodies.map((b) => ({ done: b.done, text: b.text, status: b.status, error: b.error })))
  bodies.forEach((b, i) => {
    const turn = turns[i]
    if (!turn || !b.done) return
    if (b.error) turn.failed = turn.failed ?? b.error
    turn.status = b.status
    if (turn.doneAt === undefined) turn.doneAt = now()
    const events = parse(b.text)
    turn.heard = events.find((e) => e.kind === 'heard')?.data.text
    turn.said = events.filter((e) => e.kind === 'sentence').map((e) => e.data.text).join(' ')
    turn.tools = events.filter((e) => e.kind === 'tool').map((e) => `${e.data.name}:${e.data.ok}`)
    turn.audioEvents = events.filter((e) => e.kind === 'audio').length
    turn.end = events.find((e) => e.kind === 'done')?.data.end === true
    turn.conversation = events.find((e) => e.kind === 'turn')?.data.conversation_id
    turn.greeting = events.find((e) => e.kind === 'greeting')?.data
    turn.complete = events.some((e) => e.kind === 'done' || e.kind === 'error')
  })
}
const engine = page.waitForResponse((r) => r.url().endsWith('/api/assistant/session') && r.request().method() === 'GET', { timeout: 60000 })
const csrf = async () => (await context.cookies()).find((c) => c.name === 'csrftoken')?.value
async function post(p, body) {
  return page.evaluate(async ([p, body, token]) => {
    const r = await fetch(p, { method: 'POST', headers: { 'content-type': 'application/json', 'x-csrftoken': token }, body: JSON.stringify(body) })
    return r.status
  }, [p, body, await csrf()])
}

const rec = A.scenario.startsWith('rec')
if (rec) {
  await page.goto(`${A.base}/try`)
  await page.evaluate(() => fetch('/api/auth/csrf'))
  const entered = await post('/api/demo/enter', { code: A.code, ref: 'browser-check' })
  R.notes.push(`enter ${entered}`)
  await page.goto(`${A.base}/try`)
} else {
  await page.goto(`${A.base}/login`)
  await page.evaluate(() => fetch('/api/auth/csrf'))
  const logged = await post('/api/auth/session/login', { email: A.email, password: A.password })
  R.notes.push(`login ${logged}`)
  await page.goto(`${A.base}/`)
}
const session = await (await engine).json()
check('session_engine_cascade', session.engine === 'cascade', session.engine)
await page.waitForTimeout(1500)

const dock = rec ? page : page.getByRole('complementary', { name: /^Conversation with/ })
if (!rec) {
  await page.keyboard.press('/')
  await page.waitForTimeout(500)
}
const talk = async () => {
  if (rec) await page.locator('[data-chat="orb"]').click()
  else await dock.getByRole('button', { name: /^Talk to / }).click()
}
const logText = async () => page.evaluate(() => {
  // The desk's voice-first chat keeps its log folded: the orb's own label says the mic is closed.
  const orb = document.querySelector('[data-chat="orb"]')
  const closedOrb = orb && /^Talk to /.test(orb.getAttribute('aria-label') || '') ? ' [orb: Conversation closed]' : ''
  return (document.body.textContent || '') + closedOrb
}).catch(() => '')
const waitFor = async (fn, ms) => { const end = Date.now() + ms; while (Date.now() < end) { await sync().catch(() => {}); if (await fn()) return true; await sleep(250) } return false }
const playedAfter = (a, from) => a.buffers.filter((b) => b.t >= from)
const ms = (a, bufs) => bufs.reduce((n, b) => n + (b.len / b.rate) * 1000, 0)

async function typed(text) {
  if (rec) {
    const toggle = page.locator('[data-chat="toggle"]')
    if (await toggle.count()) {
      const box = page.locator('[data-chat="form"] input')
      if (!(await box.isVisible().catch(() => false))) await toggle.click()
    }
    await page.locator('[data-chat="form"] input').fill(text)
    await page.locator('[data-chat="form"] input').press('Enter')
  } else {
    const box = dock.getByRole('textbox')
    await box.fill(text)
    await box.press('Enter')
  }
}

// ---- the opening tap: the greeting must play ----------------------------------------------
if (A.scenario !== 'rec-limit') {
  const before = (await audit()).buffers.length
  await talk()
  const greeted = await waitFor(async () => turns.some((t) => t.kind === 'greet' && t.doneAt), 30000)
  await sleep(1500)
  const a = await audit()
  const g = a.buffers.slice(before)
  check('greeting_plays', greeted && g.length > 0 && a.states.includes('running'),
    { frames: g.reduce((n, b) => n + b.len, 0), ms: Math.round(ms(a, g)), states: a.states, text: turns.find((t) => t.kind === 'greet')?.said })
}

if (A.scenario.endsWith('silence')) {
  // No speech: the 8 s window lapses after the greeting, with the tone and the note.
  const closed = await waitFor(async () => (await logText()).includes('Conversation closed'), 60000)
  const a = await audit()
  const tone = a.buffers.find((b) => b.len === 3600 && b.rate === 24000)
  check('window_lapses_with_tone', closed && tone, { closed, tone: Boolean(tone) })
  check('no_turn_without_speech', !turns.some((t) => t.kind === 'audio'), turns.map((t) => t.kind))
  // A typed turn: answered in text, no audio.
  const n = (await audit()).buffers.length
  await typed("What's on the grocery list?")
  const answered = await waitFor(async () => turns.some((t) => t.kind === 'text' && t.doneAt), 40000)
  await sleep(1000)
  const after = (await audit()).buffers.length
  const tt = turns.find((t) => t.kind === 'text')
  check('typed_turn', answered && tt?.said && after === n && tt.audioEvents === 0, { said: tt?.said, audioEvents: tt?.audioEvents, newBuffers: after - n })
  await page.screenshot({ path: path.join(A.out, `${A.scenario}.png`) })
}

if (A.scenario.endsWith('question')) {
  // The question loops on the fake mic: it becomes a turn, the reply plays, the mic re-opens.
  const first = await waitFor(async () => turns.some((t) => t.kind === 'audio' && t.doneAt), 90000)
  const q = turns.find((t) => t.kind === 'audio')
  await sleep(1500)
  await page.screenshot({ path: path.join(A.out, `${A.scenario}-reply.png`) })
  check('spoken_turn', first && q?.heard && q?.said && q.audioEvents > 0, { heard: q?.heard, said: q?.said, tools: q?.tools })
  // Re-listen: a second spoken turn arrives without any tap.
  const second = await waitFor(async () => turns.filter((t) => t.kind === 'audio').length >= 2, 90000)
  check('mic_reopens_after_reply', second, turns.map((t) => `${t.kind}@${t.at}`))
  // Half-duplex: every spoken turn was posted after the previous reply's audio had played out,
  // and none heard the assistant's own words.
  const a = await audit()
  const origin = a.origin - t0
  const spoken = turns.filter((t) => t.kind === 'audio')
  const overlaps = []
  for (const t of spoken) {
    const prior = a.buffers.filter((b) => b.t + origin < t.at)
    if (!prior.length) continue
    let end = 0
    for (const b of prior) end = Math.max(end, b.t + origin) + 0
    const lastStart = Math.max(...prior.map((b) => b.t + origin))
    const tail = prior.filter((b) => b.t + origin >= lastStart - 5000)
    const playedOut = lastStart + (tail.at(-1).len / tail.at(-1).rate) * 1000
    if (t.at < playedOut - 300) overlaps.push({ posted: t.at, playedOut: Math.round(playedOut) })
  }
  const echoed = spoken.filter((t, i) => i > 0 && t.heard && spoken[i - 1].said && spoken[i - 1].said.toLowerCase().includes(t.heard.toLowerCase().slice(0, 25)))
  check('half_duplex', overlaps.length === 0 && echoed.length === 0, { overlaps, echoed: echoed.map((t) => t.heard) })
  // Interrupt: click while the second reply plays; the request is aborted and listening resumes.
  const playing = await waitFor(async () => {
    const t = spoken[1]
    const b = (await audit()).buffers.filter((x) => x.t + origin > (t?.at ?? Infinity))
    return b.length > 0
  }, 60000)
  if (playing) {
    // A diagram card may have opened over the page (show_diagram): close it first, as a person would.
    await page.keyboard.press('Escape')
    await sleep(200)
    const t = spoken[1]
    await sync().catch(() => {})
    const streamingAtClick = !t.complete
    const clickAt = now()
    // A tap while it speaks: the orb on the desk, the Talk button in the member's dock.
    if (rec) await page.locator('[data-chat="orb"]').click()
    else await dock.getByRole('button', { name: /^Talk to / }).click()
    await sleep(1500)
    await sync().catch(() => {})
    const after = (await audit()).buffers.filter((b) => b.t + origin > clickAt + 300).length
    check('interrupt_stops_reply', after === 0 && (!streamingAtClick || Boolean(t.failed)),
      { streamingAtClick, aborted: t.failed ?? null, buffersAfterClick: after })
    // After the interrupt the page listens again; with nothing more said the window lapses.
    const relisten = await waitFor(async () => (await logText()).includes('Conversation closed'), 60000)
    const a2 = await audit()
    check('listens_after_interrupt_then_lapses', relisten && a2.buffers.some((b) => b.len === 3600 && b.t + origin > clickAt), relisten)
  } else {
    check('interrupt_aborts_turn', false, 'the second reply never played')
  }
}

if (A.scenario.endsWith('stop')) {
  const ended = await waitFor(async () => turns.some((t) => t.kind === 'audio' && t.end), 90000)
  const closed = await waitFor(async () => (await logText()).includes('Conversation closed'), 30000)
  const t = turns.find((x) => x.kind === 'audio')
  check('stop_phrase_closes', ended && closed, { heard: t?.heard, said: t?.said, end: t?.end, closed })
  await page.screenshot({ path: path.join(A.out, `${A.scenario}.png`) })
}

if (A.scenario === 'rec-limit') {
  // LEGION_DEMO_MAX_SESSION_MINUTES=1 on the engine: the page ends the conversation at 1 minute.
  check('session_minutes_1', session.session_minutes === 1, session.session_minutes)
  await typed('Hello there.')
  await waitFor(async () => turns.some((t) => t.kind === 'text' && t.doneAt), 40000)
  const conv = turns.find((t) => t.kind === 'text')?.conversation
  const ended = await waitFor(async () => (await page.locator('body').innerText()).includes('Demo sessions last 5 minutes'), 75000)
  const late = await page.evaluate(async ([conv, token]) => {
    const f = new FormData(); f.append('text', 'Still there?'); f.append('conversation_id', conv)
    const r = await fetch('/api/assistant/turn', { method: 'POST', headers: { 'x-csrftoken': token }, body: f })
    return r.status
  }, [conv, await csrf()])
  check('demo_limit_ends', ended && late === 409, { pageEnded: ended, serverStatus: late })
  await page.screenshot({ path: path.join(A.out, `${A.scenario}.png`) })
}

check('no_key_or_token_to_browser', R.leaks.length === 0 && R.foreign.length === 0, { leaks: R.leaks, foreign: R.foreign })
await sync().catch(() => {})
// Known loads that fail before a visitor enters or with no demo content loaded in a test database.
const EXPECTED = ['/api/auth/me 401', '/api/demo/session 403', '/api/demo/content 404', ...(A.scenario === 'rec-limit' ? ['/api/assistant/turn 409'] : [])]
R.unexpectedHttp = (R.httpErrors || []).filter((e) => !EXPECTED.some((x) => e.endsWith(x)))
R.checks.no_console_errors = {
  ok: R.unexpectedHttp.length === 0 && R.console.filter((c) => !c.startsWith('Failed to load resource')).length === 0,
  detail: { console: R.console, http: R.httpErrors, unexpected: R.unexpectedHttp },
}
R.turns = turns.map(({ req, ...t }) => t)
fs.writeFileSync(path.join(A.out, `${A.scenario}.json`), JSON.stringify(R, null, 1))
await browser.close()
console.log(JSON.stringify(R.checks))
