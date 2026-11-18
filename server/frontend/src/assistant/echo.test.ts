import { describe, expect, it, vi } from 'vitest'

import { CascadeClient, type CascadeDeps, type TurnRequest, type TurnStart } from './cascade-client'
import { VOICE_BARGE_IN } from './deps'
import { pcm16ToBase64 } from './pcm'
import type { SseEvent } from './sse'
import { loadTurnDetector, type TurnDetector } from './turn/detector'
import type { FromWorker, ToWorker } from './turn/turn.worker'
import { FakeAudio } from '../test/fake-live'

// Fails fast, with its name, if anything hangs.
vi.setConfig({ testTimeout: 20_000 })

/**
 * The reply heard back by its own microphone (review 2026-11-17: in a browser run Alfred's reply,
 * fed back into the mic, cut the answer and was posted as a paid turn). The real detector, gate and
 * end-of-turn policy run here; only the two models are stood in for, as in `turn/detector.test.ts`
 * (any window with energy is "speech", as Silero says of an echo). The room is `Room`: what plays
 * comes back into the mic loud and a little late (output latency), and stops when playback stops.
 */

class FakeWorker {
  onmessage: ((e: MessageEvent<FromWorker>) => void) | null = null
  private terminated = false
  postMessage(msg: ToWorker) {
    if (this.terminated) return
    let reply: FromWorker | null = null
    if (msg.type === 'init') {
      reply = msg.silero
        ? { type: 'ready', sileroMs: 2, smartMs: null, loadMs: 10 }
        : { type: 'ready', sileroMs: 0, smartMs: 100, loadMs: 10 }
    } else if (msg.type === 'window') {
      let sum = 0
      for (const v of msg.samples) sum += v * v
      reply = { type: 'prob', seq: msg.seq, prob: Math.sqrt(sum / msg.samples.length) > 0.005 ? 0.95 : 0.02, ms: 1 }
    } else if (msg.type === 'check') {
      reply = { type: 'score', checkId: msg.checkId, score: 0.9, ms: 50 }
    }
    if (reply) {
      const answer = reply
      queueMicrotask(() => {
        if (!this.terminated) this.onmessage?.({ data: answer } as MessageEvent<FromWorker>)
      })
    }
  }
  terminate() {
    this.terminated = true
  }
}

/** A steady voice-like tone, loud enough to pass the gate's default echo threshold. */
function tone(ms: number, peak = 26_000): Int16Array {
  const out = new Int16Array(Math.round((ms / 1000) * 16_000))
  for (let i = 0; i < out.length; i++) out[i] = Math.round(peak * Math.sin((2 * Math.PI * 220 * i) / 16_000))
  return out
}

/** The speaker and the mic in one room: 20 ms at a time, what plays comes back `delayMs` later. */
class Room extends FakeAudio {
  private queue: Int16Array[] = []
  private at = 0
  private readonly late: Int16Array[] = []
  playedSamples = 0
  /** Output latency: what plays reaches the mic this much later. */
  private readonly delayMs = 240
  play(pcm: Int16Array, rate: number) {
    super.play(pcm, rate)
    this.queue.push(pcm)
  }
  /** Flushes that cut audio still to play (a typed question over silence flushes nothing real). */
  cuts = 0
  flush() {
    if (this.queue.length > 0) this.cuts += 1
    this.queue = []
    this.at = 0
    super.flush()
  }
  pauses = 0
  pause() {
    this.pauses += 1
    super.pause()
  }
  private voice: Int16Array | null = null
  private voiceAt = 0
  /** Someone in the room talks: mixed into what the mic hears from the next tick. */
  say(pcm: Int16Array) {
    this.voice = pcm
    this.voiceAt = 0
  }
  get sounding(): boolean {
    return this.queue.length > 0
  }
  private ticks = 0
  private waiters: { at: number; go: () => void }[] = []
  /** Resolves once `n` more 20 ms ticks have passed (a stream still sending as it plays). */
  after(n: number): Promise<void> {
    return new Promise((go) => this.waiters.push({ at: this.ticks + n, go }))
  }
  /** 20 ms passes: the speaker plays (unless paused), the mic hears what played `delayMs` ago. */
  tick() {
    const out = new Int16Array(320)
    if (!this.paused && this.queue.length > 0) {
      let n = 0
      while (n < 320 && this.queue.length > 0) {
        const head = this.queue[0]
        const take = Math.min(320 - n, head.length - this.at)
        out.set(head.subarray(this.at, this.at + take), n)
        n += take
        this.at += take
        if (this.at >= head.length) {
          this.queue.shift()
          this.at = 0
        }
      }
      this.playedSamples += n
      if (this.queue.length === 0) this.handlers?.onPlaying(false)
    }
    this.late.push(out)
    const heard = this.late.length > this.delayMs / 20 ? this.late.shift()! : new Int16Array(320)
    if (this.voice) {
      for (let i = 0; i < 320 && this.voiceAt < this.voice.length; i++, this.voiceAt++) {
        heard[i] = Math.max(-32767, Math.min(32767, heard[i] + this.voice[this.voiceAt]))
      }
      if (this.voiceAt >= this.voice.length) this.voice = null
    }
    this.handlers?.onChunk(heard)
    this.ticks += 1
    const due = this.waiters.filter((w) => w.at <= this.ticks)
    this.waiters = this.waiters.filter((w) => w.at > this.ticks)
    for (const w of due) w.go()
  }
}

async function microtasks(n = 30) {
  for (let i = 0; i < n; i++) await Promise.resolve()
}

function spokenReply(pcm: Int16Array, text: string, turn: number): TurnStart {
  const events: SseEvent[] = [
    { type: 'turn', data: { conversation_id: 'c-1', turn } },
    { type: 'sentence', data: { text } },
    { type: 'audio', data: { pcm: pcm16ToBase64(pcm), rate: 16_000 } },
    { type: 'done', data: { status: 'done' } },
  ]
  return {
    ok: true,
    events: (async function* () {
      for (const e of events) {
        await Promise.resolve()
        yield e
      }
    })(),
  }
}

/** A reply whose audio keeps arriving while it plays: ten chunks, one every 200 ms of the room. */
function pacedReply(room: Room, pcm: Int16Array, turn: number): TurnStart {
  return {
    ok: true,
    events: (async function* () {
      yield { type: 'turn', data: { conversation_id: 'c-1', turn } } as SseEvent
      yield { type: 'sentence', data: { text: 'A long answer.' } } as SseEvent
      const size = Math.ceil(pcm.length / 10)
      for (let at = 0; at < pcm.length; at += size) {
        yield { type: 'audio', data: { pcm: pcm16ToBase64(pcm.subarray(at, at + size)), rate: 16_000 } } as SseEvent
        await room.after(10)
      }
      yield { type: 'done', data: { status: 'done' } } as SseEvent
    })(),
  }
}

describe('the reply heard back by the microphone', () => {
  it('pauses at most once per reply, never posts the echo, plays every answer in full, then goes half-duplex', async () => {
    const room = new Room()
    const replies = [tone(3_000), tone(3_000), tone(3_000)]
    const answers = replies.map((pcm, i) => spokenReply(pcm, `Answer ${i}.`, i))
    const requests: TurnRequest[] = []
    let fires = 0
    let detector: TurnDetector | null = null
    const deps: CascadeDeps = {
      startTurn: async (request) => {
        requests.push(request)
        return answers.shift() ?? { ok: false, status: 400, detail: 'nothing scripted', code: 'bad_request' }
      },
      audio: () => room,
      now: () => Date.now(),
      utcOffsetMinutes: () => 0,
      // The barge-in code behind the switch (`deps.ts` VOICE_BARGE_IN, off on the page).
      bargeIn: true,
      turnDetector: async () => {
        detector = await loadTurnDetector({ makeWorker: () => new FakeWorker(), sileroUrl: 's', smartUrl: 't' }, 1000)
        const set = detector!.setHandlers.bind(detector)
        detector!.setHandlers = (h) =>
          set(h && { ...h, onBargeIn: (e) => ((fires += 1), h.onBargeIn(e)) })
        return detector
      },
    }
    const client = new CascadeClient(deps, { companionName: 'Alfred', sessionMinutes: null, endOnHidden: () => false })
    client.attach()

    // The opening tap: the greeting plays into the room.
    void client.startVoice()
    await microtasks()
    for (let t = 0; t < 400 && (room.sounding || t < 20); t++) {
      room.tick()
      await microtasks()
    }
    // Typed questions with the conversation open: answered aloud, into the room again.
    for (const question of ['Tell me more.', 'And then?']) {
      client.send(question)
      await microtasks()
      for (let t = 0; t < 400 && (room.sounding || t < 20); t++) {
        room.tick()
        await microtasks()
      }
    }
    // And quiet after: the echo's tail is not a turn either.
    for (let t = 0; t < 100; t++) {
      room.tick()
      await microtasks()
    }

    expect(fires).toBeLessThanOrEqual(2)
    expect(requests.filter((r) => r.audio)).toHaveLength(0)
    expect(requests).toHaveLength(3)
    expect(room.cuts).toBe(0)
    expect(room.playedSamples).toBe(replies.reduce((n, r) => n + r.length, 0))
    expect(client.fullDuplex).toBe(false)
    // And after each answer the conversation listened again; it still is.
    expect(client.getState().mic).toBe('on')
    client.dispose()
  })

  it('with the stream still sending during the verdict, the pause holds: nothing posted, the answer plays in full', async () => {
    const room = new Room()
    const greeting = tone(200)
    const answer = tone(4_000)
    const answers = [spokenReply(greeting, 'Hi.', 0), pacedReply(room, answer, 1)]
    const requests: TurnRequest[] = []
    let fires = 0
    const deps: CascadeDeps = {
      startTurn: async (request) => {
        requests.push(request)
        return answers.shift() ?? { ok: false, status: 400, detail: 'nothing scripted', code: 'bad_request' }
      },
      audio: () => room,
      now: () => Date.now(),
      utcOffsetMinutes: () => 0,
      // The barge-in code behind the switch (`deps.ts` VOICE_BARGE_IN, off on the page).
      bargeIn: true,
      turnDetector: async () => {
        const detector = await loadTurnDetector({ makeWorker: () => new FakeWorker(), sileroUrl: 's', smartUrl: 't' }, 1000)
        const set = detector!.setHandlers.bind(detector)
        detector!.setHandlers = (h) => set(h && { ...h, onBargeIn: (e) => ((fires += 1), h.onBargeIn(e)) })
        return detector
      },
    }
    const client = new CascadeClient(deps, { companionName: 'Alfred', sessionMinutes: null, endOnHidden: () => false })
    client.attach()
    void client.startVoice()
    await microtasks()
    for (let t = 0; t < 60; t++) {
      room.tick()
      await microtasks()
    }
    client.send('Tell me everything.')
    await microtasks()
    let pausedTicks = 0
    for (let t = 0; t < 800 && (room.sounding || client.getState().activity !== 'listening'); t++) {
      if (room.paused) pausedTicks += 1
      room.tick()
      await microtasks()
    }
    for (let t = 0; t < 40; t++) {
      room.tick()
      await microtasks()
    }
    expect(fires).toBe(1)
    // Held through the verdict window although audio kept arriving.
    expect(pausedTicks).toBeGreaterThanOrEqual(Math.floor(1_200 / 20))
    expect(requests.filter((r) => r.audio)).toHaveLength(0)
    expect(room.cuts).toBe(0)
    expect(room.playedSamples).toBe(greeting.length + answer.length)
    expect(client.getState().mic).toBe('on')
    client.dispose()
  })
})

describe('voice barge-in off (the shipped default, deps.ts VOICE_BARGE_IN)', () => {
  function room(answers: (r: Room) => TurnStart[]) {
    const r = new Room()
    const queue = answers(r)
    const requests: TurnRequest[] = []
    const cancels: string[] = []
    const deps: CascadeDeps = {
      startTurn: async (request) => {
        requests.push(request)
        return queue.shift() ?? { ok: true, events: (async function* () {})() }
      },
      cancelTurn: (id) => cancels.push(id),
      audio: () => r,
      now: () => Date.now(),
      utcOffsetMinutes: () => 0,
      bargeIn: VOICE_BARGE_IN,
      turnDetector: () => loadTurnDetector({ makeWorker: () => new FakeWorker(), sileroUrl: 's', smartUrl: 't' }, 1000),
    }
    const client = new CascadeClient(deps, { companionName: 'Alfred', sessionMinutes: null, endOnHidden: () => false })
    client.attach()
    const run = async (ticks: number, until?: () => boolean) => {
      for (let t = 0; t < ticks && !(until?.() ?? false); t++) {
        r.tick()
        await microtasks()
      }
    }
    return { room: r, client, requests, cancels, run }
  }

  it('is off', () => {
    expect(VOICE_BARGE_IN).toBe(false)
  })

  it.each([
    ['25%', 6_500],
    ['100%', 26_000],
  ])('its own answer heard back at %s, the stream still sending: no pause, no cut, nothing posted, played in full, listening after', async (_level, peak) => {
    const greeting = tone(200, peak)
    const answer = tone(4_000, peak)
    const t = room((r) => [spokenReply(greeting, 'Hi.', 0), pacedReply(r, answer, 1)])
    void t.client.startVoice()
    await microtasks()
    await t.run(60)
    t.client.send('Tell me everything.')
    await microtasks()
    await t.run(800, () => !t.room.sounding && t.client.getState().activity === 'listening')
    await t.run(40)
    expect(t.room.pauses).toBe(0)
    expect(t.room.cuts).toBe(0)
    expect(t.requests.filter((q) => q.audio)).toHaveLength(0)
    expect(t.requests.some((q) => q.bargeIn)).toBe(false)
    expect(t.room.playedSamples).toBe(greeting.length + answer.length)
    expect(t.client.getState().mic).toBe('on')
    t.client.dispose()
  })

  it('a person talking over the answer is not heard; after it (past the 300 ms tail) they are', async () => {
    const answer = tone(3_000)
    const t = room(() => [spokenReply(answer, 'A long answer.', 0)])
    void t.client.startVoice()
    await microtasks()
    await t.run(20)
    t.room.say(tone(1_000, 20_000))
    await t.run(400, () => !t.room.sounding && t.client.getState().activity === 'listening')
    await t.run(40)
    expect(t.requests).toHaveLength(1)
    t.room.say(tone(1_000, 20_000))
    await t.run(150)
    expect(t.requests).toHaveLength(2)
    expect(t.requests[1].audio).toBeDefined()
    expect(t.requests[1].bargeIn).toBeUndefined()
    t.client.dispose()
  })

  it('a tap during the answer still stops it and cancels the turn', async () => {
    const t = room((r) => [spokenReply(tone(200), 'Hi.', 0), pacedReply(r, tone(4_000), 1)])
    void t.client.startVoice()
    await microtasks()
    await t.run(60)
    t.client.send('Tell me everything.')
    await microtasks()
    await t.run(40)
    expect(t.client.getState().activity).toBe('speaking')
    t.client.interrupt()
    expect(t.cancels).toHaveLength(1)
    expect(t.room.cuts).toBe(1)
    t.client.dispose()
  })
})
