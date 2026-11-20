import { useEffect, useLayoutEffect, useSyncExternalStore, type RefObject } from 'react'

/**
 * /try as a scroll story (Kevin, 2026-11-19, from the prototype captures: "B bolder"). The rules
 * every part of it keeps:
 * - nothing runs on a scroll event: IntersectionObserver says what is in view, CSS animates
 *   `transform` and `opacity`, and the one scroll-linked effect (the hero title easing out) is a
 *   CSS scroll-driven animation where the browser has them, and simply absent where it does not;
 * - the page scrolls at the visitor's own speed: pinning is `position: sticky`, never a captured
 *   wheel or touch;
 * - `prefers-reduced-motion` gives the plain page as it was: no reveal, no pin, no glide;
 * - nothing moves the layout: a reveal is a transform, a pin is sticky inside room the page
 *   already has, and the room each section keeps for the orb is fixed per section.
 */

const MOTION = '(prefers-reduced-motion: no-preference)'
/** The orb rides beside the sections only where there is room beside them. */
const GLIDE = '(min-width: 900px) and (prefers-reduced-motion: no-preference)'

function useQuery(query: string): boolean {
  return useSyncExternalStore(
    (onChange) => {
      if (typeof window.matchMedia !== 'function') return () => {}
      const list = window.matchMedia(query)
      list.addEventListener('change', onChange)
      return () => list.removeEventListener('change', onChange)
    },
    () => typeof window.matchMedia === 'function' && window.matchMedia(query).matches,
    () => false,
  )
}

/** Reveals, pinned scenes, the model turning with the scroll: any width, motion allowed. */
export function useStoryMotion(): boolean {
  return useQuery(MOTION)
}

/** The orb glides to the side of each section (else it docks at the foot, as before). */
export function useStoryGlide(): boolean {
  return useQuery(GLIDE)
}

export type OrbSide = 'left' | 'right'
export interface OrbPlace {
  side: OrbSide
  /** Beside a pinned scene the orb rides alone: its line and chips would cover the scene. */
  pinned: boolean
  /**
   * A section has put it in place. Until then (the header and hero, which keep no room for it) the
   * side orb stays hidden (review recheck 2026-11-20: it sat over the header's links at 900-1280 px).
   */
  placed: boolean
}

// One place for the whole page: the sections say which one is in view, the voice stage's orb
// follows. A tiny store, so the two need no shared parent component.
let place: OrbPlace = { side: 'right', pinned: false, placed: false }
const listeners = new Set<() => void>()

export function setOrbPlace({ side, pinned, placed = true }: { side: OrbSide; pinned: boolean; placed?: boolean }) {
  if (side === place.side && pinned === place.pinned && placed === place.placed) return
  place = { side, pinned, placed }
  for (const listener of listeners) listener()
}

export function useOrbPlace(): OrbPlace {
  return useSyncExternalStore(
    (onChange) => {
      listeners.add(onChange)
      return () => listeners.delete(onChange)
    },
    () => place,
    () => place,
  )
}

// What has risen in once stays shown for the visit: a live content reload re-runs the marking,
// and must never hide again what the visitor has already seen (review 2026-11-20).
const risen = new WeakSet<Element>()
const TALL = '[data-scene], .rc-scrolly, video, canvas, .rc-model-box'

/**
 * For the sections under `root`: each `[data-orb-side]` section that crosses the middle of the
 * screen puts the orb on its side; each section's own children rise in as they arrive, a few
 * tens of milliseconds apart. Marked hidden only here, so with no script nothing is hidden.
 */
export function useStorySections(root: RefObject<HTMLElement | null>, on: boolean, deps: unknown) {
  // Before paint: the children of sections out of view start hidden, never flash in first.
  useLayoutEffect(() => {
    const element = root.current
    if (!on || !element || typeof IntersectionObserver === 'undefined') return
    const sections = Array.from(element.querySelectorAll<HTMLElement>('[data-orb-side]'))
    const pending: HTMLElement[] = []
    for (const section of sections) {
      Array.from(section.children).forEach((child, index) => {
        const el = child as HTMLElement
        if (el.dataset.reveal === 'shown' || risen.has(el)) return
        // Never a block that is, or holds, a pinned scene, a player or a 3D view: moving a layer
        // that size re-draws it every frame of the rise (review 2026-11-20, frames at 1440 px).
        if (el.matches(TALL) || el.querySelector(TALL)) return
        el.dataset.reveal = 'pending'
        el.style.setProperty('--reveal-i', String(Math.min(index, 6)))
        pending.push(el)
      })
    }
    const show = (entries: IntersectionObserverEntry[]) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue
        ;(entry.target as HTMLElement).dataset.reveal = 'shown'
        risen.add(entry.target)
        reveal.unobserve(entry.target)
        tail.unobserve(entry.target)
      }
    }
    // Its top a little way up the screen; never a share of its area, which a block taller than
    // several screens (a pinned scene) would never reach.
    const reveal = new IntersectionObserver(show, { rootMargin: '0px 0px -12% 0px', threshold: 0 })
    // The page's last block may end too near the foot to ever climb that far: it shows on arrival.
    const tail = new IntersectionObserver(show, { threshold: 0 })
    pending.forEach((el) => reveal.observe(el))
    if (pending.length > 0) tail.observe(pending[pending.length - 1])
    const middle = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (!entry.isIntersecting) continue
          const target = entry.target as HTMLElement
          setOrbPlace({
            side: target.dataset.orbSide === 'left' ? 'left' : 'right',
            pinned: target.dataset.pinned === 'true',
          })
        }
        // Above the first section (its top below the middle) the orb has no place: the header and
        // hero keep no room for it. Read from the section's own position on every callback, so a
        // jump straight to the top (Home, a scrollbar drag, a flick), where the first section never
        // crosses the middle, still counts (review recheck cbe39b2a).
        const first = sections[0]
        if (first && first.getBoundingClientRect().top > window.innerHeight / 2) setOrbPlace({ ...place, placed: false })
      },
      { rootMargin: '-50% 0px -50% 0px' },
    )
    sections.forEach((section) => middle.observe(section))
    return () => {
      reveal.disconnect()
      tail.disconnect()
      middle.disconnect()
      // Off again (reduced motion switched on, or the page left): nothing stays hidden.
      for (const el of pending) {
        delete el.dataset.reveal
        el.style.removeProperty('--reveal-i')
      }
    }
  }, [root, on, deps])
}

/**
 * The step crossing the scene's reading line (`margin`, an IntersectionObserver root margin: the
 * middle of the screen by default): `onStep(index)` for each element under `root` marked `[data-step]`.
 */
export function useSceneSteps(
  root: RefObject<HTMLElement | null>,
  on: boolean,
  onStep: (index: number) => void,
  margin = '-45% 0px -45% 0px',
) {
  useEffect(() => {
    const element = root.current
    if (!on || !element || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) onStep(Number((entry.target as HTMLElement).dataset.step))
        }
      },
      { rootMargin: margin },
    )
    element.querySelectorAll('[data-step]').forEach((step) => observer.observe(step))
    return () => observer.disconnect()
  }, [root, on, onStep, margin])
}
