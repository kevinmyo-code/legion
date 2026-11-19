import { act, fireEvent, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

import type { DemoPhone } from '@/api/demo'
import { createEngine } from '@/test/engine'
import { sampleContent } from '@/test/engine-demo'
import { FakeLiveSocket } from '@/test/fake-live'
import { renderApp } from '@/test/render-app'

/**
 * "LEGION on the phone" with a walkthrough (2026-11-19): one video, its chapters as buttons that
 * seek it and follow it, the full clips (at most three) below. Nothing plays before a press. All
 * content invented.
 */

let played: HTMLMediaElement[] = []

beforeEach(() => {
  FakeLiveSocket.reset()
  vi.stubGlobal('WebSocket', FakeLiveSocket)
  played = []
  // jsdom has no media: play() records who was asked to play.
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockImplementation(function (this: HTMLMediaElement) {
    played.push(this)
    return Promise.resolve()
  })
  vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => {})
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

function phone(): DemoPhone {
  return {
    title: 'LEGION on the phone',
    intro: 'One video, then the clips.',
    clips: [
      { title: 'Invented clip one', body: 'Lorem one.', video: '/static/demo/one.mp4', poster: '/static/demo/one.jpg' },
      { title: 'Invented clip two', body: 'Lorem two.', video: '/static/demo/two.mp4' },
    ],
    walkthrough: {
      video: '/static/demo/walk.mp4',
      poster: '/static/demo/walk.png',
      captions: '/static/demo/walk.vtt',
      chapters: [
        { title: 'Wake it', line: 'Say the word.', at: 3 },
        { title: 'Ask', line: 'Ask a thing.', at: 8.5 },
        { title: 'Answer', line: 'Hear it back.', at: 20 },
      ],
    },
  }
}

function visitWith(phoneSection: DemoPhone | undefined) {
  const engine = createEngine({ signedIn: false })
  engine.demo.session = { role: 'recruiter', expires_at: new Date(Date.now() + 2 * 3_600_000).toISOString(), ref: '' }
  engine.demo.content = { ...sampleContent(), phone: phoneSection }
  return engine
}

async function section() {
  return screen.findByRole('region', { name: 'LEGION on the phone' })
}

function walkVideo(region: HTMLElement) {
  return region.querySelector('video[src="/static/demo/walk.mp4"]') as HTMLVideoElement
}

describe('the phone walkthrough', () => {
  test('one video, poster first, captions on, nothing plays by itself or preloads', async () => {
    renderApp('/try', visitWith(phone()), 'family')
    const region = await section()
    const video = walkVideo(region)
    expect(video).toHaveAttribute('poster', '/static/demo/walk.png')
    expect(video).toHaveAttribute('preload', 'none')
    expect(video).toHaveAttribute('controls')
    expect(video).not.toHaveAttribute('autoplay')
    expect(video.muted).toBe(false)
    expect(video.querySelector('track')).toHaveAttribute('src', '/static/demo/walk.vtt')
    expect(video.querySelector('track')).toHaveAttribute('default')
    // The full clips under it load nothing either, and nobody was asked to play.
    for (const clip of Array.from(region.querySelectorAll('video'))) expect(clip).toHaveAttribute('preload', 'none')
    expect(played).toEqual([])
  })

  test('each chapter is a button with its line; a press seeks and plays from there', async () => {
    renderApp('/try', visitWith(phone()), 'family')
    const region = await section()
    const chapters = within(region).getByRole('list', { name: 'Chapters' })
    const buttons = within(chapters).getAllByRole('button')
    expect(buttons.map((b) => b.textContent)).toEqual(['0:03Wake itSay the word.', '0:08AskAsk a thing.', '0:20AnswerHear it back.'])
    fireEvent.click(buttons[1])
    const video = walkVideo(region)
    expect(video.currentTime).toBe(8.5)
    expect(played).toEqual([video])
    expect(buttons[1]).toHaveAttribute('aria-current', 'step')
  })

  test('a press before anything has loaded still lands on the chapter once the length is known', async () => {
    renderApp('/try', visitWith(phone()), 'family')
    const region = await section()
    const video = walkVideo(region)
    expect(video.readyState).toBe(HTMLMediaElement.HAVE_NOTHING)
    fireEvent.click(within(within(region).getByRole('list', { name: 'Chapters' })).getAllByRole('button')[2])
    // The browser drops a seek made before the metadata: the load starts at 0.
    video.currentTime = 0
    act(() => {
      video.dispatchEvent(new Event('loadedmetadata'))
    })
    expect(video.currentTime).toBe(20)
  })

  test('the chapter that is playing is marked as the video moves', async () => {
    renderApp('/try', visitWith(phone()), 'family')
    const region = await section()
    const video = walkVideo(region)
    const buttons = within(within(region).getByRole('list', { name: 'Chapters' })).getAllByRole('button')
    // Before the first chapter's time, none is current.
    expect(buttons.some((b) => b.hasAttribute('aria-current'))).toBe(false)
    act(() => {
      video.currentTime = 21
      video.dispatchEvent(new Event('timeupdate'))
    })
    expect(buttons[2]).toHaveAttribute('aria-current', 'step')
    expect(buttons[0]).not.toHaveAttribute('aria-current')
  })

  test('left and right keys step between chapters', async () => {
    renderApp('/try', visitWith(phone()), 'family')
    const region = await section()
    const video = walkVideo(region)
    const buttons = within(within(region).getByRole('list', { name: 'Chapters' })).getAllByRole('button')
    buttons[0].focus()
    fireEvent.keyDown(buttons[0], { key: 'ArrowRight' })
    expect(video.currentTime).toBe(8.5)
    expect(document.activeElement).toBe(buttons[1])
    fireEvent.keyDown(buttons[1], { key: 'ArrowLeft' })
    expect(video.currentTime).toBe(3)
    expect(document.activeElement).toBe(buttons[0])
    // At the ends it stays put.
    fireEvent.keyDown(buttons[0], { key: 'ArrowLeft' })
    expect(document.activeElement).toBe(buttons[0])
  })

  test('the full clips sit below, whole, with their titles', async () => {
    renderApp('/try', visitWith(phone()), 'family')
    const region = await section()
    for (const clip of phone().clips) {
      expect(within(region).getByRole('heading', { level: 3, name: clip.title })).toBeInTheDocument()
      expect(region.querySelector(`video[src="${clip.video}"]`)).toHaveAttribute('controls')
    }
    // No scroll stage and no swipe hint in this mode.
    expect(region.querySelector('[data-mode]')).toBeNull()
  })

  test('without a walkthrough the section is the clip row it always was', async () => {
    const old = phone()
    delete old.walkthrough
    renderApp('/try', visitWith(old), 'family')
    const region = await section()
    expect(region.querySelector('[data-mode]')).toHaveAttribute('data-mode', 'inline')
    expect(within(region).queryByRole('list', { name: 'Chapters' })).not.toBeInTheDocument()
  })

  test('reduced motion: the chapter highlight does not animate', async () => {
    renderApp('/try', visitWith(phone()), 'family')
    // After renderApp, which installs the surface stub; the section mounts once content answers.
    const original = window.matchMedia
    window.matchMedia = ((query: string) =>
      query === '(prefers-reduced-motion: reduce)'
        ? ({ matches: true, media: query, addEventListener() {}, removeEventListener() {} } as unknown as MediaQueryList)
        : original(query)) as typeof window.matchMedia
    try {
      const region = await section()
      expect(within(region).getByRole('list', { name: 'Chapters' })).toHaveAttribute('data-motion', 'reduce')
    } finally {
      window.matchMedia = original
    }
  })
})
