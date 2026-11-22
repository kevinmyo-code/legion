import { useLayoutEffect, useRef, useState, type RefObject } from 'react'

import type { OrbSide } from '@/components/demo/scroll-story'

/**
 * The orb's one continuous flight (Kevin, 2026-11-22: "it gotta flow from the middle of the page to
 * that side. one continous flow"). There is one drawn orb on the page: it sits exactly over the
 * stage's orb at the top, and as the page scrolls it travels, shrinking, to its rest place: beside
 * the sections at desk width, into the foot bar on a phone. Scrolling back flies it home.
 *
 * What it costs per frame: one passive scroll listener schedules one animation frame, which writes
 * one `transform`. Every position it needs is measured on resize only (the
 * stage's place in the page, the foot bar's slot), so a frame reads no layout and moves none.
 * Off under reduced motion: the page keeps the plain dock (the caller does not mount this).
 */

export type Rest = 'side' | 'dock'

/** Where the flight starts and ends, in page and screen pixels; measured on resize. */
export interface FlightGeometry {
  /** The stage orb's centre in the page (document coordinates). */
  stageX: number
  stageY: number
  /** The stage orb's width: the flying orb's own size, so it is drawn sharp at the top. */
  size: number
  /** The rest place's centre on screen, and the orb's width there. */
  restX: number
  restY: number
  restSize: number
  /** How many pixels of scroll the flight takes. */
  travel: number
}

export interface FlightFrame {
  x: number
  y: number
  scale: number
  /** 0 at the top, 1 at rest: the words beside the rest place fade in near its end. */
  progress: number
}

function ease(t: number): number {
  return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2
}

/** The orb's centre on screen and its scale at a scroll position: eased over `travel`. */
export function flightFrame(g: FlightGeometry, scrollY: number): FlightFrame {
  const progress = Math.min(1, Math.max(0, scrollY / Math.max(1, g.travel)))
  const e = ease(progress)
  const fromY = g.stageY - scrollY
  return {
    x: g.stageX + (g.restX - g.stageX) * e,
    y: fromY + (g.restY - fromY) * e,
    scale: 1 + (g.restSize / Math.max(1, g.size) - 1) * e,
    progress,
  }
}

/** The side rest place's centre: in the 12rem column the sections keep beside them. */
export function sideRestX(side: OrbSide, viewportWidth: number, rem: number): number {
  return side === 'left' ? 7.5 * rem : viewportWidth - 8 * rem
}

/**
 * The flight's length: it lands as the stage (orb, captions, typing, the ask) leaves the upper part
 * of the screen, so the orb glides the whole way the stage scrolls away; never under 360 px.
 */
export function flightTravel(stageBottom: number, viewportHeight: number): number {
  return Math.max(360, stageBottom - viewportHeight * 0.4)
}

const SIDE_ORB_REM = 10
// How fast the rest place follows a change of side: about 0.8 s to settle, like the words' glide.
const SIDE_TAU_S = 0.16

/**
 * Drives `flyer` (a fixed element as large as the stage orb) from the stage orb `stage` to its rest
 * place. `slot` is the foot bar's empty orb slot (the `dock` rest). Returns whether the orb is at
 * rest: its words, chips and foot bar show then, and it is the page's orb for the keyboard.
 */
export function useOrbFlight({
  on,
  rest,
  side,
  stage,
  flyer,
  slot,
}: {
  on: boolean
  rest: Rest
  side: OrbSide
  stage: RefObject<HTMLElement | null>
  flyer: RefObject<HTMLElement | null>
  slot: RefObject<HTMLElement | null>
}) {
  const geometry = useRef<FlightGeometry | null>(null)
  const sideX = useRef<{ now: number; goal: number } | null>(null)
  const frame = useRef(0)
  const last = useRef(0)
  const [landed, setLanded] = useState(false)

  useLayoutEffect(() => {
    if (!on) return
    const fly = flyer.current
    const home = stage.current
    if (!fly || !home) return
    const rem = () => parseFloat(getComputedStyle(document.documentElement).fontSize) || 16

    const measure = () => {
      const box = home.getBoundingClientRect()
      const size = box.width || 1
      let restX: number
      let restY: number
      let restSize: number
      if (rest === 'side') {
        const goal = sideRestX(side, window.innerWidth, rem())
        sideX.current = sideX.current ? { now: sideX.current.now, goal } : { now: goal, goal }
        restX = sideX.current.now
        restY = window.innerHeight / 2 - 2.5 * rem()
        restSize = SIDE_ORB_REM * rem()
      } else {
        const spot = slot.current?.getBoundingClientRect()
        restX = spot ? spot.left + spot.width / 2 : 40
        restY = spot ? spot.top + spot.height / 2 : window.innerHeight - 32
        restSize = spot?.width || 48
      }
      const stageBottom = (home.closest('section') ?? home).getBoundingClientRect().bottom + window.scrollY
      geometry.current = {
        stageX: box.left + window.scrollX + box.width / 2,
        stageY: box.top + window.scrollY + box.height / 2,
        size,
        restX,
        restY,
        restSize,
        travel: flightTravel(stageBottom, window.innerHeight),
      }
      fly.style.width = `${size}px`
      fly.style.height = `${size}px`
    }

    const paint = (now: number) => {
      frame.current = 0
      const g = geometry.current
      if (!g) return
      // The side's rest place eases to a new side; the frame loop runs only while it is moving.
      let moving = false
      if (rest === 'side' && sideX.current) {
        const dt = Math.min(0.05, Math.max(0, (now - (last.current || now)) / 1000))
        const s = sideX.current
        s.now += (s.goal - s.now) * (1 - Math.exp(-dt / SIDE_TAU_S))
        if (Math.abs(s.goal - s.now) < 0.5) s.now = s.goal
        else moving = true
        g.restX = s.now
      }
      last.current = now
      const f = flightFrame(g, window.scrollY)
      fly.style.transform = `translate3d(${(f.x - g.size / 2).toFixed(1)}px, ${(f.y - g.size / 2).toFixed(1)}px, 0) scale(${f.scale.toFixed(4)})`
      setLanded(f.progress >= 0.999)
      if (moving) frame.current = requestAnimationFrame(paint)
      else last.current = 0
    }
    const schedule = () => {
      if (!frame.current) frame.current = requestAnimationFrame(paint)
    }

    measure()
    paint(performance.now())
    const remeasure = () => {
      measure()
      schedule()
    }
    window.addEventListener('scroll', schedule, { passive: true })
    window.addEventListener('resize', remeasure)
    // The page under the stage grows as content arrives; the stage itself may reflow (fonts).
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(remeasure)
    observer?.observe(document.body)
    observer?.observe(home)
    return () => {
      window.removeEventListener('scroll', schedule)
      window.removeEventListener('resize', remeasure)
      observer?.disconnect()
      cancelAnimationFrame(frame.current)
      frame.current = 0
    }
    // A new side re-runs this: the measure keeps where the rest place is and sets where it goes,
    // and the first frame starts the glide there.
  }, [on, rest, stage, flyer, slot, side])
  return on && landed
}
