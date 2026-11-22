import { act, render, screen } from '@testing-library/react'
import { useRef } from 'react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import { cardOffset, diagramAspect, diagramLayers, frameDiagram, layerFor, LegionScene, litBoxes, litEdges, readDiagram } from '@/screens/try-legion-scene'

import { resetOrbPlace, useOrbPlace, useStoryGlide, useStoryMotion, useStorySections } from './scroll-story'

/**
 * The /try scroll story (Kevin, 2026-11-19, "B bolder"): sections rise in as they arrive, the orb
 * rides beside the section in view, the LEGION diagram is a pinned scene whose layer lights with
 * the part in view; reduced motion is the plain page. jsdom lays nothing out: the browser check
 * (`e2e/try-scroll-story-shots.spec.ts`) measures the motion itself. Invented content only.
 */

type Callback = (entries: { isIntersecting: boolean; target: Element }[]) => void
const observers: { cb: Callback; options?: IntersectionObserverInit; targets: Element[] }[] = []
const realIO = (globalThis as { IntersectionObserver?: unknown }).IntersectionObserver

function fakeIO() {
  ;(globalThis as { IntersectionObserver?: unknown }).IntersectionObserver = class {
    entry: { cb: Callback; options?: IntersectionObserverInit; targets: Element[] }
    constructor(cb: Callback, options?: IntersectionObserverInit) {
      this.entry = { cb, options, targets: [] }
      observers.push(this.entry)
    }
    observe(target: Element) {
      this.entry.targets.push(target)
    }
    unobserve() {}
    disconnect() {}
  }
}

/** The browser says `target` is in view, to the observer watching with this root margin. */
function inView(target: Element, margin?: string) {
  act(() => {
    for (const o of observers) {
      if (o.targets.includes(target) && (margin === undefined || o.options?.rootMargin === margin)) {
        o.cb([{ isIntersecting: true, target }])
      }
    }
  })
}

function media(matching: (query: string) => boolean) {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: matching(query),
    media: query,
    addEventListener() {},
    removeEventListener() {},
  }))
}

afterEach(() => {
  ;(globalThis as { IntersectionObserver?: unknown }).IntersectionObserver = realIO
  observers.length = 0
  vi.unstubAllGlobals()
  resetOrbPlace()
})

function Page({ content = 'content' }: { content?: string }) {
  const motion = useStoryMotion()
  const glide = useStoryGlide()
  const place = useOrbPlace()
  const main = useRef<HTMLElement>(null)
  useStorySections(main, motion, content)
  return (
    <main ref={main} data-story={motion ? 'on' : undefined} data-glide={glide ? 'on' : 'off'}>
      <p data-testid="place">{`${place.placed ? '' : 'unplaced '}${place.side}${place.pinned ? ' pinned' : ''}`}</p>
      <section data-testid="first" data-orb-side="right">
        <h2>First</h2>
        <p>An invented line.</p>
        <div data-testid="media">
          <video />
        </div>
        <div data-testid="scene" data-scene="invented" />
      </section>
      <section data-testid="second" data-orb-side="left" data-pinned="true">
        <h2>Second</h2>
      </section>
    </main>
  )
}

describe('the sections', () => {
  test('rise in as they arrive (hidden only by script, then shown), a few steps apart', () => {
    fakeIO()
    media((q) => q.includes('no-preference'))
    render(<Page />)
    const first = screen.getByTestId('first')
    const [heading, line] = Array.from(first.children) as HTMLElement[]
    expect(heading.dataset.reveal).toBe('pending')
    expect(line.style.getPropertyValue('--reveal-i')).toBe('1')
    inView(heading)
    expect(heading.dataset.reveal).toBe('shown')
    expect(line.dataset.reveal).toBe('pending')
  })

  test('blocks a screen or more tall (a pinned scene, a player, a 3D model) never rise in: no big layer moves', () => {
    fakeIO()
    media((q) => q.includes('no-preference'))
    render(<Page />)
    expect(screen.getByTestId('media')).not.toHaveAttribute('data-reveal')
    expect(screen.getByTestId('scene')).not.toHaveAttribute('data-reveal')
  })

  test('a live content reload never hides again what was already shown', () => {
    fakeIO()
    media((q) => q.includes('no-preference'))
    const { rerender } = render(<Page content="first" />)
    const heading = screen.getByTestId('first').children[0] as HTMLElement
    inView(heading)
    expect(heading.dataset.reveal).toBe('shown')
    rerender(<Page content="reloaded" />)
    expect(heading.dataset.reveal).not.toBe('pending')
  })

  test('the orb rides on the side of the section crossing the middle, alone beside a pinned scene', () => {
    fakeIO()
    media((q) => q.includes('no-preference'))
    render(<Page />)
    expect(screen.getByTestId('place')).toHaveTextContent('unplaced right')
    inView(screen.getByTestId('second'), '-50% 0px -50% 0px')
    expect(screen.getByTestId('place')).toHaveTextContent(/^left pinned$/)
    inView(screen.getByTestId('first'), '-50% 0px -50% 0px')
    expect(screen.getByTestId('place')).toHaveTextContent(/^right$/)
    // Scrolled back above the first section (its top below the middle): no place, the orb goes.
    const top = window.innerHeight * 0.7
    screen.getByTestId('first').getBoundingClientRect = () =>
      ({ top, bottom: top + 500, left: 0, right: 0, width: 0, height: 500, x: 0, y: top, toJSON() {} }) as DOMRect
    act(() => {
      for (const o of observers) {
        if (o.options?.rootMargin !== '-50% 0px -50% 0px') continue
        o.cb([{ isIntersecting: false, target: screen.getByTestId('first'), boundingClientRect: { top: window.innerHeight * 0.7 } } as never])
      }
    })
    expect(screen.getByTestId('place')).toHaveTextContent('unplaced')
  })

  test('a jump straight to the top (Home, a scrollbar drag, a flick) takes the orb out of place', () => {
    fakeIO()
    media((q) => q.includes('no-preference'))
    render(<Page />)
    const first = screen.getByTestId('first')
    const second = screen.getByTestId('second')
    const leave = (target: Element) =>
      act(() => {
        for (const o of observers) {
          if (o.options?.rootMargin !== '-50% 0px -50% 0px') continue
          o.cb([{ isIntersecting: false, target, boundingClientRect: { top: -2000 } } as never])
        }
      })
    const firstAt = (top: number) => {
      first.getBoundingClientRect = () => ({ top, bottom: top + 500, left: 0, right: 0, width: 0, height: 500, x: 0, y: top, toJSON() {} }) as DOMRect
    }
    // Deep in the page: the second section is in the middle.
    firstAt(-3000)
    inView(second, '-50% 0px -50% 0px')
    expect(screen.getByTestId('place')).toHaveTextContent(/^left pinned$/)
    // Jump to the top: the only entry is the deep section leaving; the first one never crossed.
    firstAt(window.innerHeight * 0.8)
    leave(second)
    expect(screen.getByTestId('place')).toHaveTextContent('unplaced')
    // Deep, to the middle of the page, and back to the top again.
    firstAt(-3000)
    inView(second, '-50% 0px -50% 0px')
    firstAt(-200)
    inView(first, '-50% 0px -50% 0px')
    expect(screen.getByTestId('place')).toHaveTextContent(/^right$/)
    firstAt(window.innerHeight * 0.9)
    leave(first)
    expect(screen.getByTestId('place')).toHaveTextContent('unplaced')
  })

  test('the last block of the page is revealed even if it can never climb past the reading margin', () => {
    fakeIO()
    media((q) => q.includes('no-preference'))
    render(<Page />)
    const lastSection = screen.getByTestId('second')
    const last = lastSection.children[lastSection.children.length - 1] as HTMLElement
    expect(last.dataset.reveal).toBe('pending')
    // Watched by an observer with no reading margin as well, so the foot of the page shows it.
    const plain = observers.filter((o) => o.targets.includes(last) && !(o.options?.rootMargin ?? '').includes('-'))
    expect(plain.length).toBeGreaterThan(0)
  })

  test('glides only with room beside the sections (900 px and up), motion allowed', () => {
    media((q) => q.includes('no-preference') && !q.includes('min-width'))
    render(<Page />)
    expect(screen.getByRole('main')).toHaveAttribute('data-glide', 'off')
  })

  test('reduced motion is the plain page: nothing hidden, no story', () => {
    fakeIO()
    media(() => false)
    render(<Page />)
    expect(screen.getByRole('main')).not.toHaveAttribute('data-story')
    expect(screen.getByTestId('first').querySelector('[data-reveal]')).toBeNull()
  })
})

const SVG = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 200">
  <defs><marker id="m"><rect x="0" y="0" width="8" height="8"/></marker></defs>
  <rect x="0" y="0" width="400" height="200" fill="#fff"/>
  <rect x="10" y="10" width="80" height="30"/><rect x="110" y="20" width="80" height="30"/>
  <rect x="10" y="90" width="380" height="30"/>
  <rect x="10" y="150" width="80" height="40"/>
</svg>`

describe('the LEGION scene', () => {
  test("the diagram's layers are the rows its boxes sit in (never a backdrop or a marker's box)", () => {
    expect(diagramLayers(SVG)).toEqual([
      { left: 2.5, width: 45, top: 5, height: 20 },
      { left: 2.5, width: 95, top: 45, height: 15 },
      { left: 2.5, width: 20, top: 75, height: 20 },
    ])
    expect(diagramLayers('<svg></svg>')).toEqual([])
    expect(diagramLayers('not a picture')).toEqual([])
  })

  test('a diagram drawn in columns (a tall box joins its rows) is read as its columns', () => {
    const columns = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 200">
      <rect x="20" y="10" width="80" height="40"/><rect x="20" y="60" width="80" height="40"/>
      <rect x="160" y="10" width="80" height="180"/>
      <rect x="300" y="10" width="80" height="40"/><rect x="300" y="60" width="80" height="40"/>
    </svg>`
    expect(diagramLayers(columns)).toEqual([
      { left: 5, width: 20, top: 5, height: 45 },
      { left: 40, width: 20, top: 5, height: 90 },
      { left: 75, width: 20, top: 5, height: 45 },
    ])
  })

  test('a diagram with no viewBox gives its aspect from its own width and height', () => {
    expect(diagramAspect('<svg xmlns="http://www.w3.org/2000/svg" width="600" height="300"></svg>')).toBe(2)
    expect(diagramAspect('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 200" width="10" height="10"></svg>')).toBe(2)
    expect(diagramAspect('<svg xmlns="http://www.w3.org/2000/svg"></svg>')).toBeNull()
  })

  test('part N lights the layer at the same share of the way down', () => {
    expect([0, 1, 2, 3, 4, 5].map((step) => layerFor(step, 6, 4))).toEqual([0, 0, 1, 2, 2, 3])
    expect(layerFor(0, 3, 0)).toBe(-1)
  })

  test('boxes are read with the heading written in them, and lines with their arrows', () => {
    const d = readDiagram(NAMED)!
    expect(d.view).toEqual({ x: 0, y: 0, w: 400, h: 200 })
    expect(d.boxes.map((b) => b.label)).toEqual(['Fake phone', 'Fake engine', 'Fake bank'])
    expect(d.edges).toEqual([
      { x1: 92, y1: 30, x2: 158, y2: 30, both: true },
      { x1: 242, y1: 30, x2: 308, y2: 30, both: false },
    ])
    expect(readDiagram('not a picture')).toBeNull()
  })

  test('a part lights the boxes it names; naming none (or none found) lights its share of the layers', () => {
    const d = readDiagram(NAMED)!
    expect(litBoxes(d, ['fake  ENGINE'], 0, 3)).toEqual([1])
    expect(litBoxes(d, ['Fake engine', 'Fake bank'], 0, 3)).toEqual([1, 2])
    expect(litBoxes(d, undefined, 2, 3)).toEqual([2])
    expect(litBoxes(d, ['Not in the picture'], 0, 3)).toEqual([0])
  })

  test('the connections lit: between two lit boxes; with one box, every one reaching it', () => {
    const d = readDiagram(NAMED)!
    expect(litEdges(d, [0, 1])).toEqual([0])
    expect(litEdges(d, [1])).toEqual([0, 1])
    expect(litEdges(d, [])).toEqual([])
    expect(litEdges(readDiagram(SVG)!, [0])).toEqual([])
  })

  test('framing: the whole diagram centred, or zoomed to the lit boxes with room, never past three times', () => {
    const d = readDiagram(NAMED)!
    expect(frameDiagram(d, [1], 800, 400, false)).toEqual({ scale: 2, x: 0, y: 0 })
    const zoomed = frameDiagram(d, [1], 400, 200, true)
    expect(zoomed.scale).toBeCloseTo(Math.min(400 / 116, 200 / 76, 3))
    // The lit box's centre lands at the frame's centre.
    expect(zoomed.x + 200 * zoomed.scale).toBeCloseTo(200)
    expect(zoomed.y + 30 * zoomed.scale).toBeCloseTo(100)
  })

  test('the card sits under the lit boxes, inside the frame', () => {
    expect(cardOffset(500, 1000, 400)).toBe(300)
    expect(cardOffset(50, 1000, 400)).toBe(0)
    expect(cardOffset(990, 1000, 400)).toBe(600)
    expect(cardOffset(500, 300, 400)).toBe(0)
  })

  test('the part at the middle of the screen is the step: its words show, its boxes and lines light', () => {
    fakeIO()
    const parts = [
      { title: 'One', body: 'An invented part about the phone.', lights: ['Fake phone'] },
      { title: 'Two', body: 'Another invented part, about the engine and the phone.', lights: ['Fake engine', 'Fake phone'] },
      { title: 'Three', body: 'A third, with no names.' },
    ]
    const { container } = render(<LegionScene svg={NAMED} caption="An invented caption." parts={parts} lead={(t) => `${t}.`} />)
    const items = () => Array.from(container.querySelectorAll('.rc-scene-card li')).map((li) => li.getAttribute('data-on'))
    const nodes = () => Array.from(container.querySelectorAll('.rc-scene-node')).map((n) => n.getAttribute('data-on'))
    const edges = () => Array.from(container.querySelectorAll('.rc-scene-edge')).map((n) => n.getAttribute('data-on'))
    expect(items()).toEqual(['true', 'false', 'false'])
    expect(nodes()).toEqual(['true', 'false', 'false'])
    expect(edges()).toEqual(['true', 'false'])
    inView(container.querySelector('[data-step="1"]')!)
    expect(items()).toEqual(['false', 'true', 'false'])
    expect(nodes()).toEqual(['true', 'true', 'false'])
    expect(edges()).toEqual(['true', 'false'])
    expect(container.querySelector('.rc-scene-card li[aria-current="step"]')).toHaveTextContent('Two.')
    // Packets travel only on lit lines: both ways on a line with an arrow at each end.
    expect(container.querySelectorAll('.rc-scene-packet')).toHaveLength(2)
    inView(container.querySelector('[data-step="2"]')!)
    expect(nodes()).toEqual(['false', 'false', 'true'])
    // The words are in the page once each, in order, and none of them is a live region.
    for (const part of parts) expect(screen.getAllByText(part.body, { exact: false })).toHaveLength(1)
    expect(container.querySelector('[aria-live]')).toBeNull()
    // The diagram is still an image (never the SVG's markup in the page); the highlights are the
    // page's own drawing over it, hidden from assistive tech.
    expect(screen.getByRole('img', { name: 'An invented caption.' }).tagName).toBe('IMG')
    expect(container.querySelector('.rc-scene-overlay')).toHaveAttribute('aria-hidden', 'true')
    expect(container.querySelector('.rc-scene-overlay text')).toBeNull()
  })

  test('reduced motion keeps the steps: the scene says so, and the CSS drops the travel', () => {
    fakeIO()
    media(() => false)
    const { container } = render(<LegionScene svg={NAMED} caption="" parts={[{ title: 'One', body: 'Invented.' }]} lead={(t) => t} />)
    expect(container.querySelector('.rc-scene')).toHaveAttribute('data-motion', 'off')
    expect(container.querySelector('[data-step="0"]')).not.toBeNull()
  })
})

const NAMED = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 200">
  <defs><marker id="a"><path d="M0,0 L8,4 L0,8 z"/></marker></defs>
  <rect width="400" height="200" fill="#fff"/>
  <rect x="10" y="10" width="80" height="40"/><text x="20" y="30">Fake phone</text><text x="20" y="44">a fake subtitle</text>
  <rect x="160" y="10" width="80" height="40"/><text x="170" y="30">Fake engine</text>
  <rect x="310" y="10" width="80" height="40"/><text x="320" y="30">Fake bank</text>
  <line x1="92" y1="30" x2="158" y2="30" marker-start="url(#a)" marker-end="url(#a)"/>
  <line x1="242" y1="30" x2="308" y2="30" marker-end="url(#a)"/>
</svg>`
