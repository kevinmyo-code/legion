import { act, render, screen } from '@testing-library/react'
import { useRef } from 'react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import { diagramLayers, layerFor, LegionScene } from '@/screens/try-legion-scene'

import { setOrbPlace, useOrbPlace, useStoryGlide, useStoryMotion, useStorySections } from './scroll-story'

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
  setOrbPlace({ side: 'right', pinned: false })
})

function Page() {
  const motion = useStoryMotion()
  const glide = useStoryGlide()
  const place = useOrbPlace()
  const main = useRef<HTMLElement>(null)
  useStorySections(main, motion, 'content')
  return (
    <main ref={main} data-story={motion ? 'on' : undefined} data-glide={glide ? 'on' : 'off'}>
      <p data-testid="place">{`${place.side}${place.pinned ? ' pinned' : ''}`}</p>
      <section data-testid="first" data-orb-side="right">
        <h2>First</h2>
        <p>An invented line.</p>
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

  test('the orb rides on the side of the section crossing the middle, alone beside a pinned scene', () => {
    fakeIO()
    media((q) => q.includes('no-preference'))
    render(<Page />)
    inView(screen.getByTestId('second'), '-50% 0px -50% 0px')
    expect(screen.getByTestId('place')).toHaveTextContent('left pinned')
    inView(screen.getByTestId('first'), '-50% 0px -50% 0px')
    expect(screen.getByTestId('place')).toHaveTextContent('right')
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
      { top: 5, height: 20 },
      { top: 45, height: 15 },
      { top: 75, height: 20 },
    ])
    expect(diagramLayers('<svg></svg>')).toEqual([])
    expect(diagramLayers('not a picture')).toEqual([])
  })

  test('part N lights the layer at the same share of the way down', () => {
    expect([0, 1, 2, 3, 4, 5].map((step) => layerFor(step, 6, 4))).toEqual([0, 0, 1, 2, 2, 3])
    expect(layerFor(0, 3, 0)).toBe(-1)
  })

  test('the part crossing the middle of the screen is the one lit, with its layer', () => {
    fakeIO()
    const parts = [
      { title: 'One', body: 'An invented part.' },
      { title: 'Two', body: 'Another invented part.' },
      { title: 'Three', body: 'A third.' },
    ]
    const { container } = render(<LegionScene svg={SVG} caption="An invented caption." parts={parts} lead={(t) => `${t}.`} />)
    const bands = Array.from(container.querySelectorAll('.rc-scene-band'))
    expect(bands.map((b) => b.getAttribute('data-on'))).toEqual(['true', 'false', 'false'])
    inView(container.querySelector('[data-step="2"]')!)
    expect(container.querySelector('[data-step="2"]')).toHaveAttribute('data-on', 'true')
    expect(bands.map((b) => b.getAttribute('data-on'))).toEqual(['false', 'false', 'true'])
    // The diagram is still an image (never the SVG's markup in the page) with its words.
    expect(screen.getByRole('img', { name: 'An invented caption.' }).tagName).toBe('IMG')
  })
})
