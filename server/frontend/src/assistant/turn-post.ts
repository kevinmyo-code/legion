import type { TurnStart } from './cascade-client'

/**
 * The engine's `busy` refusal (`code: "busy"`): this caller's own earlier turn has not released yet,
 * which is usual just after an interrupt (the engine notices the hang-up a moment later). The same
 * turn is posted again after `retry_ms`, at most `BUSY_TRIES` posts and `BUSY_BUDGET_MS` of waiting.
 */
export const BUSY_RETRY_MS = 400
export const BUSY_TRIES = 5
export const BUSY_BUDGET_MS = 6_000
/** `demo_full` (the demo's turn cap, per address or demo-wide): a slower, shorter wait. */
export const FULL_RETRY_MS = 1_500
export const FULL_TRIES = 3

/** Codes worth posting the same turn again for: how far apart, how many posts, how long in all. */
const RETRY: Record<string, { ms: (given: number | null | undefined) => number; tries: number; budgetMs: number }> = {
  busy: { ms: (given) => given ?? BUSY_RETRY_MS, tries: BUSY_TRIES, budgetMs: BUSY_BUDGET_MS },
  demo_full: { ms: () => FULL_RETRY_MS, tries: FULL_TRIES, budgetMs: FULL_RETRY_MS * (FULL_TRIES - 1) },
}

const wait = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms))

/** Post until it is not retried, the tries run out, or `wanted()` says nobody is waiting any more. */
export async function postTurn(post: () => Promise<TurnStart>, wanted: () => boolean): Promise<TurnStart> {
  let waited = 0
  for (let tries = 1; ; tries++) {
    const start = await post()
    const rule = start.ok || !start.code ? undefined : RETRY[start.code]
    if (start.ok || !rule || !wanted()) return start
    const ms = rule.ms(start.retryMs)
    if (tries >= rule.tries || waited + ms > rule.budgetMs) return start
    waited += ms
    await wait(ms)
    if (!wanted()) return start
  }
}

/** A turn's id for the engine's cancel door; `randomUUID` exists only on secure origins. */
export function newTurnId(): string {
  const c = globalThis.crypto
  if (typeof c.randomUUID === 'function') return c.randomUUID()
  const b = c.getRandomValues(new Uint8Array(16))
  b[6] = (b[6] & 0x0f) | 0x40
  b[8] = (b[8] & 0x3f) | 0x80
  const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('')
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`
}
