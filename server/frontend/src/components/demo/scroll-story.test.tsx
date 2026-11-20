import { act, render, screen } from '@testing-library/react'
import { useRef } from 'react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import { diagramAspect, diagramLayers, layerFor, LegionScene } from '@/screens/try-legion-scene'

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
  setOrbPlace({ side: 'right', pinned: false, placed: false })
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
    act(() => {
      for (const o of observers) {
        if (o.options?.rootMargin !== '-50% 0px -50% 0px') continue
        o.cb([{ isIntersecting: false, target: screen.getByTestId('first'), boundingClientRect: { top: window.innerHeight * 0.7 } } as never])
      }
    })
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
    // Narrow, its frame scrolls sideways, and says so, as the plain page's does.
    expect(screen.getByRole('region', { name: 'Diagram, scrolls sideways' })).toBeInTheDocument()
    expect(container.querySelector('.rc-swipe')).toHaveTextContent('Swipe sideways for the rest of the diagram.')
  })

  test('with room (900 px and up) the whole diagram shows: no sideways claim, no hint', () => {
    fakeIO()
    media(() => true)
    const { container } = render(<LegionScene svg={SVG} caption="An invented caption." parts={[{ title: 'One', body: 'Invented.' }]} lead={(t) => t} />)
    expect(screen.getByRole('region', { name: 'Diagram' })).toBeInTheDocument()
    expect(container.querySelector('.rc-swipe')).toBeNull()
  })
})
