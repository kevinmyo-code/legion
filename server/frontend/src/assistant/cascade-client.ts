import { asCard, mergeCar, type Card } from '@/components/assistant/cards/types'

import { AudioError, type AudioIO, type AudioLevels } from './audio'
import { ConversationRecord, type RecordEvent, type RecordTransport } from './conversation-record'
import { Endpointer } from './endpointer'
import type { TurnDetector } from './turn/detector'
import type { AssistantState, Line, Notice } from './live-client'
import { base64ToPcm16 } from './pcm'
import type { SseEvent } from './sse'

/**
 * One conversation on the engine's own voice pipeline (ADR 0102), with the phone's cloud voice
 * behaviour (`.legion/reports/voice-behaviour-spec-1117.md`, items cited as "spec N"). The browser
 * records an utterance, posts it to `POST /api/assistant/turn`, and the ENGINE transcribes it,
 * runs the text model's tool loop over the web tool door, and speaks the reply back as a stream of
 * events. The browser holds no Gemini credential of any kind.
 *
 * Same surface as `LiveClient` (`AssistantClient`), so every screen works with either; which one
 * a page gets is the engine's answer (`GET /api/assistant/session`, `assistant-context.tsx`).
 *
 * How a spoken conversation runs (Kevin, 2026-11-17: "keep listening unless user says stop or
 * taps to close"):
 * - **The opening tap** unlocks audio and starts the greeting request in the same tap (spec 29),
 *   and the engine greets out loud (spec 25).
 * - **Half-duplex** (spec 19, adapted): the microphone stays open for the conversation, but its
 *   audio is ignored while the assistant thinks or speaks, so the page never hears itself.
 *   Listening starts only once the reply has finished playing.
 * - **The follow-up window** (spec 14, 15): after every reply the page listens again on its own;
 *   eight seconds with no speech closes the conversation, with the phone's closing tone and the
 *   note "Conversation closed" (spec 17). So does `end_conversation` (spec 18), after its sign-off.
 * - **Taps** (spec 21): a tap while listening closes the conversation; a tap while the assistant
 *   thinks or speaks interrupts it and listens again (spec 20).
 * - **Nothing heard / not understood** (spec 32): a caption, no speech, and listening resumes.
 *
 * A typed message is answered in text only, as on Live. What it will not do, as `LiveClient`:
 * assert an outcome the engine did not report (`Done:` only after `ok: true`), or keep anything
 * of the conversation in the browser. The engine writes the conversation record's words and tool
 * calls itself; this sends only what the browser alone knows (mic changes, interruptions, the
 * reply latency, the end). The greeting's caps (spec 27) are the one thing kept in the browser:
 * the kinds of topic led with today and the last hellos' words, never the facts.
 */

export interface TurnRequest {
  audio?: Int16Array
  text?: string
  /** `text` came from an early transcript of speech: answered aloud. */
  spoken?: boolean
  /** The demo tour's beat to play. */
  tour?: number
  greet?: boolean
  greetingHistory?: GreetingHistory
  conversationId: string | null
  utcOffsetMinutes: number
  preface?: string
}

export interface GreetingHistory {
  today: string[]
  last: string | null
  hellos: string[]
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
  /** The greeting caps' store (spec 27). Absent keeps them for this page only. */
  greetingStore?: { load(): string | null; save(value: string): void }
  /**
   * The phone's turn models (Silero + Smart Turn, `turn/detector.ts`), loaded after the first tap.
   * Absent, or resolving null, keeps the energy threshold and half-duplex.
   */
  turnDetector?: () => Promise<TurnDetector | null>
  /** An early transcript of what has been said so far (`POST /api/assistant/transcribe`). */
  transcribe?: (audio: Int16Array, signal: AbortSignal) => Promise<string | null>
  /** The `robotic` marker's tap: this reply's turn number, nothing else (`POST /api/assistant/robotic`). */
  robotic?: (conversationId: string, turn: number) => Promise<boolean>
  /** One step of the demo for its funnel counts (`POST /api/demo/funnel`). */
  funnel?: (event: FunnelEvent) => void
}

export type FunnelEvent = 'orb_tap' | 'first_word' | 'turns_3' | 'tour_started' | 'tour_finished' | 'resume_opened'

/** What the live strip shows while it talks (the demo): the tool, how long, the turn's cost. */
export interface StripEntry {
  tool: string
  ms: number | null
  filler?: string
}

export interface StripState {
  tools: StripEntry[]
  costUsd: number | null
}

export interface CascadeOptions {
  /** The companion's name, from the engine's answer. */
  companionName: string | null
  /** Minutes one conversation may last (a demo visit), or null. */
  sessionMinutes: number | null
  /** As `LiveOptions`: the hidden line a later conversation on the page starts with. */
  openingTurn?: () => string | null
  continueTurn?: () => string | null
  /** As `LiveOptions`: the sentence a conversation ends with at its time limit. */
  sessionLimit?: () => string | null
  endOnHidden: () => boolean
  /**
   * Awaited once, after the opening tap has opened the audio and before the greeting is asked for:
   * the demo enters its visit here, so the tap that unlocks the audio is the tap that enters.
   */
  prepare?: () => Promise<boolean>
}

export const CONVERSATION_CLOSED = 'Conversation closed'
const ENDED_LOCKED = 'Conversation ended when the screen locked.'
const ENDED_LIMIT = 'The conversation reached its time limit. Start a new one.'
const UNREACHABLE = "Not answered: couldn't reach the engine. Nothing was changed."
/** Pre-roll kept ahead of the first voiced chunk (20 ms chunks), so the first word is not clipped. */
const PREROLL_CHUNKS = 15
/** The phone's closing tone (spec 17): 150 ms, half gain. */
const TONE_MS = 150
const TONE_HZ = 880
const TONE_RATE = 24_000

type Draft<T> = T extends unknown ? Omit<T, 'id'> : never
type DistributiveOmit<T, K extends PropertyKey> = T extends unknown ? Omit<T, K> : never

/** The phone's closing tone as 16-bit PCM. */
export function closingTone(): Int16Array {
  const n = Math.round((TONE_RATE * TONE_MS) / 1000)
  const out = new Int16Array(n)
  for (let i = 0; i < n; i++) {
    const fade = Math.min(1, i / 240, (n - i) / 240)
    out[i] = Math.round(0.5 * fade * 0x7fff * Math.sin((2 * Math.PI * TONE_HZ * i) / TONE_RATE))
  }
  return out
}

export class CascadeClient {
  private state: AssistantState
  private readonly listeners = new Set<() => void>()
  private audioIO: AudioIO | null = null
  private nextId = 1
  private micGeneration = 0
  private turnGeneration = 0
  /** The conversation is open for voice: the mic is held and the page re-listens after replies. */
  private voiceOpen = false
  /** Listening for an utterance now (chunks are kept); false while thinking or speaking. */
  private listening = false
  private endpointer: Endpointer | null = null
  private chunks: Int16Array[] = []
  private abort: AbortController | null = null
  private conversationId: string | null = null
  private record: ConversationRecord | null = null
  private playing = false
  private awaiting = false
  /** After the reply: close (end_conversation) or listen again once playback drains. */
  private afterReply: 'listen' | 'close' | 'tour' | null = null
  private greeted = false
  private lastAsked: string | null = null
  private limitTimer: ReturnType<typeof setTimeout> | null = null
  private replyFrom: number | null = null
  private spokenTurn = false
  private memoryHistory: string | null = null
  // ---- the phone's turn-taking (turn/detector.ts) ----
  private detector: TurnDetector | null = null
  private detectorAsked = false
  /** Barge-in is off for this conversation (two self-interrupts, or no models). */
  private halfDuplex = false
  /** Speech has started in the current listening window (the models said so). */
  private speechStarted = false
  /** The reply that was playing when the person talked over it, for the echo check. */
  private bargedOver: string | null = null
  /** The gate's own verdict already counted this barge-in as the reply's echo. */
  private bargeCounted = false
  private lastReply = ''
  private spec: { abort: AbortController; result: Promise<string | null>; samples: number } | null = null
  /** The silence that ended each utterance the models ended (the browser check reads it). */
  eotMs: number[] = []
  /** Barge-ins this page has acted on (the browser check reads it). */
  bargeIns = 0
  /** Sentences the engine judged were not for the assistant (section 10). */
  ignoredTurns = 0
  private spokenTurns = 0
  private prepared = false
  // ---- the demo ----
  private tourNext: number | null = null
  strip: StripState = { tools: [], costUsd: null }
  /** The last reply's turn number, for the robotic marker. */
  private lastTurn: number | null = null

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
    if (this.awaiting) return 'thinking'
    if (this.playing) return 'speaking'
    if (s.mic === 'on') return 'listening'
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
    this.stopListening()
    this.push({ kind: 'me', text, via: 'typed', open: false })
    if (wasSpeaking) this.push({ kind: 'sys', text: 'Stopped speaking because you typed.' })
    void this.afterPrepare(() => this.turn({ text }, false))
    return true
  }

  /**
   * The orb or mic tap when nothing is listening. **Call it straight from the tap's handler**: the
   * mic and the playback context open before anything is awaited, and the greeting request starts
   * in the same tap (the iPhone and Safari rule, spec 29). With the conversation already open (the
   * assistant thinking or speaking), it interrupts and listens again (spec 20, 21).
   */
  startVoice(): Promise<void> {
    if (this.state.phase === 'ended') return Promise.resolve()
    if (this.voiceOpen) {
      if (this.playing || this.awaiting) this.cutReply('barge_in')
      this.listen()
      return Promise.resolve()
    }
    if (this.state.mic !== 'off') return Promise.resolve()
    this.set({ mic: 'starting', notice: null })
    const generation = ++this.micGeneration
    let opened: Promise<void>
    try {
      const audio = (this.audioIO ??= this.deps.audio())
      opened = audio.startMic({
        onChunk: (pcm) => this.onChunk(pcm, generation),
        onMicLost: () => this.micLost(),
        onPlaying: (playing) => this.onPlaying(playing),
      })
    } catch (error) {
      opened = Promise.reject(error)
    }
    // The greeting goes now, in the tap, while the mic permission settles.
    this.voiceOpen = true
    this.deps.funnel?.('orb_tap')
    this.loadDetector()
    void this.greetAfterPrepare()
    return opened.then(
      () => {
        if (generation !== this.micGeneration) return
        this.rec({ kind: 'mic_open', reason: 'tap' })
        if (this.state.mic === 'starting') this.set({ mic: 'off' })
      },
      (error: unknown) => {
        if (generation !== this.micGeneration) return
        this.voiceOpen = false
        const kind = error instanceof AudioError ? error.kind : 'unavailable'
        this.set({ mic: 'off', notice: micNotice(kind) })
      },
    )
  }

  private async greetAfterPrepare() {
    if (this.options.prepare && !this.prepared) {
      this.awaiting = true
      this.refresh()
      let ok = false
      try {
        ok = await this.options.prepare()
      } catch {
        ok = false
      }
      this.awaiting = false
      if (!ok) {
        this.voiceOpen = false
        this.micGeneration += 1
        this.audioIO?.stopMic()
        this.set({ mic: 'off' })
        return
      }
      this.prepared = true
    }
    void this.turn({ greet: true }, true)
  }

  /** The phone's turn models, once per page, after the first tap (the page stays light before). */
  private loadDetector() {
    if (this.detectorAsked || !this.deps.turnDetector) return
    this.detectorAsked = true
    void this.deps.turnDetector().then(
      (detector) => {
        if (!detector) return
        this.detector = detector
        detector.setHandlers({
          onStart: () => this.onSpeechStart(),
          onEarly: (audio) => this.onEarly(audio),
          onResumed: () => this.dropSpec(),
          onEnd: (audio, _reason, silenceMs) => this.onTurnEnd(audio, silenceMs),
          onBargeIn: () => this.onBargeIn(),
          onVerdict: (v) => {
            if (v.selfInterrupt) this.bargeCounted = true
            if (v.nowDisabled) this.halfDuplex = true
          },
        })
      },
      () => {},
    )
  }

  /** Barge-in is live: the models are loaded and this conversation has not fallen back. */
  get fullDuplex(): boolean {
    return this.detector !== null && !this.halfDuplex && !this.detector.gate.disabled
  }

  /** The demo's one-minute tour: plays beat `beat` (from 0), driven by the engine. */
  startTour(beat = 0) {
    if (this.state.phase === 'ended') return
    if (beat === 0) this.deps.funnel?.('tour_started')
    try {
      const audio = (this.audioIO ??= this.deps.audio())
      audio.unlock?.((playing) => this.onPlaying(playing))
    } catch {
      // No audio here: the tour still shows and captions.
    }
    this.cutReply('barge_in')
    this.stopListening()
    void this.afterPrepare(() => this.turn({ tour: beat }, true))
  }

  /**
   * A tapped question answered aloud, for someone who cannot talk (the demo's chips). Call it from
   * the tap: it opens the playback side there (Safari) without opening the microphone.
   */
  ask(raw: string): boolean {
    const text = raw.trim()
    if (!text || this.state.phase === 'ended') return false
    try {
      const audio = (this.audioIO ??= this.deps.audio())
      audio.unlock?.((playing) => this.onPlaying(playing))
    } catch {
      // No audio here: the answer still comes as text.
    }
    this.cutReply('barge_in')
    this.stopListening()
    // The engine says it back as `heard`, which draws the line.
    void this.afterPrepare(() => this.turn({ text, spoken: true }, true))
    return true
  }

  private async afterPrepare(then: () => Promise<void> | void) {
    if (this.options.prepare && !this.prepared) {
      this.awaiting = true
      this.refresh()
      let ok = false
      try {
        ok = await this.options.prepare()
      } catch {
        ok = false
      }
      this.awaiting = false
      this.refresh()
      if (!ok) return
      this.prepared = true
    }
    await then()
  }

  /** A tap while listening closes the conversation (spec 21); while it speaks, it interrupts. */
  stopVoice() {
    if (this.listening || this.state.mic === 'starting') {
      this.close(false)
      return
    }
    this.interrupt()
  }

  /** Stop the reply now, its audio and the engine's turn, and listen again (spec 20). */
  interrupt() {
    if (!this.playing && !this.awaiting) return
    this.cutReply('barge_in')
    if (this.voiceOpen) this.listen()
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

  /** Start the follow-up window: the mic's audio counts from now (spec 14). */
  private listen(keepTurn = false) {
    if (!this.voiceOpen) return
    this.afterReply = null
    this.listening = true
    this.endpointer = new Endpointer()
    this.chunks = []
    if (!keepTurn) {
      this.speechStarted = false
      this.detector?.reset()
    }
    this.set({ mic: 'on', phase: this.state.phase === 'idle' ? 'live' : this.state.phase })
  }

  private stopListening() {
    this.listening = false
    this.endpointer = null
    this.chunks = []
    this.speechStarted = false
    this.dropSpec()
    if (this.state.mic === 'on') this.set({ mic: 'off' })
  }

  private onPlaying(playing: boolean) {
    if (playing !== this.playing) {
      if (playing) this.detector?.replyStarted()
      else this.detector?.replyEnded()
    }
    this.playing = playing
    if (!playing && !this.awaiting) this.replyDrained()
    this.refresh()
  }

  // ---- the models' turn-taking ----------------------------------------------

  private onSpeechStart() {
    if (!this.listening) return
    this.speechStarted = true
  }

  /** The phone's speculation: at 300 ms of silence, transcribe what was said so far. */
  private onEarly(audio: Int16Array) {
    if (!this.listening || !this.deps.transcribe) return
    this.dropSpec()
    const abort = new AbortController()
    this.spec = {
      abort,
      samples: audio.length,
      result: this.deps.transcribe(audio, abort.signal).catch(() => null),
    }
  }

  private dropSpec() {
    this.spec?.abort.abort()
    this.spec = null
  }

  /** The models ended the turn: send it, with the early transcript when nothing was said since. */
  private onTurnEnd(audio: Int16Array, silenceMs: number) {
    if (!this.listening) return
    const spec = this.spec
    this.spec = null
    this.eotMs.push(silenceMs)
    this.listening = false
    this.endpointer = null
    this.chunks = []
    this.speechStarted = false
    if (this.state.mic === 'on') this.set({ mic: 'off' })
    void this.sendTurn(audio, spec)
  }

  private async sendTurn(audio: Int16Array, spec: { result: Promise<string | null> } | null) {
    if (spec) {
      const text = await spec.result
      if (text && text.trim()) {
        void this.turn({ text: text.trim(), spoken: true }, true)
        return
      }
    }
    void this.turn({ audio }, true)
  }

  /** Talked over: the reply stops at once, the engine's turn is cancelled, and this is a turn. */
  private onBargeIn() {
    if (!this.voiceOpen || (!this.playing && !this.awaiting) || !this.fullDuplex) return
    this.bargedOver = this.lastReply
    this.bargeCounted = false
    this.bargeIns += 1
    this.cutReply('barge_in')
    this.listen(true)
    this.speechStarted = true
  }

  /** The reply has played to its end: listen again, or close if it was the sign-off. */
  private replyDrained() {
    const next = this.afterReply
    this.afterReply = null
    if (next === 'close') this.close(true)
    else if (next === 'listen') this.listen()
    else if (next === 'tour' && this.tourNext !== null) {
      const beat = this.tourNext
      void this.turn({ tour: beat }, true)
    }
  }

  private onChunk(pcm: Int16Array, generation: number) {
    if (generation !== this.micGeneration) return
    if (this.detector) {
      // The models hear everything; while the reply plays only the barge-in gate listens, and only
      // in full duplex. Half-duplex: nothing heard while the assistant speaks is kept (spec 19).
      this.detector.push(pcm, this.listening, this.playing && this.fullDuplex)
      if (!this.listening || !this.endpointer) return
      // The energy endpointer still runs the follow-up window (spec 14, 15) and the length cap.
      const verdict = this.endpointer.push(pcm)
      if (verdict === 'lapsed' && !this.speechStarted) this.close(true)
      else if (verdict === 'max') {
        this.detector.reset()
        this.chunks.push(pcm)
        this.sendUtterance()
      } else this.chunks.push(pcm)
      if (!this.speechStarted && this.chunks.length > PREROLL_CHUNKS) this.chunks.shift()
      return
    }
    // Half-duplex: audio heard while the assistant thinks or speaks is never kept (spec 19).
    if (!this.listening || !this.endpointer) return
    const verdict = this.endpointer.push(pcm)
    this.chunks.push(pcm)
    if (!this.endpointer.heardSpeech && this.chunks.length > PREROLL_CHUNKS) this.chunks.shift()
    if (verdict === 'end' || verdict === 'max') this.sendUtterance()
    else if (verdict === 'lapsed') this.close(true)
  }

  private sendUtterance() {
    const chunks = this.chunks
    this.stopListening()
    const total = chunks.reduce((n, c) => n + c.length, 0)
    const audio = new Int16Array(total)
    let at = 0
    for (const chunk of chunks) {
      audio.set(chunk, at)
      at += chunk.length
    }
    void this.turn({ audio }, true)
  }

  private micLost() {
    this.rec({ kind: 'mic_closed', reason: 'lost' })
    this.micGeneration += 1
    this.audioIO?.stopMic()
    this.voiceOpen = false
    this.stopListening()
    this.set({ mic: 'off', notice: micNotice('lost') })
  }

  /**
   * Close the conversation (window lapsed, end_conversation, or a tap): the phone's closing tone
   * when it closed by itself, then the note (spec 17). The next tap opens a new one, with a greeting.
   */
  private close(tone: boolean) {
    if (!this.voiceOpen && this.state.mic === 'off' && !this.conversationId) return
    this.cutReply(null)
    if (tone) this.audioIO?.play(closingTone(), TONE_RATE)
    this.rec({ kind: 'mic_closed', reason: tone ? 'window' : 'tap' })
    this.voiceOpen = false
    this.stopListening()
    this.micGeneration += 1
    this.audioIO?.stopMic()
    this.rememberLastAsked()
    this.finishRecord(CONVERSATION_CLOSED)
    this.conversationId = null
    if (this.limitTimer) clearTimeout(this.limitTimer)
    this.limitTimer = null
    this.push({ kind: 'sys', text: CONVERSATION_CLOSED })
    this.set({ mic: 'off', phase: 'idle' })
  }

  // ---- one turn ------------------------------------------------------------

  private preface(greet: boolean): string | undefined {
    if (this.conversationId) return undefined
    if (this.greeted) {
      const base = this.options.continueTurn?.()?.trim()
      if (!base) return undefined
      const asked = this.lastAsked?.replace(/\s+/g, ' ').trim().slice(0, 240)
      return asked ? `${base} Their last question was: "${asked.replace(/"/g, "'")}"` : base
    }
    const opening = this.options.openingTurn?.()?.trim()
    if (opening) this.greeted = true
    // A spoken greeting is the opener itself; a typed first message carries it as the preface.
    return greet ? undefined : opening || undefined
  }

  private async turn(
    input: { audio?: Int16Array; text?: string; greet?: boolean; spoken?: boolean; tour?: number },
    spoken: boolean,
  ) {
    if (input.audio && input.tour === undefined && this.tourNext !== null) {
      // Said during the tour: the engine hears "skip" and "stop", or answers a question.
      input = { ...input, tour: this.tourNext }
    }
    if (input.tour === undefined) this.tourNext = null
    this.strip = { tools: [], costUsd: null }
    this.lastReply = ''
    this.abort?.abort()
    const abort = new AbortController()
    this.abort = abort
    const generation = ++this.turnGeneration
    this.spokenTurn = spoken
    this.awaiting = true
    this.afterReply = null
    this.replyFrom = input.greet ? null : this.deps.now()
    this.set({ phase: 'live', notice: null })
    const startedConversation = this.conversationId === null
    let start: TurnStart
    try {
      start = await this.deps.startTurn(
        {
          ...input,
          ...(input.greet ? { greetingHistory: this.greetingHistory() } : {}),
          conversationId: this.conversationId,
          utcOffsetMinutes: this.deps.utcOffsetMinutes(),
          preface: this.preface(Boolean(input.greet)),
        },
        abort.signal,
      )
    } catch {
      if (generation !== this.turnGeneration) return
      this.awaiting = false
      this.push({ kind: 'sys', text: UNREACHABLE })
      this.settle(spoken)
      return
    }
    if (generation !== this.turnGeneration) return
    if (!start.ok) {
      this.awaiting = false
      this.refused(start.status, start.detail)
      return
    }
    let open: number | null = null
    let end = false
    let tourNext: number | null = null
    const touring = input.tour !== undefined
    try {
      for await (const event of start.events) {
        if (generation !== this.turnGeneration) return
        if (event.type === 'done' && event.data.end === true) end = true
        if (event.type === 'done') {
          const tour = event.data.tour as { next?: number | null } | undefined
          tourNext = typeof tour?.next === 'number' ? tour.next : null
          if (typeof event.data.cost_usd === 'number') this.strip = { ...this.strip, costUsd: event.data.cost_usd }
          if (touring && tour && tourNext === null) this.deps.funnel?.('tour_finished')
        }
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
        void this.record?.flush()
        this.tourNext = tourNext
        if (end) {
          this.afterReply = 'close'
          if (!this.playing) this.replyDrained()
        } else if (tourNext !== null) {
          // The next beat plays once this one has been heard; the mic listens meanwhile (barge-in).
          this.afterReply = 'tour'
          if (!this.playing) this.replyDrained()
        } else {
          this.settle(spoken)
        }
        this.refresh()
      }
    }
  }

  /** After a spoken turn: listen again once the reply has played (spec 14). */
  private settle(spoken: boolean) {
    if (!spoken || !this.voiceOpen) return
    this.afterReply = 'listen'
    if (!this.playing) this.replyDrained()
  }

  private onEvent(event: SseEvent, open: number | null, startedConversation: boolean): number | null {
    const data = event.data
    switch (event.type) {
      case 'turn': {
        if (typeof data.turn === 'number') this.lastTurn = data.turn
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
        this.noteHeard(text)
        return open
      }
      case 'filler': {
        const tool = typeof data.tool === 'string' ? data.tool : ''
        const said = typeof data.text === 'string' ? data.text : ''
        // The acknowledgement (no tool) is speech only, not a step the engine took.
        if (!tool) return open
        this.strip = { ...this.strip, tools: [...this.strip.tools, { tool, ms: null, filler: said }] }
        this.refresh()
        return open
      }
      case 'greeting': {
        this.noteGreeting(typeof data.kind === 'string' ? data.kind : '', typeof data.text === 'string' ? data.text : '')
        return open
      }
      case 'tool': {
        this.closeAi(open, false)
        const name = typeof data.name === 'string' ? data.name : 'a tool'
        const ms = typeof data.ms === 'number' ? data.ms : null
        const known = this.strip.tools.findIndex((t) => t.tool === name && t.ms === null)
        const tools = [...this.strip.tools]
        if (known >= 0) tools[known] = { ...tools[known], ms }
        else tools.push({ tool: name, ms })
        this.strip = { ...this.strip, tools }
        if (name === 'show_resume' && data.ok === true) this.deps.funnel?.('resume_opened')
        if (name === 'end_conversation') return null
        const ok = data.ok === true
        const text = typeof data.text === 'string' ? data.text : ''
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
        this.lastReply = `${this.lastReply} ${text}`.trim()
        if (this.replyFrom !== null) {
          const ms = Math.max(this.deps.now() - this.replyFrom, 0)
          this.replyFrom = null
          this.rec({ kind: 'latency', metric: 'reply', ms })
        }
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
        if (typeof data.ignored === 'string') {
          // Not said to the assistant (voice-persona section 10): its words leave the screen, and
          // the page listens again with a fresh window. Unsure shows the cue in `words`.
          const last = [...this.state.lines].reverse().find((l) => l.kind === 'me')
          if (last) this.set({ lines: this.state.lines.filter((l) => l.id !== last.id) })
          this.ignoredTurns += 1
        }
        const words = typeof data.words === 'string' ? data.words : ''
        if (words) this.push({ kind: 'sys', text: words })
        return open
      }
      case 'error': {
        const words = typeof data.words === 'string' ? data.words : 'Not answered. Nothing was changed.'
        this.closeAi(open, false)
        this.push({ kind: 'sys', text: words })
        return null
      }
      default:
        return open
    }
  }

  /** "That sounded robotic", by tap: marks the last reply's turn. False when there is none. */
  markRobotic(): boolean {
    if (!this.deps.robotic || this.conversationId === null || this.lastTurn === null) return false
    void this.deps.robotic(this.conversationId, this.lastTurn)
    return true
  }

  /** A turn was heard: the demo's funnel, and whether a barge-in was only the reply's echo. */
  private noteHeard(text: string) {
    if (text) {
      this.spokenTurns += 1
      if (this.spokenTurns === 1) this.deps.funnel?.('first_word')
      if (this.spokenTurns === 3) this.deps.funnel?.('turns_3')
    }
    const over = this.bargedOver
    this.bargedOver = null
    if (over === null || !this.detector || this.bargeCounted) return
    const words = text.toLowerCase().replace(/[^a-z0-9 ]/g, ' ').replace(/\s+/g, ' ').trim()
    const reply = over.toLowerCase().replace(/[^a-z0-9 ]/g, ' ').replace(/\s+/g, ' ')
    // Nothing heard, or only what the assistant was saying: the barge-in was its own voice. Two of
    // those and this conversation listens half-duplex, without a word about it.
    if (!words || (words.length >= 4 && reply.includes(words))) {
      if (this.detector.gate.countSelfInterrupt()) this.halfDuplex = true
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
        this.settle(this.spokenTurn)
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
    this.afterReply = null
    this.awaiting = false
    if (this.playing) this.detector?.replyEnded()
    this.playing = false
    this.audioIO?.flush()
    const open = [...this.state.lines].reverse().find((l) => l.kind === 'ai' && l.open)
    if (open) this.closeAi(open.id, true)
    if (wasActive && source) this.rec({ kind: 'interrupted', reason: source })
  }

  private stopEverything() {
    if (this.limitTimer) clearTimeout(this.limitTimer)
    this.limitTimer = null
    this.micGeneration += 1
    this.voiceOpen = false
    this.listening = false
    this.afterReply = null
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

  // ---- the greeting's caps (spec 27) -----------------------------------------

  private loadHistory(): { day: string; history: GreetingHistory } {
    const day = new Date(this.deps.now()).toDateString()
    let raw: string | null = this.memoryHistory
    try {
      raw = this.deps.greetingStore?.load() ?? raw
    } catch {
      // Storage refused (a private window): the page's own memory stands in.
    }
    try {
      const parsed = raw ? (JSON.parse(raw) as { day?: string; history?: GreetingHistory }) : null
      const history = parsed?.history
      if (history && Array.isArray(history.hellos)) {
        return { day, history: { ...history, today: parsed?.day === day ? history.today ?? [] : [] } }
      }
    } catch {
      // Not ours, or not JSON: start clean.
    }
    return { day, history: { today: [], last: null, hellos: [] } }
  }

  private greetingHistory(): GreetingHistory {
    return this.loadHistory().history
  }

  private noteGreeting(kind: string, text: string) {
    if (!kind || kind === 'scripted') return
    const { day, history } = this.loadHistory()
    if (kind === 'hello') {
      history.hellos = [...history.hellos, text].slice(-3)
      history.last = 'hello'
    } else {
      history.today = [...history.today, kind]
      history.last = kind
    }
    const value = JSON.stringify({ day, history })
    this.memoryHistory = value
    try {
      this.deps.greetingStore?.save(value)
    } catch {
      // Storage refused: kept in memory for this page.
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
