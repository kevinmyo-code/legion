import { describe, expect, test } from 'vitest'

import { refusalMessage } from '@/api/refusal'

/**
 * What a refused write says (CLAUDE.md section 7): it always names what did NOT
 * happen, and never says so twice when the engine already did.
 */
describe('refusalMessage', () => {
  test('puts what did not happen in front of a sentence that does not say it', () => {
    expect(refusalMessage('saved', 400, { detail: 'The title is blank.' })).toBe(
      'Nothing was saved. The title is blank.',
    )
  })

  test.each([
    ['Nothing was changed: this route is owner-only.'],
    ['Nobody was removed. They are the last owner of this household.'],
    ['No account was created. An account with that email already exists.'],
    ['Your current password is not right. Nothing was changed.'],
  ])('leaves %s alone, because it already says it', (said) => {
    expect(refusalMessage('changed', 400, { detail: said })).toBe(said)
  })

  test('each verb the settings screens use reads as a sentence', () => {
    for (const verb of ['changed', 'removed', 'revoked', 'created'] as const) {
      expect(refusalMessage(verb, 500, undefined)).toBe(
        `Nothing was ${verb}. The engine answered 500 without saying why.`,
      )
    }
  })

  test('an unaccepted session says to sign in again', () => {
    expect(refusalMessage('saved', 403, undefined)).toBe(
      'Nothing was saved. The engine did not accept this session. Sign in again.',
    )
  })
})
