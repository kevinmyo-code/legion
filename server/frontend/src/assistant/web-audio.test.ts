import { afterEach, describe, expect, it, vi } from 'vitest'

import { createWebAudio } from './web-audio'

/**
 * The playback side's flush (a barge-in or a tap cuts the reply at once). A stand-in `AudioContext`
 * that records what the code asks of it; jsdom has no Web Audio.
 */

class FakeContext {
  static made: FakeContext[] = []
  state: 'running' | 'suspended' | 'closed' = 'suspended'
  currentTime = 0
  destination = {}
  resumes = 0
  started = 0
  stopped = 0
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
      stop() {
        context.stopped += 1
      },
    }
  }
}

afterEach(() => {
  vi.unstubAllGlobals()
  FakeContext.made = []
})

describe('web audio playback', () => {
  it('a flush stops every queued sentence at once and says playback ended', () => {
    vi.stubGlobal('AudioContext', FakeContext)
    const io = createWebAudio()
    const playing: boolean[] = []
    io.unlock?.((p) => playing.push(p))
    io.play(new Int16Array(240), 24_000)
    io.play(new Int16Array(240), 24_000)
    io.play(new Int16Array(240), 24_000)
    const context = FakeContext.made[0]
    expect(context.started).toBe(3)
    io.flush()
    expect(context.stopped).toBe(3)
    expect(playing).toEqual([true, false])
    expect(context.state).toBe('running')
    io.dispose()
  })
})
