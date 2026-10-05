import type { Reply } from './engine'

/**
 * The fake engine's answers for the web assistant (web-assistant 08): the
 * companion read, the token mint and the tool door. It follows
 * `server/assistant/views.py` including its sentences, because the chat shows
 * those verbatim and a fake with its own wording would let a test pass against
 * words the engine never says.
 *
 * It never talks to Google. The token it mints is a fixed string and the
 * `ws_url` is a fake address; the tests (and `e2e/assistant.spec.ts`) replace
 * `WebSocket` with a scripted one.
 */

export interface ToolFixture {
  status?: 200 | 400 | 429
  /** On 200: the engine's `response.success`. */
  success?: boolean
  /** The words: `text` on a 200, `detail` on a refusal. */
  text: string
}

export interface AssistantState {
  companionName: string
  /** A forced reply for the mint ("no key", throttled). */
  mint: Reply | null
  /** Tool answers by tool name. A name that is absent is refused as the real engine refuses an unknown one. */
  tools: Record<string, ToolFixture>
  /** How many tokens were minted, and the bodies the mint was sent. */
  minted: number
  mintBodies: unknown[]
  /** Every tool call the engine was asked to run, in order. */
  toolCalls: { name: string; args: unknown }[]
}

export function defaultAssistant(): AssistantState {
  return { companionName: 'Dorothy', mint: null, tools: {}, minted: 0, mintBodies: [], toolCalls: [] }
}

export const FAKE_WS_URL = 'wss://live.example.test/ws/BidiGenerateContentConstrained'
export const FAKE_TOKEN = 'auth_tokens/fake-token-for-tests'

export function handleAssistant(
  host: { assistant: AssistantState },
  method: string,
  pathname: string,
  body: unknown,
): Reply | undefined {
  const a = host.assistant

  if (method === 'GET' && pathname === '/api/auth/csrf') return { status: 204 }

  if (method === 'GET' && pathname === '/api/assistant/companion') {
    return {
      status: 200,
      body: { name: a.companionName, persona: 'dorothy', voice_name: 'Kore', custom_register: false, stored: true },
    }
  }

  if (method === 'POST' && pathname === '/api/assistant/session') {
    a.mintBodies.push(body)
    if (a.mint) return a.mint
    a.minted += 1
    const now = Date.now()
    return {
      status: 200,
      body: {
        token: FAKE_TOKEN,
        model: 'gemini-3.8-live',
        ws_url: FAKE_WS_URL,
        expires_at: new Date(now + 30 * 60_000).toISOString(),
        connect_by: new Date(now + 60_000).toISOString(),
        companion_name: a.companionName,
        voice_name: 'Kore',
        input_audio_mime: 'audio/pcm;rate=16000',
        output_audio_rate: 24000,
      },
    }
  }

  if (method === 'POST' && pathname === '/api/assistant/tool') {
    const { name, args } = body as { name: string; args?: unknown }
    a.toolCalls.push({ name, args })
    const fixture = a.tools[name]
    if (!fixture) {
      const detail = `Nothing was read or written. ${name} is not a tool the web assistant has.`
      return { status: 400, body: { detail, response: { success: false, message: detail } } }
    }
    const status = fixture.status ?? 200
    if (status === 429) return { status, body: { detail: fixture.text } }
    if (status === 400) {
      return { status, body: { detail: fixture.text, response: { success: false, message: fixture.text } } }
    }
    const success = fixture.success ?? true
    return {
      status: 200,
      body: {
        name,
        is_error: !success,
        outcome: success ? 'ok' : 'failed',
        text: fixture.text,
        response: { success, message: fixture.text },
      },
    }
  }

  return undefined
}
