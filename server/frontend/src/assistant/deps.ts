import { api, csrfToken } from '@/api/client'
import { asCard } from '@/components/assistant/cards/types'

import type { AudioIO } from './audio'
import type { CascadeDeps, FunnelEvent, TurnRequest, TurnStart } from './cascade-client'
import type { TurnDetector } from './turn/detector'
import type { RecordEvent, RecordTransport } from './conversation-record'
import type { LiveDeps, Notice, SessionResult, ToolContext, ToolResult } from './live-client'
import { readSse } from './sse'

/**
 * The real `LiveDeps`: the engine through the generated client, a browser
 * `WebSocket`, and the Web Audio module once it has loaded.
 *
 * Every engine call goes through `api` (`openapi-fetch` over `schema.d.ts`), so
 * the session and tool shapes are the server's own. The raw things are the
 * `WebSocket`, which is Google's, not the engine's, and `navigator.sendBeacon`
 * for the conversation record while the page closes (a beacon cannot go
 * through `fetch`, and cannot carry a header, so its CSRF token is a form field).
 */

const CONVERSATION_PATH = '/api/assistant/conversation'

const UNREACHABLE_TOOL = 'Nothing was read or written: the engine could not be reached.'

/** The audio module is a separate chunk (worklet included); it loads on first use. */
let webAudio: typeof import('./web-audio') | null = null

/** Start loading the audio code. Called when the chat opens, so the microphone tap has it ready. */
export function preloadAudio(): Promise<unknown> {
  return import('./web-audio').then((module) => {
    webAudio = module
  })
}

let audioFactory: (() => AudioIO) | null = null

/** Tests only: stand in for Web Audio, which jsdom does not have. Pass null to restore. */
export function setAudioFactory(factory: (() => AudioIO) | null) {
  audioFactory = factory
}

let csrfPrimed = false

async function primeCsrf() {
  if (csrfPrimed) return
  try {
    await api.GET('/api/auth/csrf')
    csrfPrimed = true
  } catch {
    // Unreachable: the session call below reports it in words.
  }
}

function notice(kind: Notice['kind'], text: string): SessionResult {
  return { ok: false, notice: { kind, text } }
}

async function startSession(utcOffsetMinutes: number): Promise<SessionResult> {
  await primeCsrf()
  try {
    const { data, error, response } = await api.POST('/api/assistant/session', {
      body: { utc_offset_minutes: utcOffsetMinutes, client_now_ms: Date.now() },
    })
    if (data) {
      return {
        ok: true,
        session: {
          token: data.token,
          model: data.model,
          wsUrl: data.ws_url,
          connectBy: Date.parse(data.connect_by),
          expiresAt: Date.parse(data.expires_at),
          companionName: data.companion_name,
          inputAudioMime: data.input_audio_mime,
          outputAudioRate: data.output_audio_rate,
          conversationId: data.conversation_id,
        },
      }
    }
    const detail = (error as { detail?: string } | undefined)?.detail
    switch (response.status) {
      case 503:
        return notice('no-key', `${detail ?? "The assistant isn't set up on this server."} Nothing was started.`)
      case 429:
        // The engine's words when it gave some: a refusal can be "too many just
        // now" or "today's demo budget is used up", and only the engine knows
        // which, so a fixed "try again in a little while" would be wrong for one.
        return notice('throttled', detail ?? 'Too many conversations were started. Try again in a little while.')
      case 401:
      case 403:
        return notice('signed-out', 'You are signed out, so nothing was started. Sign in again.')
      case 502:
        return notice('mint-failed', `${detail ?? 'The voice service refused the start.'} Nothing was started.`)
      default:
        return notice('mint-failed', `${detail ?? 'The engine could not start a conversation.'} Nothing was started.`)
    }
  } catch {
    return notice('engine-unreachable', "Can't reach the engine, so nothing was started.")
  }
}

function failure(message: string): ToolResult {
  return { success: false, text: message, forward: { success: false, message } }
}

async function runTool(
  name: string,
  args: Record<string, unknown>,
  context: ToolContext = {},
): Promise<ToolResult> {
  const body = {
    name,
    args,
    ...(context.conversationId ? { conversation_id: context.conversationId } : {}),
    call_id: context.callId ?? '',
    ...(context.turn !== undefined ? { turn: context.turn } : {}),
  }
  try {
    const { data, error, response } = await api.POST('/api/assistant/tool', { body })
    if (data) {
      const card = asCard(data.card)
      return { success: data.response.success, text: data.text, forward: data.response, ...(card ? { card } : {}) }
    }
    const refusal = error as { detail?: string; response?: { success: boolean; message: string } } | undefined
    if (refusal?.response) {
      return { success: false, text: refusal.detail ?? refusal.response.message, forward: refusal.response }
    }
    if (response.status === 429) {
      return failure(refusal?.detail ?? 'Too many tool calls just now. Nothing was read or written.')
    }
    if (response.status === 401 || response.status === 403) {
      return failure('Nothing was read or written: you are signed out of the engine.')
    }
    return failure(refusal?.detail ?? 'Nothing was read or written: the engine refused the call.')
  } catch {
    return failure(UNREACHABLE_TOOL)
  }
}

/** The upload's form: `conversation_id` and the events as JSON, plus the CSRF token for a beacon. */
export function recordForm(conversationId: string, events: RecordEvent[], withCsrf: boolean): FormData {
  const form = new FormData()
  form.append('conversation_id', conversationId)
  form.append('events', JSON.stringify(events))
  const token = withCsrf ? csrfToken() : null
  if (token) form.append('csrfmiddlewaretoken', token)
  return form
}

export const recordTransport: RecordTransport = {
  async upload(conversationId, events) {
    try {
      const { response } = await api.POST(CONVERSATION_PATH, {
        body: { conversation_id: conversationId, events: JSON.stringify(events) },
        bodySerializer: () => recordForm(conversationId, events, false),
      })
      return response.ok
    } catch {
      return false
    }
  },
  beacon(conversationId, events) {
    if (typeof navigator === 'undefined' || typeof navigator.sendBeacon !== 'function') return false
    try {
      return navigator.sendBeacon(CONVERSATION_PATH, recordForm(conversationId, events, true))
    } catch {
      return false
    }
  },
}

export function createLiveDeps(): LiveDeps {
  return {
    startSession,
    runTool,
    openSocket: (url) => new WebSocket(url),
    audio: (): AudioIO => {
      if (audioFactory) return audioFactory()
      if (!webAudio) throw new Error('The audio code has not loaded yet.')
      return webAudio.createWebAudio()
    },
    now: () => Date.now(),
    utcOffsetMinutes: () => -new Date().getTimezoneOffset(),
    record: recordTransport,
  }
}

/** The turn's form: one utterance (raw 16 kHz PCM16) or one typed text, and the conversation. */
export function turnForm(request: TurnRequest): FormData {
  const form = new FormData()
  if (request.audio) {
    // A copy on its own ArrayBuffer: a Blob takes no SharedArrayBuffer-backed view.
    const bytes = new Uint8Array(request.audio.byteLength)
    bytes.set(new Uint8Array(request.audio.buffer, request.audio.byteOffset, request.audio.byteLength))
    form.append('audio', new Blob([bytes], { type: 'application/octet-stream' }), 'utterance.pcm')
  }
  if (request.text) form.append('text', request.text)
  if (request.spoken && request.text) form.append('spoken', 'true')
  if (request.tour !== undefined) form.append('tour', String(request.tour))
  if (request.greet) {
    form.append('greet', 'true')
    if (request.greetingHistory) form.append('greeting_history', JSON.stringify(request.greetingHistory))
  }
  if (request.conversationId) form.append('conversation_id', request.conversationId)
  form.append('utc_offset_minutes', String(request.utcOffsetMinutes))
  form.append('client_now_ms', String(Date.now()))
  if (request.preface) form.append('preface', request.preface)
  if (request.turnId) form.append('turn_id', request.turnId)
  if (request.bargeIn) form.append('barge_in', '1')
  return form
}

async function startTurn(request: TurnRequest, signal: AbortSignal): Promise<TurnStart> {
  await primeCsrf()
  const { response, error } = await api.POST('/api/assistant/turn', {
    // The typed fields for the contract; the form (with the utterance as a file) is what is sent.
    body: {
      ...(request.text ? { text: request.text } : {}),
      greet: Boolean(request.greet),
      spoken: Boolean(request.spoken),
      ...(request.tour !== undefined ? { tour: request.tour } : {}),
      conversation_id: request.conversationId,
      utc_offset_minutes: request.utcOffsetMinutes,
      ...(request.preface ? { preface: request.preface } : {}),
    },
    bodySerializer: () => turnForm(request),
    parseAs: 'stream',
    signal,
  })
  if (response.ok && response.body) return { ok: true, events: readSse(response.body) }
  let detail: string | null = null
  let code: string | null = null
  let retryMs: number | null = null
  try {
    const parsed = (error ?? (await response.json())) as { detail?: unknown; code?: unknown; retry_ms?: unknown } | undefined
    detail = typeof parsed?.detail === 'string' ? parsed.detail : null
    code = typeof parsed?.code === 'string' ? parsed.code : null
    retryMs = typeof parsed?.retry_ms === 'number' ? parsed.retry_ms : null
  } catch {
    detail = null
  }
  return { ok: false, status: response.status, detail, code, retryMs }
}

/**
 * The engine's cancel door for an abandoned turn: fire and forget, `keepalive` so it survives the
 * page unloading, and a failure is never shown (the aborted fetch already stopped the stream).
 * Raw `fetch` because the door is newer than the generated schema; same credentials and CSRF.
 */
function cancelTurn(turnId: string) {
  try {
    const token = csrfToken()
    void fetch('/api/assistant/turn/cancel', {
      method: 'POST',
      credentials: 'same-origin',
      keepalive: true,
      headers: { 'Content-Type': 'application/json', Accept: 'application/json', ...(token ? { 'X-CSRFToken': token } : {}) },
      body: JSON.stringify({ turn_id: turnId }),
    }).catch(() => {})
  } catch {
    // Never in the way.
  }
}

/** The phone's turn models, after the first tap: two workers, the site's own model files. */
async function loadTurnModels(): Promise<TurnDetector | null> {
  try {
    const { loadTurnDetector } = await import('./turn/detector')
    return await loadTurnDetector({
      makeWorker: () => new Worker(new URL('./turn/turn.worker.ts', import.meta.url), { type: 'module' }),
      sileroUrl: '/turn/silero_vad_v6_2.onnx',
      smartUrl: '/turn/smart-turn-v3.2-cpu.onnx',
    })
  } catch {
    return null
  }
}

/**
 * Early transcripts (the phone's speculation, spec 33 adapted): OFF for the demo's first ship
 * (blind review 2026-11-17). Each 300 ms pause re-uploaded the whole turn so far (N pauses, N paid
 * calls, about N squared over two of audio), and the text replaced the final audio without checking
 * the audio had not grown, so a soft last word was lost. Off: `/transcribe` is never called and the
 * turn's audio goes to `/turn` once. The client's speculation code and its tests stay, behind this.
 */
export const EARLY_TRANSCRIPT = false

/**
 * Barge-in by voice: OFF for the demo's first ship (2026-11-17). Three rounds of pause-then-decide
 * passed the unit tests and failed the real headless-Chrome echo runs (the reply heard back cut the
 * answer and was posted as a turn; `turn/barge-in-gate.ts` has what the code says about why). Off,
 * the conversation is half-duplex from the start and a tap (the orb, the dialog's Interrupt) is
 * the way to interrupt. The code and its tests stay, behind this.
 */
export const VOICE_BARGE_IN = false

/** What has been said so far, transcribed early; null when it could not be had. */
async function transcribe(audio: Int16Array, signal: AbortSignal): Promise<string | null> {
  const form = new FormData()
  const bytes = new Uint8Array(audio.byteLength)
  bytes.set(new Uint8Array(audio.buffer, audio.byteOffset, audio.byteLength))
  form.append('audio', new Blob([bytes], { type: 'application/octet-stream' }), 'so-far.pcm')
  try {
    const { data } = await api.POST('/api/assistant/transcribe', {
      body: { audio: '' },
      bodySerializer: () => form,
      signal,
    })
    return data ? data.text : null
  } catch {
    return null
  }
}

/** One step of the demo for its funnel counts. Fire and forget; a count lost is no harm. */
export function postFunnel(event: FunnelEvent | 'page_load', ref: string | undefined) {
  try {
    void api.POST('/api/demo/funnel', { body: { event, ref: ref ?? '' } })
  } catch {
    // Counting never gets in the way.
  }
}

/** The robotic marker's tap: the turn number on the caller's own conversation record. */
async function robotic(conversationId: string, turn: number): Promise<boolean> {
  try {
    const { response } = await api.POST('/api/assistant/robotic', { body: { conversation_id: conversationId, turn } })
    return response.status === 204
  } catch {
    return false
  }
}

export function createCascadeDeps(options: { funnelRef?: string } = {}): CascadeDeps {
  return {
    startTurn,
    cancelTurn,
    robotic,
    turnDetector: loadTurnModels,
    ...(EARLY_TRANSCRIPT ? { transcribe } : {}),
    bargeIn: VOICE_BARGE_IN,
    ...(options.funnelRef !== undefined ? { funnel: (event) => postFunnel(event, options.funnelRef) } : {}),
    audio: (): AudioIO => {
      if (audioFactory) return audioFactory()
      if (!webAudio) throw new Error('The audio code has not loaded yet.')
      return webAudio.createWebAudio()
    },
    now: () => Date.now(),
    utcOffsetMinutes: () => -new Date().getTimezoneOffset(),
    record: recordTransport,
    greetingStore: {
      load: () => window.localStorage.getItem(GREETING_KEY),
      save: (value) => window.localStorage.setItem(GREETING_KEY, value),
    },
  }
}

/** The greeting's caps (spec 27): lead kinds today and the last hellos' words, never facts. */
const GREETING_KEY = 'legion.assistant.greeting.v1'
