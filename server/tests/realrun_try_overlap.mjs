// One width for `realrun_try_overlap.py`: enter a demo visit, load /try, scroll, and record what the
// voice stage covers. Prints one JSON line. Usage: node <this> '<json args>'
import path from 'node:path'
import { createRequire } from 'node:module'

const A = JSON.parse(process.argv[2])
const require = createRequire(path.join(process.env.NODE_PATH, 'noop.js'))
const { chromium } = require('playwright')

const mobile = A.width < 600
const browser = await chromium.launch({
  args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream', '--autoplay-policy=no-user-gesture-required'],
})
const context = await browser.newContext({
  viewport: { width: A.width, height: A.height },
  isMobile: mobile,
  hasTouch: mobile,
  deviceScaleFactor: mobile ? 3 : 1,
})
const page = await context.newPage()
await page.goto(`${A.base}/try`)
const entered = await page.evaluate(async (code) => {
  await fetch('/api/auth/csrf', { credentials: 'same-origin' })
  const token = (document.cookie.match(/csrftoken=([^;]+)/) || [])[1] || ''
  const r = await fetch('/api/demo/enter', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', 'X-CSRFToken': token },
    body: JSON.stringify({ code, ref: 'overlap' }),
  })
  return r.status
}, A.code)
await page.goto(`${A.base}/try?code=${encodeURIComponent(A.code)}&ref=overlap`)
const stage = page.locator('[aria-label="Talk to the demo assistant"]')
try {
  await stage.waitFor({ timeout: 30000 })
} catch (e) {
  await page.screenshot({ path: path.join(A.out, `overlap-${A.tag}-${A.width}-fail.png`) })
  console.log(JSON.stringify({ width: A.width, entered, error: String(e).slice(0, 200), text: (await page.innerText('body')).slice(0, 300) }))
  await browser.close()
  process.exit(0)
}
await page.waitForTimeout(1500)
const shot = (name) => page.screenshot({ path: path.join(A.out, `overlap-${A.tag}-${A.width}-${name}.png`) })
await shot('top')

const measure = async () => {
  await page.waitForSelector('[aria-label="Talk to the demo assistant"]', { timeout: 15000 })
  return page.evaluate(() => {
    const stage = document.querySelector('[aria-label="Talk to the demo assistant"]')
    const r = stage.getBoundingClientRect()
    const style = getComputedStyle(stage)
    const covered = new Set()
    const top = Math.max(r.top, 0)
    const bottom = Math.min(r.bottom, window.innerHeight)
    for (let y = top + 4; y < bottom - 2; y += 12) {
      for (let x = r.left + 8; x < r.right - 4; x += 24) {
        for (const el of document.elementsFromPoint(x, y)) {
          if (stage.contains(el) || el.contains(stage)) continue
          const text = (el.innerText || '').trim()
          if (text && el.children.length === 0) covered.add(`${el.tagName.toLowerCase()}: ${text.slice(0, 50)}`)
        }
      }
    }
    return {
      position: style.position,
      top: Math.round(r.top),
      bottom: Math.round(r.bottom),
      height: Math.round(r.height),
      background: style.backgroundColor,
      zIndex: style.zIndex,
      scrollY: Math.round(window.scrollY),
      pageHeight: document.documentElement.scrollHeight,
      covered: [...covered].slice(0, 12),
    }
  })
}

const before = await measure()
await page.evaluate(() => window.scrollBy(0, Math.round(window.innerHeight * 0.8)))
await page.waitForTimeout(600)
const scrolled = await measure()
await shot('scrolled')

// The docked orb (Kevin, 2026-11-18): once the orb is scrolled away it follows as a footer. Checked
// at the page's foot and with the typed box focused: no page-width overflow, nothing under it.
const dockCheck = () =>
  page.evaluate(() => {
    // The dock stays mounted; shown is `data-docked` (2026-11-19).
    const dock = document.querySelector('[data-stage="dock"][data-docked="true"]')
    const r = dock ? dock.getBoundingClientRect() : null
    const covered = new Set()
    if (r) {
      for (let y = r.top + 4; y < r.bottom - 2; y += 8) {
        for (let x = r.left + 8; x < r.right - 4; x += 24) {
          for (const el of document.elementsFromPoint(x, y)) {
            if (dock.contains(el) || el.contains(dock)) continue
            const text = (el.innerText || '').trim()
            if (text && el.children.length === 0) covered.add(`${el.tagName.toLowerCase()}: ${text.slice(0, 40)}`)
          }
        }
      }
    }
    const box = document.querySelector('[data-stage="form"]')
    const b = box ? box.getBoundingClientRect() : null
    return {
      shown: Boolean(dock),
      background: dock ? getComputedStyle(dock).backgroundColor : null,
      dock: r && { top: Math.round(r.top), bottom: Math.round(r.bottom), height: Math.round(r.height) },
      bodyPad: getComputedStyle(document.body).paddingBottom,
      overflow: document.documentElement.scrollWidth > window.innerWidth,
      covered: [...covered].slice(0, 12),
      boxUnderDock: Boolean(r && b && b.bottom > r.top && b.top < r.bottom),
    }
  })
await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
await page.waitForTimeout(600)
const atFoot = await dockCheck()
await shot('dock-foot')
await page.locator('[data-stage="form"] input').focus()
await page.waitForTimeout(600)
const typing = await dockCheck()
await shot('dock-typing')
// While he speaks, the docked orb interrupts (the orb's own tap).
await page.evaluate(() => window.scrollTo(0, 0))
const orb = page.locator('[data-stage="orb"]')
await orb.click()
const speaking = await orb.evaluate(
  (el) => new Promise((resolve) => {
    const end = Date.now() + 30000
    const tick = () => (/^Interrupt/.test(el.getAttribute('aria-label') || '') ? resolve(true) : Date.now() > end ? resolve(false) : setTimeout(tick, 100))
    tick()
  }),
)
await page.evaluate(() => window.scrollBy(0, Math.round(window.innerHeight * 0.9)))
await page.waitForTimeout(600)
const dockOrb = page.locator('[data-stage="dock-orb"]')
const label = await dockOrb.getAttribute('aria-label').catch(() => null)
await shot('dock-speaking')
const dockSpeaking = { speaking, label, ...(await dockCheck()) }
await browser.close()
console.log(JSON.stringify({ width: A.width, height: A.height, entered, before, scrolled, atFoot, typing, dockSpeaking }))
