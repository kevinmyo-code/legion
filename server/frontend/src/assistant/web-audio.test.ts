import { afterEach, describe, expect, it, vi } from 'vitest'

import { createWebAudio } from './web-audio'

/**
 * The playback side's pause (review r4: the barge-in pause was undone by the next audio chunk,
 * because every `play()` resumed a suspended context). A stand-in `AudioContext` that records what
 * the code asks of it; jsdom has no Web Audio.
 */

class FakeContext {
  static made: FakeContext[] = []
  state: 'running' | 'suspended' | 'closed' = 'suspended'
  currentTime = 0
  destination = {}
  resumes = 0
  started = 0
  constructor() {
    FakeContext.made.push(this)
  }
  resume() {
    this.resumes += 1
    this.state = 'running'
    return Promise.resolve()
  }
  suspend() {
    this.state = 'suspended'
    return Promise.resolve()
  }
  close() {
    this.state = 'closed'
    return Promise.resolve()
  }
  createAnalyser() {
    return { fftSize: 0, connect() {}, disconnect() {}, getFloatTimeDomainData() {} }
  }
  createBuffer(_channels: number, length: number, rate: number) {
    return { duration: length / rate, copyToChannel() {} }
  }
  createBufferSource() {
    const context = this
    return {
      buffer: null as unknown,
      onended: null as (() => void) | null,
      connect() {},
      start() {
        context.started += 1
      },
      stop() {},
    }
  }
}

afterEach(() => {
  vi.unstubAllGlobals()
  FakeContext.made = []
})

describe('web audio playback', () => {
  it('audio that arrives while paused is queued, not started: only resume or a flush lets it play', () => {
    vi.stubGlobal('AudioContext', FakeContext)
    const io = createWebAudio()
    io.play(new Int16Array(240), 24_000)
    const context = FakeContext.made[0]
    expect(context.state).toBe('running')
    io.pause?.()
    expect(context.state).toBe('suspended')
    io.play(new Int16Array(240), 24_000)
    io.play(new Int16Array(240), 24_000)
    expect(context.state).toBe('suspended')
    expect(context.started).toBe(3)
    io.resume?.()
    expect(context.state).toBe('running')
    io.pause?.()
    io.flush()
    expect(context.state).toBe('running')
    io.dispose()
  })
})
