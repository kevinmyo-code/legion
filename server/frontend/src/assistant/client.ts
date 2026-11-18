import type { LiveClient } from './live-client'

/**
 * What every assistant screen may call, whichever engine the conversation runs on (ADR 0102):
 * `LiveClient` (Gemini Live with a locked token) or `CascadeClient` (the engine's own voice
 * pipeline). The engine decides which, per role (`GET /api/assistant/session`). `CascadeClient`
 * is checked against it where the provider builds one.
 */
export type AssistantClient = Pick<
  LiveClient,
  'subscribe' | 'getState' | 'send' | 'startVoice' | 'stopVoice' | 'interrupt' | 'reset' | 'levels' | 'attach' | 'dispose'
>
