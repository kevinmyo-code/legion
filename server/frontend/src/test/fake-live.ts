import type { AudioHandlers, AudioIO } from '../assistant/audio'
import { AudioError } from '../assistant/audio'
import type { SocketLike } from '../assistant/live-client'

/**
 * Stand-ins for the two things a test must never really touch: Google's
 * WebSocket and the device's audio. `FakeLiveSocket` is both a `SocketLike` for
 * unit tests and a global `WebSocket` for tests that render the whole app.
 */

export class FakeLiveSocket implements SocketLike {
  static instances: FakeLiveSocket[] = []
  static reset() {
    FakeLiveSocket.instances = []
  }
  static get last(): FakeLiveSocket {
    const socket = FakeLiveSocket.instances[FakeLiveSocket.instances.length - 1]
    if (!socket) throw new Error('No fake socket was opened.')
    return socket
  }

  binaryType = 'blob'
  readyState = 0
  onopen: ((event: Event) => void) | null = null
  onmessage: ((event: MessageEvent) => void) | null = null
  onerror: ((event: Event) => void) | null = null
  onclose: ((event: CloseEvent) => void) | null = null
  /** Every JSON message the client sent, parsed. */
  sent: Record<string, unknown>[] = []
  closed = false
  readonly url: string

  constructor(url: string) {
    this.url = url
    FakeLiveSocket.instances.push(this)
  }

  send(data: string) {
    this.sent.push(JSON.parse(data) as Record<string, unknown>)
  }

  close() {
    this.closed = true
    this.readyState = 3
  }

  /** The server accepts the connection. */
  open() {
    this.readyState = 1
    this.onopen?.(new Event('open'))
  }

  /** The server sends a message (as the text frame `binaryType` would deliver). */
  serve(message: unknown) {
    this.onmessage?.({ data: JSON.stringify(message) } as MessageEvent)
  }

  /** The server closes the connection. */
  drop(code = 1011, reason = '') {
    this.readyState = 3
    this.onclose?.({ code, reason } as CloseEvent)
  }

  /** Messages the client sent, filtered to one top-level key. */
  sentOf(key: string): Record<string, unknown>[] {
    return this.sent.filter((message) => key in message)
  }
}

export class FakeAudio implements AudioIO {
  handlers: AudioHandlers | null = null
  micOpen = false
  played: { pcm: Int16Array; rate: number }[] = []
  flushes = 0
  disposed = false
  /** Set to make the next `startMic` reject as a browser would. */
  failWith: AudioError | null = null
  /** Hold the permission prompt open until `answer()` is called. */
  private gate: { resolve(): void; reject(error: unknown): void } | null = null
  hold = false

  startMic(handlers: AudioHandlers): Promise<void> {
    this.handlers = handlers
    if (this.failWith) return Promise.reject(this.failWith)
    if (this.hold) {
      return new Promise<void>((resolve, reject) => {
        this.gate = {
          resolve: () => {
            this.micOpen = true
            resolve()
          },
          reject,
        }
      })
    }
    this.micOpen = true
    return Promise.resolve()
  }
  answer(error?: AudioError) {
    if (error) this.gate?.reject(error)
    else this.gate?.resolve()
  }
  stopMic() {
    this.micOpen = false
  }
  play(pcm: Int16Array, rate: number) {
    this.played.push({ pcm, rate })
    this.handlers?.onPlaying(true)
  }
  flush() {
    this.flushes += 1
    this.handlers?.onPlaying(false)
  }
  dispose() {
    this.disposed = true
  }
  /** The microphone produced a chunk. */
  chunk(samples = 320) {
    this.handlers?.onChunk(new Int16Array(samples).fill(1000))
  }
}

/** A tick of the microtask queue chain the client uses to keep frames in order. */
export async function settle() {
  for (let i = 0; i < 8; i += 1) await Promise.resolve()
  await new Promise((resolve) => setTimeout(resolve, 0))
}
