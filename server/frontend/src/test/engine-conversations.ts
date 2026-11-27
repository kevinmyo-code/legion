import type { ConversationBatch, ConversationDevice } from '../api/conversations'
import type { Reply } from './engine'

/**
 * The fake engine's `GET /api/device-logs/conversations/` (#160), following the
 * real route (`server/devicelogs/conversations.py`): devices ordered by their
 * newest batch, `limit` applied PER DEVICE, `total` counting every batch the
 * engine holds for the device. The tests seed devices with their batches
 * already newest first, as the engine sends them.
 */
export interface ConversationsState {
  devices: ConversationDevice[]
  /** The route alone answers this status (503: down, 403: refused); the rest of the app works. */
  failing: number | false
  /** The `detail` the failing answer carries. */
  failingDetail?: string
}

export function defaultConversations(): ConversationsState {
  return { devices: [], failing: false }
}

/** A readable batch, with made-up lines in the shape the engine sends (his lines, tool names and
 * times: never an assistant line or a tool's reply), for a test to seed. */
export function makeBatch(id: string, receivedAt: string, lines: string[]): ConversationBatch {
  return {
    id,
    received_at: receivedAt,
    header: `=== batch ${id} | received ${receivedAt} | conversation`,
    lines,
    run_id: '',
    readable: true,
    problem: null,
  }
}

export function handleConversations(
  host: { conversations: ConversationsState },
  method: string,
  pathname: string,
  search: URLSearchParams,
): Reply | undefined {
  if (method !== 'GET' || pathname !== '/api/device-logs/conversations/') return undefined
  if (host.conversations.failing) {
    return {
      status: host.conversations.failing,
      body: { detail: host.conversations.failingDetail ?? 'The engine cannot answer right now.' },
    }
  }
  const limit = Math.max(1, Math.min(Number(search.get('limit') ?? 10), 30))
  return {
    status: 200,
    body: {
      devices: host.conversations.devices.map((device) => ({
        ...device,
        batches: device.batches.slice(0, limit),
      })),
    },
  }
}
