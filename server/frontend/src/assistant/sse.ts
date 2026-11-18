/**
 * Server-Sent Events from a `fetch` body (the cascade turn, ADR 0102). `EventSource` cannot POST,
 * so the turn's stream is read here: `event:` and `data:` lines, a blank line ends an event.
 * Pure, tested in Node.
 */

export interface SseEvent {
  type: string
  data: Record<string, unknown>
}

export class SseParser {
  private buffer = ''

  /** Text as it arrives; whole events out. A malformed `data:` line drops its event only. */
  feed(text: string): SseEvent[] {
    this.buffer += text.replace(/\r\n/g, '\n')
    const out: SseEvent[] = []
    let end = this.buffer.indexOf('\n\n')
    while (end >= 0) {
      const block = this.buffer.slice(0, end)
      this.buffer = this.buffer.slice(end + 2)
      const event = parseBlock(block)
      if (event) out.push(event)
      end = this.buffer.indexOf('\n\n')
    }
    return out
  }
}

function parseBlock(block: string): SseEvent | null {
  let type = 'message'
  const data: string[] = []
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) type = line.slice(6).trim()
    else if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
  }
  if (data.length === 0) return null
  try {
    const parsed: unknown = JSON.parse(data.join('\n'))
    if (parsed && typeof parsed === 'object') return { type, data: parsed as Record<string, unknown> }
  } catch {
    // Dropped: one bad event must not end the turn.
  }
  return null
}

/** Every event of a streamed body, in order. */
export async function* readSse(body: ReadableStream<Uint8Array>): AsyncGenerator<SseEvent> {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  const parser = new SseParser()
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      for (const event of parser.feed(decoder.decode(value, { stream: true }))) yield event
    }
    for (const event of parser.feed(decoder.decode() + '\n\n')) yield event
  } finally {
    reader.releaseLock()
  }
}
