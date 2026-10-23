import { describe, expect, test } from 'vitest'

import { SENTENCES, appliedSentence, locatorHref, normalizeHref, resolveEpub, resolvePaged } from './locator'

const ORDER = ['OEBPS/ch1.xhtml', 'OEBPS/ch2.xhtml', 'OEBPS/ch3.xhtml']
const loc = (href: string) => ({
  href,
  type: 'application/xhtml+xml',
  locations: { progression: 0.2, totalProgression: 0.4 },
})

describe('applying a position from the other device', () => {
  test('an EPUB locator whose resource is in this copy is applied exactly', () => {
    const r = resolveEpub({ locator: loc('OEBPS/ch2.xhtml'), total_progression: 0.4 }, ORDER)
    expect(r).toEqual({ kind: 'exact', locator: loc('OEBPS/ch2.xhtml') })
  })

  test('a fragment or a leading slash does not make a known resource unknown', () => {
    expect(resolveEpub({ locator: loc('/OEBPS/ch2.xhtml#p4'), total_progression: 0.4 }, ORDER).kind).toBe('exact')
    expect(normalizeHref('/OEBPS/ch2.xhtml#p4')).toBe('OEBPS/ch2.xhtml')
  })

  test('a locator naming a resource this copy lacks falls back to totalProgression', () => {
    const r = resolveEpub({ locator: loc('text/part0007.xhtml'), total_progression: 0.43 }, ORDER)
    expect(r).toEqual({ kind: 'near', totalProgression: 0.43 })
  })

  test('a missing or malformed locator also falls back, and the fraction is held to 0 to 1', () => {
    expect(resolveEpub({ locator: null, total_progression: 1.7 }, ORDER)).toEqual({ kind: 'near', totalProgression: 1 })
    expect(resolveEpub({ locator: { page: 3 }, total_progression: -2 }, ORDER)).toEqual({
      kind: 'near',
      totalProgression: 0,
    })
    expect(locatorHref({ page: 3 })).toBeUndefined()
  })

  test('a page index inside the file is exact; one outside it falls back to the fraction', () => {
    expect(resolvePaged({ locator: { page: 4 }, total_progression: 0.5 }, 10)).toEqual({ kind: 'exact', page: 4 })
    expect(resolvePaged({ locator: { page: 40 }, total_progression: 0.5 }, 10)).toEqual({ kind: 'near', page: 5 })
    expect(resolvePaged({ locator: null, total_progression: 1 }, 10)).toEqual({ kind: 'near', page: 9 })
  })

  test('the near sentence never claims the exact spot, and names the kind of device', () => {
    expect(appliedSentence('exact')).toBe('Picked up where you left off on your phone.')
    expect(appliedSentence('near')).toBe('Opened near where you were on your phone.')
    expect(appliedSentence('near', true)).toContain('different number of pages')
    expect(SENTENCES.near('web')).toBe('Opened near where you were on the web.')
    expect(appliedSentence('near')).not.toMatch(/picked up/i)
  })
})
