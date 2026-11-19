import { asCard } from '@/components/assistant/cards/types'

import { AudioError, type AudioIO, type AudioLevels } from './audio'
import {
  CONVERSATION_CLOSED,
  ENDED_LIMIT,
  ENDED_LOCKED,
  ENTRY_STALLED,
  TONE_RATE,
  SPEECH_STOPPED,
  TURN_STALLED,
  UNREACHABLE,
  closingTone,
  micNotice,
} from './cascade-words'
import { ConversationRecord, type InterruptSource, type RecordEvent, type RecordTransport } from './conversation-record'
import { Backstop } from './backstop'
import { Endpointer } from './endpointer'
import { GreetingCaps } from './greeting-caps'
import { mergedCard } from './merge-card'
import { readRefusal } from './refusals'
import { ENTRY_MS, ENTRY_TAP_MS, TurnDeadline } from './turn-deadline'
import { newTurnId, postTurn } from './turn-post'
import { utterance } from './utterance'
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
 * A typed message is answered in text only, as on Live, unless a voice conversation is open or the
 * page asks for it aloud (`speakTyped`, the demo's stage): then it is spoken and the conversation
 * carries on as after any turn. What it will not do, as `LiveClient`:
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
  /** This turn's id, for `cancelTurn`. */
  turnId?: string
  /** Its audio began over a reply (a barge-in judged a person): the engine checks it for echo. */
  bargeIn?: boolean
  /** A suggestion chip's token (`POST /api/demo/chips`); `text` is its question word for word. */
  chip?: string
}

/** A suggestion chip the engine offered and checked for this visitor (`demo/suggestions.py`). */
export interface SuggestChip {
  label: string
  question: string
  token: string
}

export interface GreetingHistory {
  today: string[]
  last: string | null
  hellos: string[]
}

export type TurnStart =
  | { ok: true; events: AsyncIterable<SseEvent> }
  | { ok: false; status: number; detail: string | null; code?: string | null; retryMs?: number | null }

export interface CascadeDeps {
  /** Post one turn; resolves when the engine has answered with a stream or a refusal. */
  startTurn(request: TurnRequest, signal: AbortSignal): Promise<TurnStart>
  /** Tell the engine a turn in flight is abandoned (`POST /api/assistant/turn/cancel`). Fire and forget. */
  cancelTurn?: (turnId: string) => void
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
  /**
   * Barge-in by voice (`deps.ts` VOICE_BARGE_IN, off for this ship). Off: half-duplex from the
   * start; what the mic hears while a reply plays, and in its tail, never reaches the models.
   */
  bargeIn?: boolean
  /** An early transcript of what has been said so far (`POST /api/assistant/transcribe`). */
  transcribe?: (audio: Int16Array, signal: AbortSignal) => Promise<string | null>
  /** The `robotic` marker's tap: this reply's turn number, nothing else (`POST /api/assistant/robotic`). */
  robotic?: (conversationId: string, turn: number) => Promise<boolean>
  /** One step of the demo for its funnel counts (`POST /api/demo/funnel`). */
  funnel?: (event: FunnelEvent) => void
  /** The checked chips for a turn's `offer`, asked after the answer has gone out (the demo). */
  chips?: (offer: string) => Promise<SuggestChip[]>
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
  prepare?: (signal: AbortSignal) => Promise<boolean>
  /** Typed messages are answered aloud even with no voice conversation open (the demo's stage). */
  speakTyped?: boolean
  /** The engine says this caller is on Live now (`live_engine`): the page asks again and swaps. */
  onEngineLive?: () => void
}

/** After the reply ends, this much audio is not listened to: its tail is still in the room. */
export const ECHO_TAIL_MS = 300
/** Pre-roll kept ahead of the first voiced chunk (20 ms chunks), so the first word is not clipped. */
const PREROLL_CHUNKS = 15

export { CONVERSATION_CLOSED, closingTone }

type TurnInput = {
  audio?: Int16Array
  text?: string
  greet?: boolean
  spoken?: boolean
  tour?: number
  bargeIn?: boolean
  chip?: string
}
/** The demo entry in flight (`CascadeClient.enter`). */
type Entry = { done: Promise<boolean>; abort: AbortController; timer: ReturnType<typeof setTimeout> }
type Draft<T> = T extends unknown ? Omit<T, 'id'> : never
type DistributiveOmit<T, K extends PropertyKey> = T extends unknown ? Omit<T, K> : never

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
  /** The id of the turn in flight (posted, its stream not yet ended), for the engine's cancel. */
  private turnId: string | null = null
  private deadline: TurnDeadline | null = null
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
  /** When this spoken turn was posted, until its first sound plays (`first_audio`, item 2d). */
  private audioFrom: number | null = null
  private spokenTurn = false
  // ---- the phone's turn-taking (turn/detector.ts) ----
  private detector: TurnDetector | null = null
  private detectorAsked = false
  /** Barge-in is off for this conversation (two self-interrupts, or no models). */
  private halfDuplex = false
  /** Speech has started in the current listening window (the models said so). */
  private speechStarted = false
  /** The reply that was playing when the person talked over it, for the echo check. */
  private bargedOver: string | null = null
  private lastReply = ''
  /** This turn's typed words, which the engine says back as `heard` when it answers aloud. */
  private typedEcho: string | null = null
  /** Turns in a row the engine heard no speech in (noise sent as an utterance). */
  private noSpeechRun = 0
  private spec: { abort: AbortController; result: Promise<string | null>; samples: number } | null = null
  /** The silence that ended each utterance the models ended (the browser check reads it). */
  eotMs: number[] = []
  /** Barge-ins this page has acted on (the browser check reads it). */
  bargeIns = 0
  /** Sentences the engine judged were not for the assistant (section 10), or no speech at all. */
  ignoredTurns = 0
  /** The line this turn's own heard words drew, if any. */
  private heardLineId: number | null = null
  private spokenTurns = 0
  private prepared = false
  /** The demo visit is being entered: taps wait for it (the orb shows "starting"). */
  private entering = false
  /** The gate fired over the reply, which is paused until its verdict; a turn that ended meanwhile. */
  private bargePending: { held: Int16Array | null; afterReply: 'listen' | 'close' | 'tour' | null } | null = null
  /** Audio right after the reply ended that is not yet listened to (ECHO_TAIL_MS). */
  private tailLeftMs = 0
  /** The turn being listened to began over a reply (`barge_in=1` on its post). */
  private bargeTurn = false
  /** The closing tone is what is playing. */
  private toning = false
  private enteringSince = 0
  /** The mic's permission settled for the conversation being opened. */
  private micReady = false
  /** The last turn ended `busy`: the next tap listens in the same conversation. */
  private resumeOnTap = false
  private preparing: Entry | null = null
  /** The page let go of this client: it never asks the engine for anything again. */
  private disposed = false
  // ---- the demo ----
  private tourNext: number | null = null
  strip: StripState = { tools: [], costUsd: null }
  /**
   * The engine sent a turn's cost on this page: Kevin's tester visit, the only one the strip is for
   * (Kevin, 2026-11-19: "B hide the whole strip"; `assistant/cascade.py` sends it to no one else).
   */
  stripShown = false
  /** The last reply's turn number, for the robotic marker. */
  private lastTurn: number | null = null
  // ---- suggestion chips (the demo) ----
  /** The chips on screen: the examples after the greeting, then each answer's follow-ups. */
  chips: readonly SuggestChip[] = []
  /** The greeting (which holds the recording notice) has played to its end, uncut, in this visit
   *  (this page): never reset by a close, so a later chip may speak without a second greeting. */
  private noticeHeard = false
  /** The turn playing now is the greeting. */
  private greetPlaying = false
  /** A card is open over the page (`AssistantModals`). */
  private cardOpen = false
  /** The follow-up window lapsed under an open card and closed quietly: "Tap to ask more". */
  lapsedQuietly = false

  private readonly deps: CascadeDeps
  private readonly options: CascadeOptions
  private readonly caps: GreetingCaps

  private readonly onPageHide = () => this.record?.beacon()
  private readonly onVisibility = () => {
    if (typeof document === 'undefined') return
    const hidden = document.visibilityState === 'hidden'
    if (hidden && this.options.endOnHidden() && this.state.phase === 'live') {
      this.end(ENDED_LOCKED)
      return
    }
    if (this.voiceOpen) this.backstop.visibility(hidden)
  }
  private readonly backstop = new Backstop(
    () => this.deps.now(),
    () => {
      if (this.listening) this.close(true)
    },
    () => {
      if (this.voiceOpen) this.close(true)
    },
    () => this.voiceOpen && !this.listening && !this.playing && !this.awaiting && !this.entering,
    () => this.listen(),
  )

  constructor(deps: CascadeDeps, options: CascadeOptions) {
    this.deps = deps
    this.options = options
    this.caps = new GreetingCaps(() => deps.now(), deps.greetingStore)
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
    // StrictMode runs the provider's effect, its cleanup and the effect again on one client.
    this.disposed = false
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
    // Audible is speaking, even while the rest of the answer streams in: the orb then says a tap
    // interrupts, which is the only way to with voice barge-in off.
    if (this.playing) return 'speaking'
    if (this.awaiting) return 'thinking'
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
    if (!text || this.state.phase === 'ended' || this.disposed) return false
    // Typed into an open voice conversation, or on the voice stage: answered aloud, and the
    // conversation carries on as after any turn. Otherwise in text only, as on Live.
    const aloud = this.voiceOpen || this.options.speakTyped === true
    if (aloud) this.unlockPlayback()
    const wasSpeaking = this.playing
    this.cutReply('typed')
    this.stopListening()
    this.push({ kind: 'me', text, via: 'typed', open: false })
    if (wasSpeaking) this.push({ kind: 'sys', text: 'Stopped speaking because you typed.' })
    void this.afterPrepare(() => this.turn(aloud ? { text, spoken: true } : { text }, aloud, true))
    return true
  }

  /** Open the playback side inside a tap (Safari), without the microphone. */
  private unlockPlayback() {
    try {
      const audio = (this.audioIO ??= this.deps.audio())
      audio.unlock?.((playing) => this.onPlaying(playing))
    } catch {
      // No audio here: the answer still comes as text.
    }
  }

  /**
   * The orb or mic tap when nothing is listening. **Call it straight from the tap's handler**: the
   * mic and the playback context open before anything is awaited, and the greeting request starts
   * in the same tap (the iPhone and Safari rule, spec 29). With the conversation already open (the
   * assistant thinking or speaking), it interrupts and listens again (spec 20, 21).
   */
  startVoice(): Promise<void> {
    if (this.state.phase === 'ended' || this.disposed) return Promise.resolve()
    if (this.voiceOpen) {
      // Taps while the visit is being entered are ignored: a double tap on first contact is one.
      if (this.entering) {
        this.tapDuringEntry()
        return Promise.resolve()
      }
      if (this.playing || this.awaiting) this.cutReply('tap')
      this.listen()
      return Promise.resolve()
    }
    if (this.state.mic !== 'off') return Promise.resolve()
    this.micReady = false
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
    // Barge-in falls back to half-duplex for one conversation, never for the page.
    this.halfDuplex = false
    this.detector?.gate.newConversation()
    this.deps.funnel?.('orb_tap')
    this.loadDetector()
    if (this.resumeOnTap && this.conversationId) this.listen()
    else void this.greetAfterPrepare()
    this.resumeOnTap = false
    return opened.then(
      () => {
        if (generation !== this.micGeneration) return
        this.rec({ kind: 'mic_open', reason: 'tap' })
        this.micReady = true
        if (this.state.mic === 'starting' && !this.entering) this.set({ mic: 'off' })
      },
      (error: unknown) => {
        if (generation !== this.micGeneration) return
        this.voiceOpen = false
        const kind = error instanceof AudioError ? error.kind : 'unavailable'
        this.set({ mic: 'off', notice: micNotice(kind) })
      },
    )
  }

  private greetAfterPrepare() {
    return this.afterPrepare(
      () => this.turn({ greet: true }, true),
      () => {
        this.voiceOpen = false
        this.micGeneration += 1
        this.audioIO?.stopMic()
        this.set({ mic: 'off' })
      },
    )
  }

  /** The phone's turn models, once per page, after the first tap (the page stays light before). */
  private loadDetector() {
    if (this.detectorAsked || !this.deps.turnDetector) return
    this.detectorAsked = true
    void this.deps.turnDetector().then(
      (detector) => {
        if (!detector) return
        // Loaded after the page let go of this client: its workers go too.
        if (this.disposed) return detector.dispose()
        this.detector = detector
        detector.setHandlers({
          onStart: () => this.onSpeechStart(),
          onEarly: (audio) => this.onEarly(audio),
          onResumed: () => this.dropSpec(),
          onEnd: (audio, _reason, silenceMs) => this.onTurnEnd(audio, silenceMs),
          onBargeIn: () => this.onBargeIn(),
          onVerdict: (v) => this.onVerdict(v.selfInterrupt, v.nowDisabled),
        })
      },
      () => {},
    )
  }

  /** Barge-in is live: the models are loaded and this conversation has not fallen back. */
  get fullDuplex(): boolean {
    return this.deps.bargeIn === true && this.detector !== null && !this.halfDuplex && !this.detector.gate.disabled
  }

  /** The demo's one-minute tour: plays beat `beat` (from 0), driven by the engine. */
  startTour(beat = 0) {
    if (this.state.phase === 'ended' || this.disposed) return
    if (beat === 0) this.deps.funnel?.('tour_started')
    this.unlockPlayback()
    this.cutReply('tap')
    this.stopListening()
    void this.afterPrepare(() => this.turn({ tour: beat }, true))
  }

  /**
   * A tapped suggestion chip. Once the recording notice has been heard to its end in this visit
   * (the greeting, on this page), it is like talking over him: playback stops, the turn in flight
   * is cancelled, and the chip's question is asked and answered aloud, in a new conversation if
   * the last one closed (no second greeting: the notice was heard and is on the page). Before
   * that it is a typed turn, answered in text: no voice starts before the notice.
   */
  tapChip(chip: SuggestChip): boolean {
    const text = chip.question.trim()
    if (!text || this.state.phase === 'ended' || this.disposed) return false
    const aloud = this.noticeHeard
    if (aloud) this.unlockPlayback()
    this.cutReply('tap')
    this.stopListening()
    if (aloud) {
      // The engine says it back as `heard`, which draws the line.
      void this.afterPrepare(() => this.turn({ text, spoken: true, chip: chip.token }, true))
    } else {
      this.push({ kind: 'me', text, via: 'typed', open: false })
      void this.afterPrepare(() => this.turn({ text, chip: chip.token }, false, true))
    }
    return true
  }

  /**
   * Run `then` once the demo visit is entered (once per page; every tap waiting on it shares the one
   * entry). A tap, a close or the page leaving while it waits cancels `then` (the turn generation
   * moved), so nothing is asked for that nobody is waiting for.
   */
  private async afterPrepare(then: () => Promise<void> | void, failed?: () => void) {
    if (this.options.prepare && !this.prepared) {
      const generation = this.turnGeneration
      this.awaiting = true
      this.entering = true
      this.enteringSince = this.deps.now()
      this.refresh()
      const entry = (this.preparing ??= this.enter(this.options.prepare))
      const ok = await entry.done
      if (generation !== this.turnGeneration || this.disposed) return
      this.entering = false
      this.awaiting = false
      // The orb held "starting" through the entry; the mic is ready if its permission settled.
      if (this.state.mic === 'starting' && this.micReady) this.set({ mic: 'off' })
      this.refresh()
      if (!ok) {
        failed?.()
        return
      }
    }
    await then()
  }

  /**
   * One entry, with its own deadline (nothing inside it has one, and the mic is already open) and
   * its own abort. Abandoned or superseded, its timer and its answer count for nothing.
   */
  private enter(prepare: (signal: AbortSignal) => Promise<boolean>): Entry {
    const abort = new AbortController()
    const entry: Entry = {
      abort,
      timer: setTimeout(() => {
        if (this.preparing === entry) this.abandonEntry()
      }, ENTRY_MS),
      done: Promise.resolve(false),
    }
    entry.done = prepare(abort.signal)
      .catch(() => false)
      .then((ok) => {
        clearTimeout(entry.timer)
        if (this.preparing !== entry) return false
        this.preparing = null
        if (ok) this.prepared = true
        return ok
      })
    return entry
  }

  private dropEntry() {
    const entry = this.preparing
    this.preparing = null
    if (!entry) return
    clearTimeout(entry.timer)
    entry.abort.abort()
  }

  /**
   * A tap during the entry: ignored in its first seconds (a double tap on first contact is one),
   * after that it gives up on an entry that is taking too long.
   */
  private tapDuringEntry() {
    if (this.deps.now() - this.enteringSince >= ENTRY_TAP_MS) this.abandonEntry()
  }

  /** The entry hung: the mic goes, a notice says so, and the next tap enters afresh. */
  private abandonEntry() {
    if (!this.entering) return
    this.entering = false
    this.dropEntry()
    this.cutReply(null)
    this.voiceOpen = false
    this.micGeneration += 1
    this.audioIO?.stopMic()
    this.set({ mic: 'off', notice: ENTRY_STALLED })
  }

  /** A tap while listening closes the conversation (spec 21); while it speaks, it interrupts. */
  stopVoice() {
    if (this.entering) return this.tapDuringEntry()
    if (this.listening || this.state.mic === 'starting') {
      this.close(false)
      return
    }
    this.interrupt()
  }

  /** Stop the reply now, its audio and the engine's turn, and listen again (spec 20). */
  interrupt() {
    if (this.entering) return this.tapDuringEntry()
    if (this.toning && !this.voiceOpen) {
      // A tap over the closing tone means "again": a new conversation at once, from this tap.
      this.audioIO?.flush()
      void this.startVoice()
      return
    }
    if (!this.playing && !this.awaiting) return
    this.cutReply('tap')
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
    this.chips = []
    this.lapsedQuietly = false
    this.set({ phase: 'idle', mic: 'off', lines: [], notice: null, ended: null })
  }

  dispose() {
    this.disposed = true
    this.dropEntry()
    this.finishRecord('The page closed.', true)
    this.cutReply(null)
    this.stopEverything()
    if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', this.onVisibility)
    if (typeof window !== 'undefined') window.removeEventListener('pagehide', this.onPageHide)
    this.audioIO?.dispose()
    this.audioIO = null
    // The models' two workers live as long as the client, not the page (one set per mount).
    this.detector?.dispose()
    this.detector = null
    this.detectorAsked = false
  }

  // ---- listening -----------------------------------------------------------

  /** Start the follow-up window: the mic's audio counts from now (spec 14). */
  private listen(keepTurn = false) {
    if (!this.voiceOpen) return
    this.afterReply = null
    this.listening = true
    this.endpointer = new Endpointer()
    this.chunks = []
    this.backstop.listening()
    if (!keepTurn) {
      this.speechStarted = false
      this.detector?.reset()
      this.tailLeftMs = ECHO_TAIL_MS
      this.bargeTurn = false
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
    if (playing && this.audioFrom !== null) {
      // Post to first sound, beside the engine's own first-audio time in the record (item 2d).
      this.rec({ kind: 'latency', metric: 'first_audio', ms: Math.max(this.deps.now() - this.audioFrom, 0) })
      this.audioFrom = null
    }
    this.playing = playing
    if (!playing) this.toning = false
    if (!playing && !this.awaiting && this.greetPlaying) {
      // The greeting, recording notice included, played to its end: chips may now speak.
      this.greetPlaying = false
      this.noticeHeard = true
    }
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
    // Ended before the gate's verdict: sent only if it was a person.
    if (this.bargePending) this.bargePending.held = audio
    else void this.sendTurn(audio, spec)
  }

  private async sendTurn(audio: Int16Array, spec: { result: Promise<string | null> } | null) {
    if (spec) {
      const generation = this.turnGeneration
      const text = await spec.result
      if (generation !== this.turnGeneration || this.listening || !this.voiceOpen) return
      if (text && text.trim()) {
        void this.turn({ text: text.trim(), spoken: true, ...this.takeBargeIn() }, true)
        return
      }
    }
    void this.turn({ audio, ...this.takeBargeIn() }, true)
  }

  /** This turn began over a reply (a person, by the gate's verdict): the engine checks it for echo. */
  private takeBargeIn(): { bargeIn?: true } {
    const was = this.bargeTurn
    this.bargeTurn = false
    return was ? { bargeIn: true } : {}
  }

  /**
   * Talked over. The reply is paused, not cut, until the gate's verdict (about 1.2 s): its own
   * echo then plays on with nothing posted (review 2026-11-17: an echo cut the answer and was sent
   * as a paid turn); a person cuts it, and what they said is the turn. Without `pause` here, it
   * cuts at once.
   */
  private onBargeIn() {
    if (!this.voiceOpen || (!this.playing && !this.awaiting) || !this.fullDuplex) return
    if (this.audioIO?.pause) {
      this.audioIO.pause()
      // What follows the reply (listen, close, the next beat) must survive listen() below.
      this.bargePending = { held: null, afterReply: this.afterReply }
    } else this.cutForBargeIn()
    this.listen(true)
    this.speechStarted = true
  }

  private cutForBargeIn() {
    this.bargedOver = this.lastReply
    this.bargeIns += 1
    this.cutReply('barge_in')
    this.bargeTurn = true
  }

  private onVerdict(selfInterrupt: boolean, nowDisabled: boolean) {
    if (nowDisabled) this.halfDuplex = true
    const pending = this.bargePending
    if (!pending) return
    this.bargePending = null
    if (selfInterrupt) {
      // Its own voice: what was heard goes, and the answer plays on from where it paused.
      this.afterReply ??= pending.afterReply
      this.stopListening()
      this.detector?.reset()
      this.audioIO?.resume?.()
      // Nothing left to play (the reply ended while paused): listen now, or nothing ever will.
      if (!this.playing && !this.awaiting) {
        if (this.afterReply) this.replyDrained()
        else this.listen()
      }
      return
    }
    this.cutForBargeIn()
    if (pending.held) void this.sendTurn(pending.held, null)
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
    this.backstop.chunk()
    // The reply's tail is still in the room (output latency, the room): not yet a turn.
    const tail = this.listening && this.tailLeftMs > 0
    if (tail) this.tailLeftMs -= (pcm.length / 16_000) * 1000
    if (this.detector) {
      // Full duplex: the models hear everything, and while the reply plays only the barge-in gate
      // listens. Half-duplex (and always with voice barge-in off): audio heard while the assistant
      // thinks or speaks, or in the tail after it, is thrown away here and reaches nothing (spec 19).
      if (!this.fullDuplex && (!this.listening || tail)) return
      this.detector.push(pcm, this.listening && !tail, this.playing && this.fullDuplex)
      if (!this.listening || !this.endpointer || tail) return
      // The endpointer runs the follow-up window (spec 14, 15) on the clock and the length cap from
      // the models' speech; noise alone never holds it open or reaches the cap.
      const verdict = this.endpointer.push(pcm, this.speechStarted)
      if (verdict === 'lapsed') this.lapse()
      else if (verdict === 'max') {
        this.detector.reset()
        this.chunks.push(pcm)
        this.sendUtterance()
      } else this.chunks.push(pcm)
      if (!this.speechStarted && this.chunks.length > PREROLL_CHUNKS) this.chunks.shift()
      return
    }
    // Half-duplex: audio heard while the assistant thinks or speaks is never kept (spec 19).
    if (!this.listening || !this.endpointer || tail) return
    const verdict = this.endpointer.push(pcm)
    this.chunks.push(pcm)
    if (!this.endpointer.heardSpeech && this.chunks.length > PREROLL_CHUNKS) this.chunks.shift()
    if (verdict === 'end' || verdict === 'max') this.sendUtterance()
    else if (verdict === 'lapsed') this.lapse()
  }

  private sendUtterance() {
    const chunks = this.chunks
    this.stopListening()
    void this.turn({ audio: chunks, ...this.takeBargeIn() }, true)
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
   * The follow-up window lapsed. Over an open card (a diagram, the resume) it closes quietly: no
   * tone, the chips and the typed box stay, and the status reads "Tap to ask more" (item 4).
   */
  private lapse() {
    if (!this.cardOpen) return this.close(true)
    this.close(false, 'window')
    this.lapsedQuietly = true
    this.refresh()
  }

  /** The page's card modal opened or closed (`AssistantModals`). */
  setCardOpen(open: boolean) {
    this.cardOpen = open
  }

  /**
   * Close the conversation (window lapsed, end_conversation, or a tap): the phone's closing tone
   * when it closed by itself, then the note (spec 17). The next tap opens a new one, with a greeting.
   */
  private close(tone: boolean, reason: 'window' | 'tap' = tone ? 'window' : 'tap') {
    if (!this.voiceOpen && this.state.mic === 'off' && !this.conversationId) return
    this.cutReply(null)
    if (tone) this.audioIO?.play(closingTone(), TONE_RATE)
    this.toning = tone && this.playing
    this.rec({ kind: 'mic_closed', reason })
    this.voiceOpen = false
    this.noSpeechRun = 0
    this.resumeOnTap = false
    this.stopListening()
    this.micGeneration += 1
    this.audioIO?.stopMic()
    this.rememberLastAsked()
    this.finishRecord(CONVERSATION_CLOSED)
    this.conversationId = null
    this.backstop.stop()
    if (this.limitTimer) clearTimeout(this.limitTimer)
    this.limitTimer = null
    this.push({ kind: 'sys', text: CONVERSATION_CLOSED })
    this.set({ mic: 'off', phase: 'idle' })
  }

  /**
   * Close and say why (a refusal, a stalled turn): the mic is let go, the closing tone plays, the
   * reason stays on screen as the notice (the caption line under it is "Conversation closed"), and
   * the next tap opens a new conversation.
   */
  private closeWith(notice: Notice) {
    // First the turn: with no voice and no conversation open (a chip, the box, the tour) close()
    // has nothing to close, and the turn must still stop.
    this.cutReply(null)
    this.close(true)
    this.set({ notice })
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
    given: Omit<TurnInput, 'audio'> & { audio?: Int16Array | Int16Array[] },
    spoken: boolean,
    typed = false,
  ) {
    if (this.disposed) return
    this.typedEcho = typed ? given.text ?? null : null
    // Every upload passes here, so none is ever over the engine's cap.
    const { audio: raw, ...rest } = given
    let input: TurnInput = raw ? { ...rest, audio: utterance(raw) } : rest
    if ((input.audio || (typed && input.text)) && input.tour === undefined && this.tourNext !== null) {
      // Said or typed during the tour: the engine hears "skip" and "stop", or answers a question.
      input = { ...input, tour: this.tourNext }
    }
    if (input.tour === undefined) this.tourNext = null
    this.strip = { tools: [], costUsd: null }
    // A new turn: the last answer's chips go (the next answer brings its own).
    this.chips = []
    this.lapsedQuietly = false
    this.greetPlaying = Boolean(input.greet)
    this.lastReply = ''
    this.heardLineId = null
    this.dropTurn()
    const abort = new AbortController()
    this.abort = abort
    const turnId = newTurnId()
    this.turnId = turnId
    const generation = ++this.turnGeneration
    this.deadline?.clear()
    const deadline = new TurnDeadline(() => {
      if (generation === this.turnGeneration) this.closeWith(TURN_STALLED)
    })
    this.deadline = deadline
    this.spokenTurn = spoken
    this.awaiting = true
    this.afterReply = null
    this.replyFrom = input.greet ? null : this.deps.now()
    this.audioFrom = spoken ? this.deps.now() : null
    this.set({ phase: 'live', notice: null })
    let startedConversation = this.conversationId === null
    let request: TurnRequest = {
      ...input,
      ...(input.greet ? { greetingHistory: this.caps.history() } : {}),
      conversationId: this.conversationId,
      utcOffsetMinutes: this.deps.utcOffsetMinutes(),
      preface: this.preface(Boolean(input.greet)),
      turnId,
    }
    let start: TurnStart
    try {
      const post = () =>
        postTurn(
          () => this.deps.startTurn(request, abort.signal),
          () => generation === this.turnGeneration,
        )
      start = await post()
      if (!start.ok && start.code === 'no_conversation' && request.conversationId && generation === this.turnGeneration) {
        // The engine no longer has this conversation: the same words start a new one, once.
        this.conversationId = null
        startedConversation = true
        request = { ...request, conversationId: null, preface: this.preface(Boolean(input.greet)) }
        start = await post()
      }
    } catch {
      if (generation !== this.turnGeneration) return
      this.turnId = null
      deadline.clear()
      this.awaiting = false
      this.push({ kind: 'sys', text: UNREACHABLE })
      this.settle(spoken)
      return
    }
    if (generation !== this.turnGeneration) return
    if (!start.ok) {
      this.turnId = null
      deadline.clear()
      this.awaiting = false
      this.refused(start.status, start.detail, start.code ?? null)
      return
    }
    let open: number | null = null
    let end = false
    let tourNext: number | null = null
    let offer: string | null = null
    const touring = input.tour !== undefined
    try {
      for await (const event of start.events) {
        if (generation !== this.turnGeneration) return
        deadline.heard()
        if (event.type === 'done' && event.data.end === true) end = true
        if (event.type === 'done') {
          const tour = event.data.tour as { next?: number | null } | undefined
          tourNext = typeof tour?.next === 'number' ? tour.next : null
          if (typeof event.data.cost_usd === 'number') {
            this.strip = { ...this.strip, costUsd: event.data.cost_usd }
            this.stripShown = true
          }
          if (typeof event.data.offer === 'string') offer = event.data.offer
          if (touring && tour && tourNext === null) this.deps.funnel?.('tour_finished')
        }
        open = this.onEvent(event, open, startedConversation)
      }
    } catch {
      if (generation !== this.turnGeneration) return
      this.push({ kind: 'sys', text: 'The answer was cut off: the connection to the engine dropped.' })
    } finally {
      if (generation === this.turnGeneration) {
        this.turnId = null
        deadline.clear()
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
        // After the answer has gone out: the chips are asked for, and shown only if no newer
        // turn has started by then.
        if (offer !== null) void this.loadChips(offer, generation)
      }
    }
  }

  private async loadChips(offer: string, generation: number) {
    if (!this.deps.chips) return
    let chips: SuggestChip[]
    try {
      chips = await this.deps.chips(offer)
    } catch {
      return
    }
    if (generation !== this.turnGeneration || this.disposed || this.state.phase === 'ended') return
    this.chips = chips
    this.refresh()
  }

  /** After a spoken turn: listen again once the reply has played (spec 14). */
  private settle(spoken: boolean) {
    if (!spoken || !this.voiceOpen) return
    // Noise the engine heard no words in, twice running: close rather than loop on it.
    this.afterReply = this.noSpeechRun >= 2 ? 'close' : 'listen'
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
        // A typed turn sent to be spoken comes back as `heard`: its line is already on screen.
        const typed = text !== '' && text === this.typedEcho
        if (text) this.noSpeechRun = 0
        if (text && !typed) {
          this.push({ kind: 'me', text, via: 'spoken', open: false })
          this.heardLineId = this.state.lines[this.state.lines.length - 1].id
        }
        this.noteHeard(text)
        return open
      }
      case 'filler': {
        const tool = typeof data.tool === 'string' ? data.tool : ''
        const said = typeof data.text === 'string' ? data.text : ''
        // The acknowledgement (no tool) is speech only, not a step the engine took.
        if (!tool) return open
        // A delayed line (the 0.7 s kind) comes after its tool's own event: it joins that entry.
        const done = this.strip.tools.map((t) => t.tool).lastIndexOf(tool)
        if (done >= 0 && this.strip.tools[done].ms !== null) {
          const tools = [...this.strip.tools]
          tools[done] = { ...tools[done], filler: said }
          this.strip = { ...this.strip, tools }
        } else this.strip = { ...this.strip, tools: [...this.strip.tools, { tool, ms: null, filler: said }] }
        this.refresh()
        return open
      }
      case 'greeting': {
        this.caps.note(typeof data.kind === 'string' ? data.kind : '', typeof data.text === 'string' ? data.text : '')
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
        const merged = mergedCard(this.state.lines, asCard(data.card))
        if (merged.replaces !== null) this.update(merged.replaces, { card: undefined } as Partial<Line>)
        const card = merged.card
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
          // Only this turn's own words: no speech at all ("no_speech") added none.
          const id = this.heardLineId
          if (id !== null) this.set({ lines: this.state.lines.filter((l) => l.id !== id) })
          this.ignoredTurns += 1
          // No speech after a barge-in is the reply's own echo (the self-interrupt count).
          if (data.ignored === 'no_speech') {
            this.noSpeechRun += 1
            this.noteHeard('')
          }
        }
        const words = typeof data.words === 'string' ? data.words : ''
        if (words) this.push({ kind: 'sys', text: words })
        return open
      }
      case 'speech_stopped': {
        // The turn ran out of its speech allowance: the words keep coming, on screen only.
        this.push({ kind: 'sys', text: SPEECH_STOPPED })
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
    // The gate counts each barge-in once, whether its verdict or this comes first.
    if (over === null || !this.detector) return
    const words = text.toLowerCase().replace(/[^a-z0-9 ]/g, ' ').replace(/\s+/g, ' ').trim()
    const reply = over.toLowerCase().replace(/[^a-z0-9 ]/g, ' ').replace(/\s+/g, ' ')
    // Nothing heard, or only what the assistant was saying: the barge-in was its own voice. Two of
    // those and this conversation listens half-duplex, without a word about it.
    if (!words || (words.length >= 4 && reply.includes(words))) {
      if (this.detector.gate.countSelfInterrupt()) this.halfDuplex = true
    }
  }

  private refused(status: number, detail: string | null, code: string | null) {
    const refusal = readRefusal(status, detail, code, this.conversationId !== null)
    switch (refusal.type) {
      case 'wait':
        return this.stillBusy(refusal.text)
      case 'end':
        return this.end(this.options.sessionLimit?.()?.trim() || (refusal.text ?? ENDED_LIMIT))
      case 'close':
        return this.closeWith(refusal.notice)
      case 'live':
        if (!this.options.onEngineLive) return this.closeWith({ kind: 'mint-failed', text: detail ?? 'The assistant could not answer that.' })
        // Quietly: the page swaps to the Live engine's page, and nothing greets into the same refusal.
        this.close(false)
        return this.options.onEngineLive()
      case 'listen':
        if (refusal.caption) this.push({ kind: 'sys', text: refusal.caption })
        this.settle(this.spokenTurn)
    }
  }

  /**
   * Still `busy` after the retries: the conversation stays (its id, its record), the mic is let go,
   * and the next tap listens in it again rather than greeting.
   */
  private stillBusy(text: string) {
    this.push({ kind: 'sys', text })
    if (!this.voiceOpen) return
    this.voiceOpen = false
    this.stopListening()
    this.micGeneration += 1
    this.audioIO?.stopMic()
    this.resumeOnTap = true
    this.set({ mic: 'off' })
  }

  private closeAi(open: number | null, cut: boolean) {
    if (open === null) return
    const line = this.state.lines.find((l) => l.id === open)
    if (line && line.kind === 'ai' && line.open) this.update(open, { open: false, cut } as Partial<Line>)
  }

  /** Stop what is playing and the turn in flight; `source` null records nothing. */
  /** Abort the turn in flight, if any, and tell the engine it is abandoned. */
  private dropTurn() {
    this.abort?.abort()
    this.abort = null
    if (this.turnId) this.deps.cancelTurn?.(this.turnId)
    this.turnId = null
  }

  private cutReply(source: InterruptSource | null) {
    const wasActive = this.playing || this.awaiting
    // A greeting cut short does not count as the recording notice heard.
    this.greetPlaying = false
    this.audioFrom = null
    this.bargePending = null
    this.deadline?.clear()
    this.dropTurn()
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
    this.backstop.stop()
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
    this.chips = []
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
}
