import { AudioError, type AudioIO } from './audio'
import { base64ToPcm16, pcm16ToBase64 } from './pcm'

/**
 * One conversation with the household assistant: the engine mints a locked
 * Gemini Live token, the browser opens a WebSocket straight to Google with it,
 * and every tool call Google sends back is run BY THE ENGINE as the signed-in
 * member (web-assistant 02, 07). Nothing here holds a Gemini key; the token is
 * the only credential the browser ever sees (ADR 0056).
 *
 * This class is the whole of the conversation logic and none of the browser: the
 * engine, the socket, the audio and the clock arrive through `LiveDeps`, so the
 * tests drive it with fakes and never call Google. It is a tiny external store
 * (`subscribe` / `getState`) so React reads it with `useSyncExternalStore`.
 *
 * What it will not do, on purpose:
 *  - **Assert an outcome it did not see.** A tool line says `Done:` only after
 *    the engine answered `success: true`; every other result says `Did not run:`
 *    and the engine's own words (CLAUDE.md section 7, outcome verbs).
 *  - **Keep a conversation across a lock.** A screen lock, an app switch (when
 *    told to), Google's ~10 minute `goAway` or a close ENDS it, in words. The
 *    token's setup is fully locked, so there is no resumption; a new
 *    conversation mints a new token.
 *  - **Persist anything.** Lines live in memory and die with the page.
 */

export type Notice = {
  kind:
    | 'no-key'
    | 'throttled'
    | 'engine-unreachable'
    | 'mint-failed'
    | 'connect-failed'
    | 'mic-denied'
    | 'mic-unavailable'
    | 'mic-lost'
    | 'signed-out'
  text: string
}

export type NoticeKind = Notice['kind']

export type Line =
  | { id: number; kind: 'me'; text: string; via: 'typed' | 'spoken'; open: boolean }
  | { id: number; kind: 'ai'; text: string; via: 'typed' | 'spoken'; open: boolean; cut: boolean }
  | { id: number; kind: 'tool'; ok: boolean; text: string }
  | { id: number; kind: 'sys'; text: string }

type Draft<T> = T extends unknown ? Omit<T, 'id'> : never

export interface AssistantState {
  /** idle: nothing running. connecting: minting or opening. live: set up, talking. ended: over, transcript kept. */
  phase: 'idle' | 'connecting' | 'live' | 'ended'
  mic: 'off' | 'starting' | 'on'
  activity: 'idle' | 'listening' | 'thinking' | 'speaking'
  lines: readonly Line[]
  /** The companion's name as the engine's session answer gave it; null before the first mint. */
  companion: string | null
  /** The current failure, in words, until the next attempt. */
  notice: Notice | null
  /** What an ended conversation says; null otherwise. */
  ended: string | null
}

export interface SessionInfo {
  token: string
  model: string
  wsUrl: string
  /** Epoch ms: the socket must be open before this. */
  connectBy: number
  companionName: string
  inputAudioMime: string
  outputAudioRate: number
}

export type SessionResult = { ok: true; session: SessionInfo } | { ok: false; notice: Notice }

export interface ToolResult {
  success: boolean
  /** The engine's words for what happened (or did not). */
  text: string
  /** What goes back to Gemini as `functionResponses[].response`. */
  forward: { success: boolean; message: string }
}

/** The slice of `WebSocket` the client uses. */
export interface SocketLike {
  binaryType: string
  readyState: number
  onopen: ((event: Event) => void) | null
  onmessage: ((event: MessageEvent) => void) | null
  onerror: ((event: Event) => void) | null
  onclose: ((event: CloseEvent) => void) | null
  send(data: string): void
  close(code?: number, reason?: string): void
}

export interface LiveDeps {
  startSession(utcOffsetMinutes: number): Promise<SessionResult>
  runTool(name: string, args: Record<string, unknown>): Promise<ToolResult>
  openSocket(url: string): SocketLike
  /** The device audio. Called once, from the tap that starts the microphone. */
  audio(): AudioIO
  now(): number
  utcOffsetMinutes(): number
}

export interface LiveOptions {
  /** End the conversation when the page is hidden (a screen lock or an app switch on a phone). */
  endOnHidden: () => boolean
}

export const ENDED_LOCKED = 'Conversation ended when the screen locked.'
export const ENDED_TIME_LIMIT = 'The conversation reached its time limit. Start a new one.'
export const ENDED_CLOSED = 'The conversation closed. Start a new one.'

const SOCKET_OPEN = 1
const SETUP_TIMEOUT_MS = 15_000

interface FunctionCall {
  id: string
  name: string
  args?: Record<string, unknown>
}

interface ServerMessage {
  setupComplete?: unknown
  serverContent?: {
    modelTurn?: { parts?: { inlineData?: { mimeType?: string; data?: string } }[] }
    inputTranscription?: { text?: string }
    outputTranscription?: { text?: string }
    interrupted?: boolean
    turnComplete?: boolean
  }
  toolCall?: { functionCalls?: FunctionCall[] }
  toolCallCancellation?: { ids?: string[] }
  goAway?: unknown
}

export class LiveClient {
  private state: AssistantState = {
    phase: 'idle',
    mic: 'off',
    activity: 'idle',
    lines: [],
    companion: null,
    notice: null,
    ended: null,
  }
  private readonly listeners = new Set<() => void>()
  private socket: SocketLike | null = null
  private session: SessionInfo | null = null
  private audioIO: AudioIO | null = null
  private nextId = 1
  /** Bumped by anything that abandons an in-flight start, so a late mint is ignored. */
  private generation = 0
  private micGeneration = 0
  private queued: string[] = []
  private playing = false
  private awaiting = false
  private pendingTools = 0
  /** Whether the reply being heard is a typed turn's (shown, not spoken). */
  private typedTurn = false
  private readonly cancelled = new Set<string>()
  private setupTimer: ReturnType<typeof setTimeout> | null = null
  private inbox: Promise<void> = Promise.resolve()
  private readonly onVisibility = () => {
    if (
      typeof document !== 'undefined' &&
      document.visibilityState === 'hidden' &&
      this.options.endOnHidden() &&
      (this.state.phase === 'connecting' || this.state.phase === 'live')
    ) {
      this.end(ENDED_LOCKED)
    }
  }

  private readonly deps: LiveDeps
  private readonly options: LiveOptions

  constructor(deps: LiveDeps, options: LiveOptions) {
    this.deps = deps
    this.options = options
  }

  /** Start listening for the page being hidden. Idempotent; pair with `dispose()`. */
  attach() {
    if (typeof document !== 'undefined') {
      document.addEventListener('visibilitychange', this.onVisibility)
    }
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
    if (s.phase === 'ended' || s.phase === 'idle') return 'idle'
    if (this.pendingTools > 0 || this.awaiting || s.phase === 'connecting') return 'thinking'
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

  private last(): Line | undefined {
    return this.state.lines[this.state.lines.length - 1]
  }

  // ---- what the person does ---------------------------------------------

  /**
   * A typed turn. Starts a conversation if there is none (mints on the first
   * send, not on opening the box), and goes into the same session a spoken turn
   * would. Returns false when nothing was sent (blank, or the conversation is over).
   */
  send(raw: string): boolean {
    const text = raw.trim()
    if (!text || this.state.phase === 'ended') return false

    const wasSpeaking = this.playing
    this.audioIO?.flush()
    this.closeAi(wasSpeaking)
    this.closeMe()
    this.push({ kind: 'me', text, via: 'typed', open: false })
    if (wasSpeaking) this.push({ kind: 'sys', text: 'Stopped speaking because you typed.' })
    this.typedTurn = true
    this.awaiting = true
    this.set({ notice: null })

    if (this.state.phase === 'live') {
      this.sendTyped(text)
    } else {
      this.queued.push(text)
      if (this.state.phase === 'idle') void this.connect()
    }
    return true
  }

  /**
   * Start the microphone. **Call it straight from the tap's handler**: the mic
   * request and the audio contexts are opened before anything is awaited, because
   * that is the only moment an iPhone allows them. A conversation that is already
   * live (typed so far) gets the mic added to it; otherwise one is started.
   */
  startVoice(): Promise<void> {
    if (this.state.mic !== 'off' || this.state.phase === 'ended') return Promise.resolve()
    this.set({ mic: 'starting', notice: null })
    const generation = ++this.micGeneration
    let opened: Promise<void>
    try {
      const audio = (this.audioIO ??= this.deps.audio())
      opened = audio.startMic({
        onChunk: (pcm) => this.sendAudio(pcm),
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
        this.set({ mic: 'on' })
        if (this.state.phase === 'idle') return this.connect()
      },
      (error: unknown) => {
        if (generation !== this.micGeneration) return
        this.set({ mic: 'off' })
        const kind = error instanceof AudioError ? error.kind : 'unavailable'
        this.set({ notice: micNotice(kind) })
      },
    )
  }

  /** Stop talking; the conversation stays open for typing. */
  stopVoice() {
    this.micGeneration += 1
    this.audioIO?.stopMic()
    this.audioIO?.flush()
    this.playing = false
    this.set({ mic: 'off' })
  }

  /** Tell the assistant to stop speaking now (the Interrupt button). */
  interrupt() {
    if (!this.playing) return
    this.audioIO?.flush()
    this.playing = false
    this.closeAi(true)
    this.refresh()
  }

  /** Throw the conversation away and go back to a blank one. Nothing is kept. */
  reset() {
    this.teardown()
    this.generation += 1
    this.queued = []
    this.cancelled.clear()
    this.awaiting = false
    this.typedTurn = false
    this.pendingTools = 0
    this.set({ phase: 'idle', mic: 'off', lines: [], notice: null, ended: null })
  }

  /** The page is going away: drop everything, no state to show. */
  dispose() {
    this.teardown()
    this.generation += 1
    if (typeof document !== 'undefined') {
      document.removeEventListener('visibilitychange', this.onVisibility)
    }
    this.audioIO?.dispose()
    this.audioIO = null
  }

  // ---- connecting --------------------------------------------------------

  private async connect() {
    const generation = ++this.generation
    this.set({ phase: 'connecting', notice: null })
    let result: SessionResult
    try {
      result = await this.deps.startSession(this.deps.utcOffsetMinutes())
    } catch {
      result = {
        ok: false,
        notice: {
          kind: 'engine-unreachable',
          text: "Can't reach the engine, so nothing was started.",
        },
      }
    }
    if (generation !== this.generation) return
    if (!result.ok) {
      this.failStart(result.notice)
      return
    }
    this.session = result.session
    this.set({ companion: result.session.companionName })

    const sep = result.session.wsUrl.includes('?') ? '&' : '?'
    const url = `${result.session.wsUrl}${sep}access_token=${encodeURIComponent(result.session.token)}`
    let socket: SocketLike
    try {
      socket = this.deps.openSocket(url)
    } catch {
      this.failStart({
        kind: 'connect-failed',
        text: "Couldn't open the connection to the voice service. Nothing was sent.",
      })
      return
    }
    socket.binaryType = 'arraybuffer'
    this.socket = socket
    socket.onopen = () => {
      if (socket !== this.socket) return
      const model = result.session.model.startsWith('models/')
        ? result.session.model
        : `models/${result.session.model}`
      // The rest of the setup is locked inside the token and ignored if sent.
      socket.send(JSON.stringify({ setup: { model } }))
    }
    socket.onmessage = (event) => {
      if (socket !== this.socket) return
      this.inbox = this.inbox.then(() => this.receive(event.data, socket))
    }
    socket.onerror = () => {
      // The close that follows says what happened; an error alone carries nothing.
    }
    socket.onclose = (event) => {
      if (socket !== this.socket) return
      this.socket = null
      this.onClosed(event.reason)
    }
    this.setupTimer = setTimeout(() => {
      if (socket === this.socket && this.state.phase === 'connecting') {
        this.failStart({
          kind: 'connect-failed',
          text: "Couldn't connect to the voice service in time. Nothing was sent.",
        })
      }
    }, SETUP_TIMEOUT_MS)
  }

  private failStart(notice: Notice) {
    this.teardown()
    this.generation += 1
    this.audioIO?.stopMic()
    this.micGeneration += 1
    const undelivered = this.queued.length > 0
    this.queued = []
    this.awaiting = false
    this.typedTurn = false
    if (undelivered) {
      this.push({ kind: 'sys', text: 'Your message was not delivered. Nothing was sent.' })
    }
    this.set({ phase: 'idle', mic: 'off', notice })
  }

  private onClosed(reason: string | undefined) {
    if (this.state.phase === 'connecting') {
      this.failStart({
        kind: 'connect-failed',
        text: `The voice service refused the connection${reason ? `: ${reason}` : ''}. Nothing was sent.`,
      })
      return
    }
    if (this.state.phase === 'live') {
      this.end(reason ? `The conversation closed: ${reason}. Start a new one.` : ENDED_CLOSED)
    }
  }

  // ---- ending ------------------------------------------------------------

  /** The conversation is over; the transcript stays on screen, unsaved, until New conversation. */
  private end(sentence: string) {
    if (this.state.phase === 'ended') return
    this.teardown()
    this.generation += 1
    this.micGeneration += 1
    this.audioIO?.stopMic()
    this.audioIO?.flush()
    this.playing = false
    this.awaiting = false
    this.typedTurn = false
    this.queued = []
    this.closeAi(false)
    this.closeMe()
    this.set({ phase: 'ended', mic: 'off', ended: sentence })
  }

  /** Close the socket and timers without touching what is shown. */
  private teardown() {
    if (this.setupTimer) clearTimeout(this.setupTimer)
    this.setupTimer = null
    const socket = this.socket
    this.socket = null
    if (socket) {
      socket.onopen = socket.onmessage = socket.onerror = socket.onclose = null
      try {
        socket.close(1000, 'done')
      } catch {
        // Already closed.
      }
    }
    this.session = null
    this.micGeneration += 1
    this.audioIO?.stopMic()
    this.audioIO?.flush()
  }

  private micLost() {
    this.micGeneration += 1
    this.audioIO?.stopMic()
    this.audioIO?.flush()
    this.playing = false
    this.set({ mic: 'off', notice: micNotice('lost') })
  }

  // ---- sending -----------------------------------------------------------

  private write(message: unknown) {
    const socket = this.socket
    if (!socket || socket.readyState !== SOCKET_OPEN) return false
    socket.send(JSON.stringify(message))
    return true
  }

  private sendTyped(text: string) {
    this.write({
      clientContent: { turns: [{ role: 'user', parts: [{ text }] }], turnComplete: true },
    })
  }

  private sendAudio(pcm: Int16Array) {
    if (this.state.phase !== 'live' || this.state.mic !== 'on' || !this.session) return
    this.write({
      realtimeInput: {
        audio: { mimeType: this.session.inputAudioMime, data: pcm16ToBase64(pcm) },
      },
    })
  }

  // ---- receiving ---------------------------------------------------------

  private async receive(data: unknown, socket: SocketLike) {
    let text: string
    if (typeof data === 'string') text = data
    else if (Object.prototype.toString.call(data) === '[object ArrayBuffer]') {
      text = new TextDecoder().decode(data as ArrayBuffer)
    }
    else if (typeof Blob !== 'undefined' && data instanceof Blob) text = await data.text()
    else return
    if (socket !== this.socket) return
    let message: ServerMessage
    try {
      message = JSON.parse(text) as ServerMessage
    } catch {
      return
    }
    this.handle(message)
  }

  private handle(message: ServerMessage) {
    if (message.setupComplete !== undefined) this.onSetupComplete()
    if (message.serverContent) this.onContent(message.serverContent)
    if (message.toolCall?.functionCalls?.length) void this.onToolCall(message.toolCall.functionCalls)
    if (message.toolCallCancellation?.ids) {
      for (const id of message.toolCallCancellation.ids) this.cancelled.add(id)
    }
    if (message.goAway !== undefined) this.end(ENDED_TIME_LIMIT)
  }

  private onSetupComplete() {
    if (this.state.phase !== 'connecting') return
    if (this.setupTimer) clearTimeout(this.setupTimer)
    this.setupTimer = null
    this.set({ phase: 'live' })
    const queued = this.queued
    this.queued = []
    for (const text of queued) this.sendTyped(text)
  }

  private onContent(content: NonNullable<ServerMessage['serverContent']>) {
    if (content.interrupted) {
      this.audioIO?.flush()
      this.playing = false
      this.closeAi(true)
      // A typed turn interrupts whatever was being said, and the interruption
      // arrives AFTER the typed turn was sent: it must not unmute or un-await it.
      if (!this.typedTurn) this.awaiting = false
    }
    const heard = content.inputTranscription?.text
    if (heard) {
      // Speech means this turn is a spoken one: its reply is played.
      this.typedTurn = false
      this.appendMe(heard)
    }
    const said = content.outputTranscription?.text
    if (said) this.appendAi(said)
    const audible = this.state.mic === 'on' && !this.typedTurn
    for (const part of content.modelTurn?.parts ?? []) {
      const inline = part.inlineData
      if (!inline?.data || !inline.mimeType?.startsWith('audio/')) continue
      // A typed turn's reply is shown, not spoken: the audio is dropped, the
      // transcript is the answer (the Android ruling, web-assistant 05).
      if (audible && this.audioIO) {
        const rate = Number(/rate=(\d+)/.exec(inline.mimeType)?.[1]) || this.session?.outputAudioRate || 24_000
        this.audioIO.play(base64ToPcm16(inline.data), rate)
      }
    }
    if (content.turnComplete) {
      this.closeAi(false)
      this.closeMe()
      this.awaiting = false
      this.typedTurn = false
    }
    this.refresh()
  }

  private appendMe(text: string) {
    const last = this.last()
    if (last?.kind === 'me' && last.open) {
      this.update(last.id, { text: last.text + text })
      return
    }
    this.closeAi(false)
    this.push({ kind: 'me', text, via: 'spoken', open: true })
  }

  private appendAi(text: string) {
    const last = this.last()
    if (last?.kind === 'ai' && last.open) {
      this.update(last.id, { text: last.text + text })
      return
    }
    this.closeMe()
    const spoken = this.state.mic === 'on' && !this.typedTurn
    this.push({ kind: 'ai', text, via: spoken ? 'spoken' : 'typed', open: true, cut: false })
  }

  private closeAi(cut: boolean) {
    const last = [...this.state.lines].reverse().find((line) => line.kind === 'ai' && line.open)
    if (last) this.update(last.id, { open: false, cut } as Partial<Line>)
  }

  private closeMe() {
    const last = [...this.state.lines].reverse().find((line) => line.kind === 'me' && line.open)
    if (last) this.update(last.id, { open: false } as Partial<Line>)
  }

  // ---- tools -------------------------------------------------------------

  private async onToolCall(calls: FunctionCall[]) {
    this.closeAi(false)
    this.closeMe()
    this.pendingTools += calls.length
    this.refresh()
    const answered = await Promise.all(
      calls.map(async (call) => {
        let result: ToolResult
        try {
          result = await this.deps.runTool(call.name, call.args ?? {})
        } catch {
          const message = 'Nothing was read or written: the engine could not be reached.'
          result = { success: false, text: message, forward: { success: false, message } }
        }
        // The line is the truth about what the engine did, whether or not Gemini
        // still wants the answer.
        this.push({
          kind: 'tool',
          ok: result.success,
          text: result.success ? `Done: ${result.text || call.name}` : `Did not run: ${result.text}`,
        })
        return { id: call.id, name: call.name, response: result.forward }
      }),
    )
    this.pendingTools -= calls.length
    const toSend = answered.filter((reply) => !this.cancelled.has(reply.id))
    for (const call of calls) this.cancelled.delete(call.id)
    if (toSend.length > 0) this.write({ toolResponse: { functionResponses: toSend } })
    this.refresh()
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
      return {
        kind: 'mic-unavailable',
        text: "A microphone couldn't be opened on this device. Typing still works.",
      }
  }
}
