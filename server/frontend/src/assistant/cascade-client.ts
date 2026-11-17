import { asCard, mergeCar, type Card } from '@/components/assistant/cards/types'

import { AudioError, type AudioIO, type AudioLevels } from './audio'
import { ConversationRecord, type RecordEvent, type RecordTransport } from './conversation-record'
import { Endpointer } from './endpointer'
import type { AssistantState, Line, Notice } from './live-client'
import { base64ToPcm16 } from './pcm'
import type { SseEvent } from './sse'

/**
 * One conversation on the engine's own voice pipeline (ADR 0102): the browser records one
 * utterance, posts it to `POST /api/assistant/turn`, and the ENGINE transcribes it, runs the text
 * model's tool loop over the web tool door, and speaks the reply back as a stream of events. The
 * browser holds no Gemini credential of any kind.
 *
 * Same surface as `LiveClient` (`AssistantClient`), so every screen works with either; which one
 * a page gets is the engine's answer (`GET /api/assistant/session`, `assistant-context.tsx`).
 *
 * The turn is the person's: tap to talk, and the utterance ends after a pause
 * (`endpointer.ts`), or at a second tap. A tap while the assistant speaks interrupts it. A typed
 * message is answered in text only, as on Live. What it will not do, as `LiveClient`: assert an
 * outcome the engine did not report (`Done:` only after `ok: true`), or keep anything in the
 * browser. The engine writes the conversation record's words and tool calls itself; this sends
 * only what the browser alone knows (mic changes, interruptions, the reply latency, the end).
 */

export interface TurnRequest {
  audio?: Int16Array
  text?: string
  conversationId: string | null
  utcOffsetMinutes: number
  preface?: string
}

export type TurnStart =
  | { ok: true; events: AsyncIterable<SseEvent> }
  | { ok: false; status: number; detail: string | null }

export interface CascadeDeps {
  /** Post one turn; resolves when the engine has answered with a stream or a refusal. */
  startTurn(request: TurnRequest, signal: AbortSignal): Promise<TurnStart>
  /** The device audio. Called once, from the tap that starts the microphone. */
  audio(): AudioIO
  now(): number
  utcOffsetMinutes(): number
  record?: RecordTransport
}

export interface CascadeOptions {
  /** The companion's name, from the engine's answer. */
  companionName: string | null
  /** Minutes one conversation may last (a demo visit), or null. */
  sessionMinutes: number | null
  /** As `LiveOptions`: a hidden line ahead of a new conversation's first turn. */
  openingTurn?: () => string | null
  continueTurn?: () => string | null
  /** As `LiveOptions`: the sentence a conversation ends with at its time limit. */
  sessionLimit?: () => string | null
  endOnHidden: () => boolean
}

const ENDED_LOCKED = 'Conversation ended when the screen locked.'
const ENDED_LIMIT = 'The conversation reached its time limit. Start a new one.'
const NOTHING_HEARD = "I didn't hear anything, so nothing was sent. Tap the mic and talk, or type."
const UNREACHABLE = "Can't reach the engine, so nothing was asked."

type Draft<T> = T extends unknown ? Omit<T, 'id'> : never
type DistributiveOmit<T, K extends PropertyKey> = T extends unknown ? Omit<T, K> : never

export class CascadeClient {
  private state: AssistantState
  private readonly listeners = new Set<() => void>()
  private audioIO: AudioIO | null = null
  private nextId = 1
  private micGeneration = 0
  private turnGeneration = 0
  private endpointer: Endpointer | null = null
  private chunks: Int16Array[] = []
  private abort: AbortController | null = null
  private conversationId: string | null = null
  private record: ConversationRecord | null = null
  private playing = false
  private awaiting = false
  private greeted = false
  private lastAsked: string | null = null
  private limitTimer: ReturnType<typeof setTimeout> | null = null
  /** When the person's turn ended, until the reply's first sentence (the `reply` latency). */
  private replyFrom: number | null = null
  private spokenTurn = false

  private readonly deps: CascadeDeps
  private readonly options: CascadeOptions

  private readonly onPageHide = () => this.record?.beacon()
  private readonly onVisibility = () => {
    if (
      typeof document !== 'undefined' &&
      document.visibilityState === 'hidden' &&
      this.options.endOnHidden() &&
      this.state.phase === 'live'
    ) {
      this.end(ENDED_LOCKED)
    }
  }

  constructor(deps: CascadeDeps, options: CascadeOptions) {
    this.deps = deps
    this.options = options
    this.state = {
      phase: 'idle',
      mic: 'off',
      activity: 'idle',
      lines: [],
      companion: options.companionName,
      notice: null,
      ended: null,
    }
  }

  attach() {
    if (typeof document !== 'undefined') document.addEventListener('visibilitychange', this.onVisibility)
    if (typeof window !== 'undefined') window.addEventListener('pagehide', this.onPageHide)
  }

  // ---- store -------------------------------------------------------------

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  getState = (): AssistantState => this.state

  private set(patch: Partial<AssistantState>) {
    const next = { ...this.state, ...patch }
    next.activity = this.activityFor(next)
    this.state = next
    for (const listener of this.listeners) listener()
  }

  private activityFor(s: AssistantState): AssistantState['activity'] {
    if (s.phase === 'ended') return 'idle'
    if (s.mic === 'on') return 'listening'
    if (this.awaiting) return 'thinking'
    if (this.playing) return 'speaking'
    return 'idle'
  }

  private refresh() {
    this.set({})
  }

  private push(line: Draft<Line>) {
    this.set({ lines: [...this.state.lines, { ...line, id: this.nextId++ } as Line] })
  }

  private update(id: number, change: Partial<Line>) {
    this.set({
      lines: this.state.lines.map((line) => (line.id === id ? ({ ...line, ...change } as Line) : line)),
    })
  }

  // ---- the record (only what the browser alone knows) ----------------------

  private rec(event: DistributiveOmit<RecordEvent, 'at_ms'>) {
    if (!this.deps.record) return
    this.record ??= new ConversationRecord(this.deps.record)
    this.record.add({ ...event, at_ms: this.deps.now() } as RecordEvent)
  }

  private finishRecord(reason: string | null, beacon = false) {
    const record = this.record
    if (!record) return
    if (reason !== null && record.conversationId) this.rec({ kind: 'session_end', reason })
    this.record = null
    if (beacon) record.beacon()
    else void record.flush()
  }

  // ---- what the person does ---------------------------------------------

  send(raw: string): boolean {
    const text = raw.trim()
    if (!text || this.state.phase === 'ended') return false
    const wasSpeaking = this.playing
    this.cutReply('barge_in')
    if (this.state.mic === 'on') this.dropMic('typed')
    this.push({ kind: 'me', text, via: 'typed', open: false })
    if (wasSpeaking) this.push({ kind: 'sys', text: 'Stopped speaking because you typed.' })
    void this.turn({ text }, false)
    return true
  }

  /**
   * Start listening for one utterance. **Call it straight from the tap's handler**: the mic and
   * the playback context open before anything is awaited (the iPhone and Safari rule), so the
   * reply can play later without another gesture.
   */
  startVoice(): Promise<void> {
    if (this.state.mic !== 'off' || this.state.phase === 'ended') return Promise.resolve()
    if (this.playing || this.awaiting) this.cutReply('barge_in')
    this.set({ mic: 'starting', notice: null })
    const generation = ++this.micGeneration
    this.chunks = []
    this.endpointer = new Endpointer()
    let opened: Promise<void>
    try {
      const audio = (this.audioIO ??= this.deps.audio())
      opened = audio.startMic({
        onChunk: (pcm) => this.onChunk(pcm, generation),
        onMicLost: () => this.micLost(),
        onPlaying: (playing) => {
          this.playing = playing
          this.refresh()
        },
      })
    } catch (error) {
      opened = Promise.reject(error)
    }
    return opened.then(
      () => {
        if (generation !== this.micGeneration) return
        this.set({ mic: 'on', phase: this.state.phase === 'idle' ? 'live' : this.state.phase })
        this.rec({ kind: 'mic_open', reason: 'tap' })
      },
      (error: unknown) => {
        if (generation !== this.micGeneration) return
        const kind = error instanceof AudioError ? error.kind : 'unavailable'
        this.set({ mic: 'off', notice: micNotice(kind) })
      },
    )
  }

  /** A tap while listening: the utterance ends now and is sent (if anything was said). */
  stopVoice() {
    if (this.state.mic === 'on') {
      this.finishUtterance('stopped')
      return
    }
    if (this.state.mic === 'starting') {
      this.micGeneration += 1
      this.audioIO?.stopMic()
      this.set({ mic: 'off' })
      return
    }
    this.interrupt()
  }

  /** Stop the reply now: its audio, and the engine's turn if it is still running. */
  interrupt() {
    if (!this.playing && !this.awaiting) return
    this.cutReply('barge_in')
    this.refresh()
  }

  levels(): AudioLevels {
    return this.audioIO?.levels?.() ?? { input: 0, output: 0 }
  }

  reset() {
    this.rememberLastAsked()
    this.cutReply(null)
    this.finishRecord('New conversation.')
    this.stopEverything()
    this.conversationId = null
    this.set({ phase: 'idle', mic: 'off', lines: [], notice: null, ended: null })
  }

  dispose() {
    this.finishRecord('The page closed.', true)
    this.cutReply(null)
    this.stopEverything()
    if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', this.onVisibility)
    if (typeof window !== 'undefined') window.removeEventListener('pagehide', this.onPageHide)
    this.audioIO?.dispose()
    this.audioIO = null
  }

  // ---- listening -----------------------------------------------------------

  private onChunk(pcm: Int16Array, generation: number) {
    if (generation !== this.micGeneration || this.state.mic !== 'on' || !this.endpointer) return
    this.chunks.push(pcm)
    const verdict = this.endpointer.push(pcm)
    if (verdict === 'end') this.finishUtterance('silence')
    else if (verdict === 'max') this.finishUtterance('max_length')
    else if (verdict === 'nothing') this.finishUtterance('no_speech')
  }

  private finishUtterance(reason: string) {
    const heard = this.endpointer?.heardSpeech ?? false
    const chunks = this.chunks
    this.dropMic(reason)
    if (!heard) {
      this.push({ kind: 'sys', text: NOTHING_HEARD })
      return
    }
    const total = chunks.reduce((n, c) => n + c.length, 0)
    const audio = new Int16Array(total)
    let at = 0
    for (const chunk of chunks) {
      audio.set(chunk, at)
      at += chunk.length
    }
    void this.turn({ audio }, true)
  }

  private dropMic(reason: string) {
    if (this.state.mic === 'on') this.rec({ kind: 'mic_closed', reason })
    this.micGeneration += 1
    this.audioIO?.stopMic()
    this.endpointer = null
    this.chunks = []
    this.set({ mic: 'off' })
  }

  private micLost() {
    this.rec({ kind: 'mic_closed', reason: 'lost' })
    this.micGeneration += 1
    this.audioIO?.stopMic()
    this.endpointer = null
    this.chunks = []
    this.set({ mic: 'off', notice: micNotice('lost') })
  }

  // ---- one turn ------------------------------------------------------------

  private preface(): string | undefined {
    if (this.conversationId) return undefined
    if (this.greeted) {
      const base = this.options.continueTurn?.()?.trim()
      if (!base) return undefined
      const asked = this.lastAsked?.replace(/\s+/g, ' ').trim().slice(0, 240)
      return asked ? `${base} Their last question was: "${asked.replace(/"/g, "'")}"` : base
    }
    const opening = this.options.openingTurn?.()?.trim()
    if (opening) this.greeted = true
    return opening || undefined
  }

  private async turn(input: { audio?: Int16Array; text?: string }, spoken: boolean) {
    this.abort?.abort()
    const abort = new AbortController()
    this.abort = abort
    const generation = ++this.turnGeneration
    this.spokenTurn = spoken
    this.awaiting = true
    this.replyFrom = this.deps.now()
    this.set({ phase: 'live', notice: null })
    const startedConversation = this.conversationId === null
    let start: TurnStart
    try {
      start = await this.deps.startTurn(
        {
          ...input,
          conversationId: this.conversationId,
          utcOffsetMinutes: this.deps.utcOffsetMinutes(),
          preface: this.preface(),
        },
        abort.signal,
      )
    } catch {
      if (generation !== this.turnGeneration) return
      this.awaiting = false
      this.set({ notice: { kind: 'engine-unreachable', text: UNREACHABLE } })
      return
    }
    if (generation !== this.turnGeneration) return
    if (!start.ok) {
      this.awaiting = false
      this.refused(start.status, start.detail)
      return
    }
    let open: number | null = null
    try {
      for await (const event of start.events) {
        if (generation !== this.turnGeneration) return
        open = this.onEvent(event, open, startedConversation)
      }
    } catch {
      if (generation !== this.turnGeneration) return
      this.push({ kind: 'sys', text: 'The answer was cut off: the connection to the engine dropped.' })
    } finally {
      if (generation === this.turnGeneration) {
        this.closeAi(open, false)
        this.awaiting = false
        this.abort = null
        this.refresh()
        void this.record?.flush()
      }
    }
  }

  private onEvent(event: SseEvent, open: number | null, startedConversation: boolean): number | null {
    const data = event.data
    switch (event.type) {
      case 'turn': {
        const id = typeof data.conversation_id === 'string' ? data.conversation_id : null
        if (id && id !== this.conversationId) {
          this.conversationId = id
          if (this.deps.record) {
            this.record ??= new ConversationRecord(this.deps.record)
            this.record.conversationId = id
          }
          if (startedConversation) this.armLimit()
        }
        return open
      }
      case 'heard': {
        const text = typeof data.text === 'string' ? data.text.trim() : ''
        if (text) this.push({ kind: 'me', text, via: 'spoken', open: false })
        return open
      }
      case 'tool': {
        this.closeAi(open, false)
        const ok = data.ok === true
        const text = typeof data.text === 'string' ? data.text : ''
        const name = typeof data.name === 'string' ? data.name : 'a tool'
        const card = this.mergedCard(asCard(data.card))
        this.push({
          kind: 'tool',
          ok,
          text: ok ? `Done: ${text || name}` : `Did not run: ${text}`,
          ...(card ? { card } : {}),
        })
        return null
      }
      case 'sentence': {
        const text = typeof data.text === 'string' ? data.text : ''
        if (!text) return open
        if (this.replyFrom !== null) {
          const ms = Math.max(this.deps.now() - this.replyFrom, 0)
          this.replyFrom = null
          this.rec({ kind: 'latency', metric: 'reply', ms })
        }
        this.awaiting = false
        if (open !== null) {
          const line = this.state.lines.find((l) => l.id === open)
          if (line && line.kind === 'ai') {
            this.update(open, { text: `${line.text} ${text}` } as Partial<Line>)
            return open
          }
        }
        this.push({ kind: 'ai', text, via: this.spokenTurn ? 'spoken' : 'typed', open: true, cut: false })
        return this.nextId - 1
      }
      case 'audio': {
        if (!this.spokenTurn || !this.audioIO || typeof data.pcm !== 'string') return open
        const rate = typeof data.rate === 'number' ? data.rate : 24_000
        this.audioIO.play(base64ToPcm16(data.pcm), rate)
        return open
      }
      case 'done': {
        const words = typeof data.words === 'string' ? data.words : ''
        if (words) this.push({ kind: 'sys', text: words })
        return open
      }
      case 'error': {
        const words = typeof data.words === 'string' ? data.words : 'The engine could not answer that.'
        this.closeAi(open, false)
        this.push({ kind: 'sys', text: words })
        return null
      }
      default:
        return open
    }
  }

  private refused(status: number, detail: string | null) {
    const said = (fallback: string) => detail ?? fallback
    switch (status) {
      case 409:
        // A demo conversation's time limit, or this page asked the wrong engine.
        if (this.conversationId) {
          this.end(this.options.sessionLimit?.()?.trim() || said(ENDED_LIMIT))
          return
        }
        this.set({ notice: { kind: 'mint-failed', text: said('The assistant could not answer that.') } })
        return
      case 429:
        this.set({ notice: { kind: 'throttled', text: said('Too much at once. Try again in a little while.') } })
        return
      case 503:
        this.set({ notice: { kind: 'no-key', text: said("The assistant isn't set up on this server.") } })
        return
      case 401:
      case 403:
        this.set({ notice: { kind: 'signed-out', text: 'You are signed out, so nothing was asked. Sign in again.' } })
        return
      default:
        this.push({ kind: 'sys', text: said('The engine refused that, so nothing was asked.') })
    }
  }

  private closeAi(open: number | null, cut: boolean) {
    if (open === null) return
    const line = this.state.lines.find((l) => l.id === open)
    if (line && line.kind === 'ai' && line.open) this.update(open, { open: false, cut } as Partial<Line>)
  }

  /** Stop what is playing and the turn in flight; `source` null records nothing. */
  private cutReply(source: 'barge_in' | null) {
    const wasActive = this.playing || this.awaiting
    this.abort?.abort()
    this.abort = null
    this.turnGeneration += 1
    this.audioIO?.flush()
    this.playing = false
    this.awaiting = false
    const open = [...this.state.lines].reverse().find((l) => l.kind === 'ai' && l.open)
    if (open) this.closeAi(open.id, true)
    if (wasActive && source) this.rec({ kind: 'interrupted', reason: source })
  }

  private stopEverything() {
    if (this.limitTimer) clearTimeout(this.limitTimer)
    this.limitTimer = null
    this.micGeneration += 1
    this.audioIO?.stopMic()
    this.audioIO?.flush()
    this.playing = false
    this.awaiting = false
    this.endpointer = null
    this.chunks = []
  }

  private armLimit() {
    const minutes = this.options.sessionMinutes
    if (!minutes) return
    if (this.limitTimer) clearTimeout(this.limitTimer)
    this.limitTimer = setTimeout(() => {
      this.end(this.options.sessionLimit?.()?.trim() || ENDED_LIMIT)
    }, minutes * 60_000)
  }

  private end(sentence: string) {
    if (this.state.phase === 'ended') return
    this.rememberLastAsked()
    this.cutReply(null)
    this.stopEverything()
    this.finishRecord(sentence)
    this.conversationId = null
    this.set({ phase: 'ended', mic: 'off', ended: sentence })
  }

  private rememberLastAsked() {
    for (let i = this.state.lines.length - 1; i >= 0; i--) {
      const line = this.state.lines[i]
      if (line.kind === 'me' && line.text.trim()) {
        this.lastAsked = line.text
        return
      }
    }
  }

  /** As `LiveClient.mergedCard`: one car card per car, one diagram card per view. */
  private mergedCard(card: Card | undefined): Card | undefined {
    if (!card) return undefined
    const lines = this.state.lines
    for (let i = lines.length - 1; i >= 0; i--) {
      const line = lines[i]
      if (line.kind !== 'tool' || !line.card) continue
      if (card.card === 'car' && line.card.card === 'car' && line.card.name === card.name) {
        this.update(line.id, { card: undefined } as Partial<Line>)
        return mergeCar(line.card, card)
      }
      if (
        card.card === 'diagram' &&
        line.card.card === 'diagram' &&
        line.card.project === card.project &&
        line.card.view === card.view
      ) {
        this.update(line.id, { card: undefined } as Partial<Line>)
      }
      return card
    }
    return card
  }
}

function micNotice(kind: 'denied' | 'unavailable' | 'lost'): Notice {
  switch (kind) {
    case 'denied':
      return {
        kind: 'mic-denied',
        text: 'The microphone is blocked for this app. To talk, allow the microphone for LEGION in your device or browser settings, then try again. Typing still works.',
      }
    case 'lost':
      return {
        kind: 'mic-lost',
        text: 'The microphone stopped, so nobody is listening. Tap the mic to talk again. Typing still works.',
      }
    default:
      return { kind: 'mic-unavailable', text: "A microphone couldn't be opened on this device. Typing still works." }
  }
}
