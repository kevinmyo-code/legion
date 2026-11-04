import { act, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

import type { DemoPhone } from '@/api/demo'
import { createEngine } from '@/test/engine'
import { sampleContent } from '@/test/engine-demo'
import { FakeLiveSocket } from '@/test/fake-live'
import { renderApp } from '@/test/render-app'

/**
 * "LEGION on the phone" on `/try` (prototype D "typeset", 2026-11-03): one
 * clip per capability, a `<video>` only where the content names one, nothing
 * that plays by itself, and no section at all when the content has none.
 * jsdom has no layout, so the default run is the inline layout (each clip
 * under its text); the stage is driven by stubbing its media query and the
 * IntersectionObserver.
 */

beforeEach(() => {
  FakeLiveSocket.reset()
  vi.stubGlobal('WebSocket', FakeLiveSocket)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

function phone(): DemoPhone {
  return {
    title: 'LEGION on the phone',
    intro: 'Cut from one recording.',
    clips: [
      {
        title: 'Wake word and barge-in',
        body: 'Say the name, ask, and interrupt it.',
        video: '/static/demo/wake.mp4',
        poster: '/static/demo/wake.jpg',
        captions: '/static/demo/wake.vtt',
      },
      { title: 'Morning briefing', body: 'One phrase runs a procedure.' },
      { title: 'Car check over OBD', body: 'The dongle reads trouble codes.', video: '/static/demo/obd.mp4' },
    ],
    full_take: '/static/demo/full.mp4',
  }
}

function visitWith(phoneSection: DemoPhone | undefined) {
  const engine = createEngine({ signedIn: false })
  engine.demo.session = {
    role: 'recruiter',
    expires_at: new Date(Date.now() + 2 * 3_600_000).toISOString(),
    ref: '',
  }
  engine.demo.content = { ...sampleContent(), phone: phoneSection }
  return engine
}

async function phoneSection() {
  return screen.findByRole('region', { name: 'LEGION on the phone' })
}

describe('the phone section', () => {
  test('renders every clip title and body, and a Phone entry in the nav', async () => {
    renderApp('/try', visitWith(phone()), 'family')
    const section = await phoneSection()
    for (const clip of phone().clips) {
      expect(within(section).getByRole('heading', { level: 3, name: clip.title })).toBeInTheDocument()
      expect(within(section).getByText(clip.body)).toBeInTheDocument()
    }
    expect(within(section).getByText('Cut from one recording.')).toBeInTheDocument()
    const nav = screen.getByRole('navigation', { name: 'On this page' })
    expect(within(nav).getByRole('link', { name: 'Phone' })).toHaveAttribute('href', '#phone')
    expect(section).toHaveAttribute('id', 'phone')
  })

  test('draws a <video> only for a clip that has one, and nothing plays by itself', async () => {
    const { container } = renderApp('/try', visitWith(phone()), 'family')
    const section = await phoneSection()
    const videos = Array.from(section.querySelectorAll('video'))
    expect(videos.map((video) => video.getAttribute('src'))).toEqual(['/static/demo/wake.mp4', '/static/demo/obd.mp4'])
    for (const video of videos) {
      expect(video.muted).toBe(true)
      expect(video).toHaveAttribute('controls')
      expect(video).toHaveAttribute('preload', 'none')
      expect(video).not.toHaveAttribute('autoplay')
      expect(video.autoplay).toBe(false)
    }
    expect(videos[0]).toHaveAttribute('poster', '/static/demo/wake.jpg')
    const track = videos[0].querySelector('track')!
    expect(track).toHaveAttribute('kind', 'captions')
    expect(track).toHaveAttribute('src', '/static/demo/wake.vtt')
    expect(track).toHaveAttribute('default')
    expect(videos[1].querySelector('track')).toBeNull()
    // The clip with no video is the placeholder, in words.
    expect(within(section).getByText('Recording to come')).toBeInTheDocument()
    expect(container.querySelectorAll('video')).toHaveLength(2)
    // The whole take is linked under the section.
    expect(within(section).getByRole('link', { name: 'watch it end to end' })).toHaveAttribute('href', '/static/demo/full.mp4')
  })

  test('renders nothing when the content has no phone section', async () => {
    const { container } = renderApp('/try', visitWith(undefined), 'family')
    await screen.findByRole('heading', { name: 'What to look for' })
    expect(screen.queryByRole('region', { name: 'LEGION on the phone' })).not.toBeInTheDocument()
    expect(container.querySelector('#phone')).toBeNull()
    expect(container.querySelector('video')).toBeNull()
    expect(screen.queryByRole('link', { name: 'Phone' })).not.toBeInTheDocument()
  })

  test('a full take that is not a web or same-site link is not drawn', async () => {
    renderApp('/try', visitWith({ ...phone(), full_take: 'javascript:alert(1)' }), 'family')
    const section = await phoneSection()
    expect(within(section).queryByRole('link', { name: 'watch it end to end' })).not.toBeInTheDocument()
  })

  test('at a wide screen the stage follows the step in the middle of the screen', async () => {
    let notify: IntersectionObserverCallback = () => {}
    const observed: Element[] = []
    const options: IntersectionObserverInit[] = []
    vi.stubGlobal(
      'IntersectionObserver',
      class {
        constructor(callback: IntersectionObserverCallback, init: IntersectionObserverInit) {
          notify = callback
          options.push(init)
        }
        observe(element: Element) {
          observed.push(element)
        }
        disconnect() {}
      },
    )
    renderApp('/try', visitWith(phone()), 'family')
    // After renderApp, which installs the surface stub; the page mounts the
    // section only once its session and content have answered.
    const original = window.matchMedia
    window.matchMedia = ((query: string) =>
      query === '(min-width: 1300px) and (prefers-reduced-motion: no-preference)'
        ? ({ matches: true, media: query, addEventListener() {}, removeEventListener() {} } as unknown as MediaQueryList)
        : original(query)) as typeof window.matchMedia
    try {
      const section = await phoneSection()
      expect(section.querySelector('[data-mode]')).toHaveAttribute('data-mode', 'stage')
      await waitFor(() => expect(observed).toHaveLength(3))
      expect(options[0].rootMargin).toBe('-45% 0px -45% 0px')
      expect(observed).toHaveLength(3)
      expect(within(section).getByText('1 of 3: Wake word and barge-in')).toBeInTheDocument()
      // Still one video per clip that has one: the stage holds them, the steps do not.
      expect(section.querySelectorAll('video')).toHaveLength(2)

      act(() => notify([{ isIntersecting: true, target: observed[2] } as unknown as IntersectionObserverEntry], {} as IntersectionObserver))
      expect(within(section).getByText('3 of 3: Car check over OBD')).toBeInTheDocument()
      expect(observed[2]).toHaveAttribute('data-active')
      expect(observed[0]).not.toHaveAttribute('data-active')
      // The clip off stage is out of the reading and tab order.
      const wake = section.querySelector('video[src="/static/demo/wake.mp4"]')!.parentElement!
      expect(wake).toHaveAttribute('aria-hidden', 'true')
      expect(wake).toHaveAttribute('inert')
    } finally {
      window.matchMedia = original
    }
  })
})
