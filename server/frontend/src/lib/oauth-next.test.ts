import { describe, expect, it } from 'vitest'

import { oauthNext } from './oauth-next'

describe('oauthNext', () => {
  it('returns the consent page the engine asked for', () => {
    const next = '/oauth/authorize/?client_id=abc&state=s'
    expect(oauthNext(`?next=${encodeURIComponent(next)}`)).toBe(next)
  })

  it('ignores a missing next', () => {
    expect(oauthNext('')).toBeNull()
  })

  it.each([
    'https://evil.example/oauth/authorize/',
    '//evil.example/oauth/authorize/',
    '/settings',
    '/oauth/token/',
    '/oauth/authorize/\\evil',
  ])('never follows %s', (next) => {
    expect(oauthNext(`?next=${encodeURIComponent(next)}`)).toBeNull()
  })
})
