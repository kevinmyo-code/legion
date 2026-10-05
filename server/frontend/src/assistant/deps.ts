import { api } from '@/api/client'

import type { AudioIO } from './audio'
import type { LiveDeps, Notice, SessionResult, ToolResult } from './live-client'

/**
 * The real `LiveDeps`: the engine through the generated client, a browser
 * `WebSocket`, and the Web Audio module once it has loaded.
 *
 * Every engine call goes through `api` (`openapi-fetch` over `schema.d.ts`), so
 * the session and tool shapes are the server's own. The one raw thing is the
 * `WebSocket`, which is Google's, not the engine's.
 */

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
      body: { utc_offset_minutes: utcOffsetMinutes },
    })
    if (data) {
      return {
        ok: true,
        session: {
          token: data.token,
          model: data.model,
          wsUrl: data.ws_url,
          connectBy: Date.parse(data.connect_by),
          companionName: data.companion_name,
          inputAudioMime: data.input_audio_mime,
          outputAudioRate: data.output_audio_rate,
        },
      }
    }
    const detail = (error as { detail?: string } | undefined)?.detail
    switch (response.status) {
      case 503:
        return notice('no-key', `${detail ?? "The assistant isn't set up on this server."} Nothing was started.`)
      case 429:
        return notice('throttled', `${detail ?? 'Too many conversations were started.'} Try again in a little while.`)
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

async function runTool(name: string, args: Record<string, unknown>): Promise<ToolResult> {
  try {
    const { data, error, response } = await api.POST('/api/assistant/tool', { body: { name, args } })
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
  }
}
