import { act, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

import type { DemoPhone } from '@/api/demo'
import { createEngine } from '@/test/engine'
import { sampleContent } from '@/test/engine-demo'
import { FakeLiveSocket } from '@/test/fake-live'
import { renderApp } from '@/test/render-app'

/**
 * "LEGION on the phone" on `/try`: one clip per capability in ONE full-width
 * 16:9 player (Kevin, 2026-11-22: the clips are horizontal, no phone frame),
 * nothing that plays by itself, and no section at all when the content has none.
 */

beforeEach(() => {
  FakeLiveSocket.reset()
  vi.stubGlobal('WebSocket', FakeLiveSocket)
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
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

  test('one wide player, no phone frame, the first clip in it, and nothing plays by itself', async () => {
    const { container } = renderApp('/try', visitWith(phone()), 'family')
    const section = await phoneSection()
    expect(section.querySelector('.rc-phone, .rc-inline, .rc-pscreen')).toBeNull()
    expect(section).not.toHaveAttribute('data-pinned')
    expect(section.querySelector('.rc-player')).not.toBeNull()
    const videos = Array.from(section.querySelectorAll('video'))
    expect(videos).toHaveLength(1)
    const [video] = videos
    expect(video).toHaveAttribute('src', '/static/demo/wake.mp4')
    // Sound on when the visitor presses play (voice clips); captions load cross-origin.
    expect(video.muted).toBe(false)
    expect(video).toHaveAttribute('crossorigin', 'anonymous')
    expect(video).toHaveAttribute('controls')
    expect(video).toHaveAttribute('preload', 'none')
    expect(video).not.toHaveAttribute('autoplay')
    expect(video.autoplay).toBe(false)
    expect(video).toHaveAttribute('poster', '/static/demo/wake.jpg')
    const track = video.querySelector('track')!
    expect(track).toHaveAttribute('kind', 'captions')
    expect(track).toHaveAttribute('src', '/static/demo/wake.vtt')
    expect(track).toHaveAttribute('default')
    expect(within(section).getByText('1 of 3: Wake word and barge-in')).toBeInTheDocument()
    expect(container.querySelectorAll('video')).toHaveLength(1)
    // The whole take is linked under the section.
    expect(within(section).getByRole('link', { name: 'watch it end to end' })).toHaveAttribute('href', '/static/demo/full.mp4')
  })

  test('renders nothing when the content has no phone section', async () => {
    const { container } = renderApp('/try', visitWith(undefined), 'family')
    await screen.findByRole('heading', { name: 'Experience' })
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

  function stubPlayer() {
    const played: string[] = []
    vi.spyOn(HTMLMediaElement.prototype, 'play').mockImplementation(function (this: HTMLMediaElement) {
      played.push(this.getAttribute('src') ?? '')
      return Promise.resolve()
    })
    vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => {})
    return played
  }

  test('a tap on a row puts that clip in the player and plays it', async () => {
    const played = stubPlayer()
    renderApp('/try', visitWith(phone()), 'family')
    const section = await phoneSection()
    const row = await within(section).findByRole('button', { name: 'Play: Car check over OBD' })
    act(() => row.click())
    expect(played).toEqual(['/static/demo/obd.mp4'])
    expect(section.querySelectorAll('video')).toHaveLength(1)
    expect(section.querySelector('video')).toHaveAttribute('src', '/static/demo/obd.mp4')
    expect(within(section).getByText('3 of 3: Car check over OBD')).toBeInTheDocument()
    expect(row).toHaveAttribute('aria-current', 'true')
    expect(within(section).getByRole('button', { name: 'Play: Wake word and barge-in' })).not.toHaveAttribute('aria-current')
  })

  test('a clip with no recording has no Play, and its slot says so in words', async () => {
    renderApp('/try', visitWith({ ...phone(), clips: [phone().clips[1], phone().clips[0]] }), 'family')
    const section = await phoneSection()
    expect(within(section).queryByRole('button', { name: 'Play: Morning briefing' })).toBeNull()
    expect(section.querySelector('video')).toBeNull()
    expect(within(section).getByText('Recording to come')).toBeInTheDocument()
    expect(within(section).getByRole('button', { name: 'Play: Wake word and barge-in' })).toBeInTheDocument()
  })
})
