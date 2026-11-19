// PROTOTYPE (proto/try-scroll-story): the /try page as a scroll story. Not production code.
// Rules held: nothing runs per scroll event; IntersectionObserver decides where the orb is and which
// step a pinned scene shows, and CSS animates transform/opacity; the page scrolls at the visitor's
// own speed (no wheel or touch is captured); reduced motion gives a plain page.
import { OrbRenderer, parseColour, type OrbMode } from '../../src/components/assistant/orb'

const variant = new URLSearchParams(location.search).get('v') === 'b' ? 'b' : 'a'
document.body.classList.add(`v-${variant}`)
const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches

// ---- the orb's drawing: the real renderer the stage uses ---------------------------------------
const canvas = document.getElementById('orb-canvas') as HTMLCanvasElement
const ctx = canvas.getContext('2d')!
const renderer = new OrbRenderer()
let mode: OrbMode = 'idle'
renderer.setMode(mode)
const ink = parseColour(getComputedStyle(canvas).color) ?? [29, 26, 22]
const accent = parseColour(getComputedStyle(canvas).getPropertyValue('--orb-accent')) ?? ink
let last = performance.now()
function draw(now: number) {
  requestAnimationFrame(draw)
  const ratio = devicePixelRatio || 1
  const size = Math.round(canvas.clientWidth * ratio)
  if (canvas.width !== size) {
    canvas.width = size
    canvas.height = size
  }
  renderer.step((now - last) / 1000, mode === 'speaking' ? 0.4 : 0, reduced)
  renderer.draw(ctx, size, { ink, accent }, ratio)
  last = now
}
requestAnimationFrame(draw)

// ---- the orb's tap: one path, one status line (a stand-in for the stage's `onOrb`) -----------
const line = document.getElementById('orb-line')!
const orbButton = document.getElementById('orb')!
orbButton.addEventListener('click', () => {
  mode = mode === 'idle' ? 'listening' : 'idle'
  renderer.setMode(mode)
  line.textContent = mode === 'listening' ? 'Listening. Tap to stop.' : 'Tap and ask me anything'
})

// ---- where the orb is: the stage, or the side of the section in view --------------------------
const dock = document.getElementById('orb-dock')!
const BOX = 240
type Place = { where: 'stage' | 'side'; side: 'left' | 'right' | 'center' }
let place: Place = { where: 'stage', side: 'center' }

function apply() {
  const vw = innerWidth
  const vh = innerHeight
  const wide = vw >= 900
  let cx: number
  let cy: number
  let scale: number
  if (place.where === 'stage') {
    const space = document.querySelector('.hero-space')!.getBoundingClientRect()
    cx = vw / 2
    scale = Math.min(1, (space.height - 8.5 * 19) / BOX)
    cy = space.top + (BOX * scale) / 2
  } else if (wide) {
    scale = 0.5
    const half = (BOX * scale) / 2
    cx = place.side === 'left' ? 32 + half : place.side === 'right' ? vw - 32 - half : vw / 2
    cy = place.side === 'center' ? vh - 32 - half : vh / 2
  } else {
    scale = 0.26
    cx = vw - 16 - (BOX * scale) / 2
    cy = 16 + (BOX * scale) / 2
  }
  dock.dataset.place = place.where
  dock.style.setProperty('--place', `translate(${Math.round(cx - BOX / 2)}px, ${Math.round(cy - BOX / 2)}px)`)
  dock.style.setProperty('--orb-scale', String(scale))
  // The status line and chips ride beside the orb: under it on the stage, at its open side beside.
  const side = dock.querySelector<HTMLElement>('.orb-side')!
  const half = (BOX * scale) / 2
  if (place.where === 'stage') {
    side.style.transform = `translate(calc(${BOX / 2}px - 50%), ${BOX / 2 + half + 16}px)`
  } else if (place.side === 'right') {
    side.style.transform = `translate(calc(${BOX / 2 - half - 16}px - 100%), ${BOX / 2 - 22}px)`
  } else {
    side.style.transform = `translate(${BOX / 2 + half + 16}px, ${BOX / 2 - 22}px)`
  }
}

if (!reduced) {
  // On the stage while most of its space shows; else beside whichever section crosses the middle.
  let onStage = true
  new IntersectionObserver(
    ([entry]) => {
      onStage = entry.intersectionRatio >= 0.5
      if (onStage) place = { where: 'stage', side: 'center' }
      else if (place.where === 'stage') place = { where: 'side', side: 'right' }
      apply()
    },
    { threshold: [0, 0.5, 1] },
  ).observe(document.querySelector('.hero-space')!)
  const sections = document.querySelectorAll<HTMLElement>('.sec, .hero')
  const middle = new IntersectionObserver(
    (entries) => {
      for (const e of entries) {
        if (!e.isIntersecting || onStage) continue
        const side = (e.target.getAttribute('data-side') ?? 'right') as Place['side']
        place = { where: 'side', side: e.target.classList.contains('hero') ? 'right' : side }
        apply()
      }
    },
    { rootMargin: '-50% 0px -50% 0px' },
  )
  sections.forEach((s) => middle.observe(s))
  addEventListener('resize', apply)
  // The stage place is where the hero's space sits at the top; it is not re-placed per scroll frame
  // (that would be per-frame work), so a little scrolling within the hero leaves the orb in place.
  apply()
} else {
  dock.style.setProperty('--place', `translate(calc(50vw - ${BOX / 2}px), 90px)`)
}

// ---- reveals, staggered within each section ------------------------------------------------------
document.querySelectorAll<HTMLElement>('.sec, .hero').forEach((section) => {
  section.querySelectorAll<HTMLElement>('.reveal').forEach((el, i) => el.style.setProperty('--i', String(i)))
})
const revealer = new IntersectionObserver(
  (entries) => {
    for (const e of entries) {
      if (e.isIntersecting) {
        e.target.classList.add('in')
        revealer.unobserve(e.target)
      }
    }
  },
  { threshold: 0.15 },
)
document.querySelectorAll('.reveal').forEach((el) => revealer.observe(el))

// ---- pinned scenes: the step crossing the middle is the one shown ------------------------------
const stepper = new IntersectionObserver(
  (entries) => {
    for (const e of entries) {
      if (!e.isIntersecting) continue
      const scene = e.target.closest('.pin-scene')!
      const n = Number((e.target as HTMLElement).dataset.step)
      scene.querySelectorAll('.step').forEach((s) => s.classList.toggle('on', s === e.target))
      scene.querySelectorAll('.layer').forEach((l) => l.classList.toggle('on', Number((l as HTMLElement).dataset.layer) === n))
      const chapter = scene.querySelector('#chapter')
      if (chapter) chapter.textContent = e.target.querySelector('h3')?.textContent ?? ''
      ;(scene as HTMLElement).style.setProperty('--scrub', `${-60 * n}px`)
    }
  },
  { rootMargin: '-45% 0px -45% 0px' },
)
document.querySelectorAll('.step').forEach((s) => stepper.observe(s))

// ---- the 3D placeholder turns once it arrives (variant B scrubs it with CSS instead) ----------
new IntersectionObserver(
  ([e]) => document.querySelector<HTMLElement>('.model')!.style.setProperty('--turn', e.isIntersecting ? '200deg' : '20deg'),
  { threshold: 0.4 },
).observe(document.querySelector('.model-wrap')!)

// ---- for the browser check: frame times and layout shifts ------------------------------------------
const probe = { frames: [] as number[], shifts: 0 }
;(window as unknown as { __probe: typeof probe }).__probe = probe
let prev = performance.now()
function tick(now: number) {
  probe.frames.push(now - prev)
  prev = now
  requestAnimationFrame(tick)
}
requestAnimationFrame(tick)
try {
  new PerformanceObserver((list) => {
    for (const e of list.getEntries() as unknown as { value: number; hadRecentInput: boolean }[]) {
      if (!e.hadRecentInput) probe.shifts += e.value
    }
  }).observe({ type: 'layout-shift', buffered: true })
} catch {
  // No layout-shift entries in this browser.
}
