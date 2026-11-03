import { api, csrfToken } from '@/api/client'

import type { AudioIO } from './audio'
import type { RecordEvent, RecordTransport } from './conversation-record'
import type { LiveDeps, Notice, SessionResult, ToolContext, ToolResult } from './live-client'

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
  }
  try {
    const { data, error, response } = await api.POST('/api/assistant/tool', { body })
    if (data) {
      return { success: data.response.success, text: data.text, forward: data.response }
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
