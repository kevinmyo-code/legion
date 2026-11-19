// One width for `realrun_try_orb_flow.py`: the /try tour, a diagram asked by a typed question, and the
// chips under the orb, with what the page did frame by frame. Prints one JSON line and writes
// <out>/orbflow-<tag>-<width>.json plus frames and screenshots. Never run against a deployed engine.
// Usage: node <this> '<json args>'
//
// Evidence kept per width (Kevin, 2026-11-19: "the diagrams are flickering hard"):
//  - every element of interest added or removed (the dock, the dialog, the diagram's host and its
//    SVG, the turn-your-phone hint, the modal's voice bar), with the time;
//  - every animation frame where the page's state changed (scroll, dock shown, the fit box's size,
//    the body's bottom padding), so a toggle storm shows as a run of alternating samples;
//  - the browser's layout-shift entries;
//  - the compositor's own frames (CDP screencast) around each diagram opening.
import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'

const A = JSON.parse(process.argv[2])
const require = createRequire(path.join(process.env.NODE_PATH, 'noop.js'))
const { chromium } = require('playwright')
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const mobile = A.width < 600
const name = (what) => `orbflow-${A.tag}-${A.width}-${what}`
const R = { width: A.width, height: A.height, tag: A.tag, console: [], phases: {}, turns: [] }

const browser = await chromium.launch({
  headless: true,
  args: [
    '--use-fake-ui-for-media-stream',
    '--use-fake-device-for-media-stream',
    ...(A.wav ? [`--use-file-for-fake-audio-capture=${A.wav}%noloop`] : []),
    '--autoplay-policy=no-user-gesture-required',
  ],
})
const context = await browser.newContext({
  viewport: { width: A.width, height: A.height },
  isMobile: mobile,
  hasTouch: mobile,
  deviceScaleFactor: mobile ? 2 : 1,
  serviceWorkers: 'block',
})
await context.grantPermissions(['microphone'], { origin: A.base })
const page = await context.newPage()
page.on('console', (m) => { if (m.type() === 'error') R.console.push(m.text().slice(0, 300)) })
page.on('pageerror', (e) => R.console.push(`pageerror ${String(e).slice(0, 300)}`))

await page.addInitScript(() => {
  const now = () => performance.timeOrigin + performance.now()
  const P = { muts: [], shifts: [], samples: [], counts: {} }
  window.__probe = P
  const SEL = {
    dock: '[data-stage="dock"]',
    dialog: '[role="dialog"]',
    diagramHost: '[data-diagram="full"]',
    fitHint: '[data-fit-hint]',
    modalVoice: '[data-modal="voice"]',
    chips: '[data-stage="chips"]',
    strip: '[data-stage="strip"]',
    stage: '[aria-label="Talk to the demo assistant"]',
  }
  // Each node counts once per add and once per remove, however many records name it (a host and the
  // SVG drawn into it in the same commit arrive as two records).
  const seen = { add: new WeakSet(), remove: new WeakSet() }
  const bump = (key, kind, node) => {
    if (node) {
      if (seen[kind].has(node)) return
      seen[kind].add(node)
      if (kind === 'add') seen.remove.delete(node)
      else seen.add.delete(node)
    }
    const c = (P.counts[key] ??= { add: 0, remove: 0 })
    c[kind] += 1
    P.muts.push({ t: now(), key, kind })
  }
  const scan = (node, kind, target) => {
    if (!(node instanceof Element)) return
    for (const [key, sel] of Object.entries(SEL)) {
      if (node.matches(sel)) bump(key, kind, node)
      for (const el of node.querySelectorAll(sel)) bump(key, kind, el)
    }
    // The diagram's SVG: drawn into its host (`useSvg`), so its parent is the host.
    if (node.tagName.toLowerCase() === 'svg' && target instanceof Element && target.matches(SEL.diagramHost)) bump('diagramSvg', kind, node)
    else for (const el of node.querySelectorAll(`${SEL.diagramHost} > svg`)) bump('diagramSvg', kind, el)
  }
  // Who moves the page: every programmatic scroll and history change, with where it came from.
  P.calls = []
  const where = () => (new Error().stack || '').split('\n').slice(2, 7).map((l) => l.trim().replace(/https?:\/\/[^/]+/, '')).join(' < ')
  const wrap = (obj, name, label) => {
    const real = obj[name]
    if (typeof real !== 'function') return
    obj[name] = function (...args) {
      P.calls.push({ t: now(), call: label, y: Math.round(scrollY), args: JSON.stringify(args).slice(0, 80), from: where() })
      return real.apply(this, args)
    }
  }
  wrap(window, 'scrollTo', 'window.scrollTo')
  wrap(window, 'scroll', 'window.scroll')
  wrap(Element.prototype, 'scrollIntoView', 'el.scrollIntoView')
  wrap(History.prototype, 'pushState', 'history.pushState')
  wrap(History.prototype, 'replaceState', 'history.replaceState')
  wrap(History.prototype, 'back', 'history.back')
  wrap(HTMLElement.prototype, 'focus', 'el.focus')
  addEventListener('popstate', () => P.calls.push({ t: now(), call: 'popstate', y: Math.round(scrollY) }))
  addEventListener('scroll', () => {
    const y = Math.round(scrollY)
    if (P.lastScrollY !== undefined && Math.abs(y - P.lastScrollY) > 120) P.calls.push({ t: now(), call: 'scroll-jump', y, from: P.lastScrollY })
    P.lastScrollY = y
  }, { passive: true })
  const start = () => {
    new MutationObserver((records) => {
      for (const m of records) {
        for (const n of m.addedNodes) scan(n, 'add', m.target)
        for (const n of m.removedNodes) scan(n, 'remove', m.target)
      }
    }).observe(document.documentElement, { childList: true, subtree: true })
    try {
      new PerformanceObserver((list) => {
        for (const e of list.getEntries()) {
          P.shifts.push({
            t: performance.timeOrigin + e.startTime,
            v: Math.round(e.value * 10000) / 10000,
            input: e.hadRecentInput,
            sources: (e.sources || []).slice(0, 4).map((s) => {
              const n = s.node
              if (!(n instanceof Element)) return n ? n.nodeName : '?'
              const tag = n.tagName.toLowerCase()
              const stage = n.getAttribute('data-stage') || n.getAttribute('data-modal') || ''
              return `${tag}${stage ? `[${stage}]` : ''}.${String(n.className?.baseVal ?? n.className).slice(0, 30)}`
            }),
          })
        }
      }).observe({ type: 'layout-shift', buffered: true })
    } catch {}
    let last = ''
    const sample = () => {
      const dock = document.querySelector(SEL.dock)
      const dockOrb = document.querySelector('[data-stage="dock-orb"]')
      const orb = document.querySelector('[data-stage="orb"]')
      const box = document.querySelector('[data-fit-box]')
      const svg = document.querySelector(`${SEL.diagramHost} > svg`)
      const o = orb?.getBoundingClientRect()
      const d = dockOrb?.getBoundingClientRect()
      const s = {
        y: Math.round(scrollY),
        // Mounted and shown (a dock that stays mounted says so in `data-docked`).
        dock: Boolean(dock) && dock.getAttribute('data-docked') !== 'false',
        dockOpacity: dock ? getComputedStyle(dock).opacity : null,
        dockOrb: d ? [Math.round(d.left), Math.round(d.top), Math.round(d.width)] : null,
        orb: o ? [Math.round(o.left), Math.round(o.top), Math.round(o.width)] : null,
        orbOpacity: orb ? getComputedStyle(orb).opacity : null,
        dlg: Boolean(document.querySelector(SEL.dialog)),
        fit: box ? [box.clientWidth, box.clientHeight] : null,
        hint: Boolean(document.querySelector(SEL.fitHint)),
        pad: document.body.style.paddingBottom,
        svg: svg ? svg.style.width : null,
        h: document.documentElement.scrollHeight,
      }
      const key = JSON.stringify(s)
      if (key !== last) {
        last = key
        P.samples.push({ t: now(), ...s })
        if (P.samples.length > 20000) P.samples.splice(0, 5000)
      }
      requestAnimationFrame(sample)
    }
    requestAnimationFrame(sample)
  }
  if (document.documentElement) start()
  else document.addEventListener('DOMContentLoaded', start)

  // The turn streams, for what each turn said and cost (the run's spend).
  window.__turnBodies = []
  const realFetch = window.fetch
  window.fetch = async function (input, init) {
    const url = typeof input === 'string' ? input : input.url
    const res = await realFetch.call(this, input, init)
    if (url && url.includes('/api/assistant/turn') && !url.includes('/turn/cancel')) {
      const entry = { at: now(), status: res.status, text: '', done: false }
      window.__turnBodies.push(entry)
      res.clone().text().then((t) => { entry.text = t; entry.done = true }, () => { entry.done = true })
    }
    return res
  }
})

// ---- the compositor's frames ------------------------------------------------------------------
const cdp = await context.newCDPSession(page)
const frames = []
cdp.on('Page.screencastFrame', async (f) => {
  frames.push({ t: f.metadata.timestamp * 1000, data: f.data })
  if (frames.length > 4000) frames.splice(0, 1000)
  try { await cdp.send('Page.screencastFrameAck', { sessionId: f.sessionId }) } catch {}
})
await cdp.send('Page.startScreencast', { format: 'jpeg', quality: 55, maxWidth: mobile ? 390 : 640, everyNthFrame: 1 })

/** Writes the frames between `from` and `to` (epoch ms) as <name>-fNNN-<ms>.jpg, at most `cap`. */
function writeFrames(label, from, to, cap = 120) {
  const picked = frames.filter((f) => f.t >= from && f.t <= to).slice(0, cap)
  picked.forEach((f, i) => {
    const ms = Math.round(f.t - from)
    fs.writeFileSync(path.join(A.out, 'frames', `${name(label)}-f${String(i).padStart(3, '0')}-${ms}ms.jpg`), Buffer.from(f.data, 'base64'))
  })
  return picked.length
}
fs.mkdirSync(path.join(A.out, 'frames'), { recursive: true })

function parse(text) {
  return text.split('\n\n').filter(Boolean).map((b) => {
    const kind = b.split('\n')[0].replace('event: ', '')
    let data = {}
    try { data = JSON.parse(b.split('\n').slice(1).join('').replace('data: ', '')) } catch {}
    return { kind, data }
  })
}
const turns = async () =>
  (await page.evaluate(() => window.__turnBodies.map((b) => ({ at: b.at, status: b.status, done: b.done, text: b.text })))).map((b) => {
    const ev = b.done ? parse(b.text) : []
    const done = ev.find((e) => e.kind === 'done')?.data
    return {
      at: b.at,
      status: b.status,
      complete: ev.some((e) => e.kind === 'done' || e.kind === 'error'),
      said: ev.filter((e) => e.kind === 'sentence').map((e) => e.data.text).join(' ').slice(0, 160),
      tools: ev.filter((e) => e.kind === 'tool').map((e) => e.data.name),
      tour: done?.tour ?? null,
      cost: typeof done?.cost_usd === 'number' ? done.cost_usd : null,
      keys: done ? Object.keys(done) : [],
    }
  })
const probe = () => page.evaluate(() => window.__probe)
const wall = () => page.evaluate(() => performance.timeOrigin + performance.now())
const shot = (what) => page.screenshot({ path: path.join(A.out, `${name(what)}.png`) })

/** Toggles of a key between two times: how many times it was added and removed. */
function toggles(P, key, from, to) {
  const m = P.muts.filter((x) => x.key === key && x.t >= from && x.t <= to)
  return { add: m.filter((x) => x.kind === 'add').length, remove: m.filter((x) => x.kind === 'remove').length }
}
/** The longest run of samples in which `dock` flipped within `windowMs` of the previous flip. */
function storm(P, from, to, windowMs = 1000) {
  const s = P.samples.filter((x) => x.t >= from && x.t <= to)
  let flips = 0, best = 0, lastFlip = -Infinity, prev = null
  for (const x of s) {
    if (prev !== null && x.dock !== prev) {
      flips = x.t - lastFlip <= windowMs ? flips + 1 : 1
      best = Math.max(best, flips)
      lastFlip = x.t
    }
    prev = x.dock
  }
  return best
}
function summary(P, from, to) {
  const shifts = P.shifts.filter((x) => x.t >= from && x.t <= to && !x.input)
  const samples = P.samples.filter((x) => x.t >= from && x.t <= to)
  const fits = new Set(samples.filter((x) => x.fit).map((x) => x.fit.join('x')))
  const pads = new Set(samples.map((x) => x.pad))
  const ys = new Set(samples.map((x) => x.y))
  return {
    ms: Math.round(to - from),
    dock: toggles(P, 'dock', from, to),
    dockStorm: storm(P, from, to),
    dialog: toggles(P, 'dialog', from, to),
    diagramHost: toggles(P, 'diagramHost', from, to),
    diagramSvg: toggles(P, 'diagramSvg', from, to),
    fitHint: toggles(P, 'fitHint', from, to),
    modalVoice: toggles(P, 'modalVoice', from, to),
    layoutShift: { count: shifts.length, sum: Math.round(shifts.reduce((n, x) => n + x.v, 0) * 10000) / 10000, top: shifts.sort((a, b) => b.v - a.v).slice(0, 4) },
    fitSizes: [...fits].slice(0, 12),
    fitSizeCount: fits.size,
    pads: [...pads],
    scrollYs: [...ys].slice(0, 20),
    scrollYCount: ys.size,
    stage: toggles(P, 'stage', from, to),
    samples: samples.length,
    // Every programmatic scroll, focus and history change in the window, and every jump of the page.
    calls: (P.calls || []).filter((x) => x.t >= from && x.t <= to).map((x) => ({ ...x, t: Math.round(x.t - from) })).slice(0, 40),
    timeline: samples.map((x) => ({ t: Math.round(x.t - from), y: x.y, dock: x.dock, dlg: x.dlg, fit: x.fit, hint: x.hint, pad: x.pad })).slice(0, 60),
  }
}
/** Frames around each diagram drawn (the SVG added) between `from` and `to`. */
function framesAround(P, label, from, to) {
  const adds = P.muts.filter((x) => x.key === 'diagramSvg' && x.kind === 'add' && x.t >= from && x.t <= to)
  const opens = []
  for (const a of adds) if (!opens.length || a.t - opens[opens.length - 1] > 3000) opens.push(a.t)
  return opens.slice(0, 3).map((t, i) => writeFrames(`${label}-open${i + 1}`, t - 600, t + 2400))
}

// ---- open the link ----------------------------------------------------------------------------
await page.goto(`${A.base}/try?code=${encodeURIComponent(A.code)}&ref=orbflow`)
await page.locator('[data-stage="orb"]').waitFor({ timeout: 30000 })
await page.evaluate(() => document.fonts.ready)
await sleep(1500)
await shot('1-top')

let t0
let t1
let P
// ---- the one-minute tour: started from the stage, then the visitor scrolls on to read ---------
t0 = await wall()
await page.locator('[data-stage="tour"]').click()
const tourEnd = Date.now() + 150000
let finished = false
let scrolledOn = false
while (Date.now() < tourEnd) {
  const t = await turns()
  const tourTurns = t.filter((x) => x.tour !== null)
  // Once the first beat has been said, the visitor scrolls past the orb (it docks), as a reader does.
  if (!scrolledOn && tourTurns.some((x) => x.complete)) {
    scrolledOn = true
    await page.evaluate(() => {
      const orb = document.querySelector('[data-stage="orb"]').getBoundingClientRect()
      window.scrollTo(0, Math.round(scrollY + orb.bottom + 8))
    })
    R.phases.tourScrolledTo = await page.evaluate(() => Math.round(scrollY))
  }
  if (tourTurns.some((x) => x.complete && x.tour && x.tour.next === null)) { finished = true; break }
  if (t.some((x) => x.complete && x.status !== 200)) break
  // A shot while the first diagram is open, once.
  if (!R.phases.tourShot && (await page.locator('[data-diagram="full"] svg').count()) > 0) {
    R.phases.tourShot = true
    await sleep(1200)
    await shot('2-tour-diagram')
  }
  await sleep(300)
}
await sleep(4000)
t1 = await wall()
P = await probe()
R.phases.tour = { finished, ...summary(P, t0, t1), frames: framesAround(P, 'tour', t0, t1) }
await shot('3-tour-end')

// Close the modal the way a visitor does: what does the page do after?
t0 = await wall()
if ((await page.locator('[role="dialog"]').count()) > 0) await page.keyboard.press('Escape')
await sleep(2500)
t1 = await wall()
P = await probe()
R.phases.tourClose = summary(P, t0, t1)
await shot('4-tour-closed')

// ---- a diagram asked by a typed question, with the page scrolled so the orb has docked ----------
await page.evaluate(() => window.scrollTo(0, 0))
await sleep(500)
await page.evaluate(() => {
  const orb = document.querySelector('[data-stage="orb"]').getBoundingClientRect()
  window.scrollTo(0, Math.round(orb.bottom + 8))
})
await sleep(800)
R.phases.dockedBeforeAsk = await page.locator('[data-stage="dock"]').count()
t0 = await wall()
const box = page.locator('[data-stage="form"] input')
await box.fill(A.question)
await page.locator('[data-stage="form"] button[type="submit"]').click()
const askEnd = Date.now() + 90000
while (Date.now() < askEnd) {
  const t = await turns()
  const last = t[t.length - 1]
  if (last && last.complete && last.tour === null && last.at >= t0) break
  await sleep(300)
}
await sleep(4000)
await shot('5-asked-diagram')
t1 = await wall()
P = await probe()
R.phases.ask = { ...summary(P, t0, t1), frames: framesAround(P, 'ask', t0, t1) }
t0 = await wall()
if ((await page.locator('[role="dialog"]').count()) > 0) await page.keyboard.press('Escape')
await sleep(2500)
t1 = await wall()
P = await probe()
R.phases.askClose = summary(P, t0, t1)
// The captions still show the newest words of the answer that streamed on under the modal.
// The captions box never scrolls (it would take the visitor's swipes from the page).
R.phases.captions = await page.evaluate(() => {
  const box = document.querySelector('[data-stage="captions"]')
  return box ? { overflowY: getComputedStyle(box).overflowY, top: Math.round(box.scrollTop) } : null
})
await shot('6-ask-closed')

// ---- the chips: where they sit, and the row's bounds at this width ------------------------------
R.chips = await page.evaluate(() => {
  const row = document.querySelector('[data-stage="chips"]')
  const orb = document.querySelector('[data-stage="orb"]')
  if (!row) return null
  const r = row.getBoundingClientRect()
  const o = orb.getBoundingClientRect()
  const buttons = [...row.querySelectorAll('button')].map((b) => {
    const x = b.getBoundingClientRect()
    return { h: Math.round(x.height), w: Math.round(x.width) }
  })
  const first = row.querySelector('button')
  return { top: Math.round(r.top), orbBottom: Math.round(o.bottom), left: Math.round(r.left), right: Math.round(r.right), buttons, overflow: document.documentElement.scrollWidth > innerWidth, radius: first ? getComputedStyle(first).borderTopLeftRadius : null }
})
await shot('10-chips')

// ---- the checks (the python side fails the run on any of them) --------------------------------
const ph = R.phases
// A jump right after this script's own scroll (the visitor scrolling on) is the visitor's.
const mine = (p, c) => (p.calls || []).some((x) => /evaluate/.test(x.from || '') && c.t >= x.t && c.t - x.t < 100)
const jumps = (p) =>
  (p.calls || []).filter(
    (c) => (c.call === 'scroll-jump' && !mine(p, c)) || (c.call === 'window.scrollTo' && /"top":0/.test(c.args || '') && !/evaluate/.test(c.from || '')),
  )
const chips = R.chips
R.checks = {
  // The chips: directly under the stage orb, each a 44 px tap target and a pill, the row never
  // wider than the page (review 2026-11-19: asserted here, not by matching the stylesheet).
  chipsUnderTheOrb: Boolean(chips) && chips.top >= chips.orbBottom && chips.top - chips.orbBottom <= 40,
  chipsTapTargets: Boolean(chips) && chips.buttons.length > 0 && chips.buttons.every((b) => b.h >= 44),
  chipsArePills: Boolean(chips) && chips.radius === '9999px',
  chipsNoPageOverflow: Boolean(chips) && chips.overflow === false,
  // The page under a diagram stays where the visitor put it, opening and closing.
  noScrollJumpWithDiagram: ['tour', 'tourClose', 'ask', 'askClose'].every((k) => jumps(ph[k]).length === 0),
  // The dock never flips back and forth (at most one change in any second, in every phase).
  noDockStorm: ['tour', 'tourClose', 'ask', 'askClose'].every((k) => ph[k].dockStorm <= 1),
  // While a diagram is open its host is never mounted again, and each diagram shown is drawn once.
  noDiagramRemount: ph.ask.diagramHost.add === 1 && ph.ask.diagramHost.remove === 0 && ph.ask.diagramSvg.add === 1 && ph.ask.diagramSvg.remove === 0,
  // Two diagrams in the tour, each drawn once into its own host (a card between them replaces the
  // first in the open modal, so the second gets a new host).
  tourDrawsEachDiagramOnce: ph.tour.diagramSvg.add === 2 && ph.tour.diagramHost.add === ph.tour.diagramSvg.add,
  // The voice stage is one element for the visit, the tap that enters it included.
  stageNeverRemounted: ph.tour.stage.add === 0 && ph.tour.stage.remove === 0,
  tourFinished: ph.tour.finished === true,
  captionsNeverScroll: !ph.captions || (ph.captions.overflowY === 'hidden' && ph.captions.top === 0),
}
R.turns = await turns()
R.spendUsd = Math.round(R.turns.reduce((n, x) => n + (x.cost ?? 0), 0) * 100000) / 100000
await cdp.send('Page.stopScreencast').catch(() => {})
await browser.close()
fs.writeFileSync(path.join(A.out, `${name('result')}.json`), JSON.stringify(R, null, 1))
console.log(JSON.stringify({ width: A.width, checks: R.checks, tour: R.phases.tour, tourClose: R.phases.tourClose, ask: R.phases.ask, askClose: R.phases.askClose, chips: R.chips, spendUsd: R.spendUsd, console: R.console.slice(0, 5) }))
