/**
 * What a refused or failed write says.
 *
 * CLAUDE.md section 7: a failure result states in words what did NOT happen,
 * and nothing claims success unless the underlying action ran. A write on these
 * screens is not optimistic, so the only two things the person can be told are
 * "it was saved" (the engine said 2xx) or a sentence from here that begins with
 * what did not happen.
 *
 * The engine's own 400 bodies are "meant to be shown to a person" (the OpenAPI
 * description of every synced write says so), in two shapes: `{detail: "..."}`
 * and `{field: ["..."]}`. Both are shown verbatim, after the sentence that says
 * nothing was written - unless the server already opened with one.
 */

export class WriteRefused extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'WriteRefused'
  }
}

/** Pull the person-facing text out of whichever body shape the engine used. */
export function sentenceFromBody(body: unknown): string | null {
  if (typeof body === 'string') return body.trim() === '' ? null : body
  if (body === null || typeof body !== 'object') return null
  const record = body as Record<string, unknown>
  if (typeof record.detail === 'string') return record.detail
  const parts: string[] = []
  for (const [field, value] of Object.entries(record)) {
    if (Array.isArray(value) && value.every((item) => typeof item === 'string')) {
      parts.push(`${field}: ${value.join(' ')}`)
    } else if (typeof value === 'string') {
      parts.push(`${field}: ${value}`)
    }
  }
  return parts.length > 0 ? parts.join(' ') : null
}

export type WriteVerb = 'saved' | 'deleted' | 'changed' | 'removed' | 'revoked' | 'created' | 'logged' | 'started' | 'connected' | 'synced'

/** The engine's refusals say what did not happen somewhere in the sentence
 * ("Nothing was ...", "Nobody was removed", "No account was created", "Your
 * current password is not right. Nothing was changed."): saying it again in front
 * would be a stutter. */
const OPENS_WITH_THE_OUTCOME = /\b(nothing|nobody|no \w+) was\b/i

export function refusalMessage(verb: WriteVerb, status: number, body: unknown): string {
  const said = sentenceFromBody(body)
  if (said !== null) {
    // The engine's own refusals usually say what did not happen. Do not say it twice.
    return OPENS_WITH_THE_OUTCOME.test(said) ? said : `Nothing was ${verb}. ${said}`
  }
  if (status === 401 || status === 403) {
    return `Nothing was ${verb}. The engine did not accept this session. Sign in again.`
  }
  return `Nothing was ${verb}. The engine answered ${status} without saying why.`
}

interface WriteResult {
  error?: unknown
  response: Response
}

interface WriteResultWith<T> extends WriteResult {
  data?: T
}

/** Run one write call; resolve only if the engine accepted it. */
export async function runWrite(verb: WriteVerb, call: () => Promise<WriteResult>): Promise<void> {
  await runWriteData(verb, call)
}

/** Like `runWrite`, for a write whose answer the screen shows (a new invite, the
 * sentence a password change answers with). Resolves with the body on a 2xx. */
export async function runWriteData<T>(
  verb: WriteVerb,
  call: () => Promise<WriteResultWith<T>>,
): Promise<T | undefined> {
  let result: WriteResultWith<T>
  try {
    result = await call()
  } catch {
    throw new WriteRefused(`Could not reach the engine. Nothing was ${verb}.`)
  }
  if (!result.response.ok) {
    throw new WriteRefused(refusalMessage(verb, result.response.status, result.error))
  }
  return result.data
}
