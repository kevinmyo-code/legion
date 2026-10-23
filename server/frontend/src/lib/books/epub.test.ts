import { describe, expect, test } from 'vitest'

import { makeCbz, makeEpub } from '@/test/books-fixtures'

import { EpubError, buildPositions, openEpub, resolveZipPath } from './epub'
import { comicPageNames } from './scan'

describe('opening an EPUB in the browser', () => {
  test('reads title, reading order, contents and zip-root-relative hrefs', () => {
    const opened = openEpub(makeEpub({ title: 'The Harbour Clock', chapters: 3 }))
    expect(opened.title).toBe('The Harbour Clock')
    expect(opened.publication.readingOrder.items.map((l) => l.href)).toEqual([
      'OEBPS/ch1.xhtml',
      'OEBPS/ch2.xhtml',
      'OEBPS/ch3.xhtml',
    ])
    expect(opened.toc.map((t) => [t.title, t.href])).toEqual([
      ['Chapter 1', 'OEBPS/ch1.xhtml'],
      ['Chapter 2', 'OEBPS/ch2.xhtml'],
      ['Chapter 3', 'OEBPS/ch3.xhtml'],
    ])
    expect(opened.chapterByHref.get('OEBPS/ch2.xhtml')).toBe('Chapter 2')
  })

  test('follows the package document wherever it lives', () => {
    const opened = openEpub(makeEpub({ root: 'EPUB/text' }))
    expect(opened.publication.readingOrder.items[0].href).toBe('EPUB/text/ch1.xhtml')
  })

  test('says in words why a file is not an EPUB', () => {
    expect(() => openEpub(new Uint8Array([1, 2, 3]))).toThrow(EpubError)
    expect(() => openEpub(new Uint8Array([1, 2, 3]))).toThrow(/not a readable EPUB/)
  })

  test('positions count up, spread totalProgression by size, and start at zero', () => {
    const positions = buildPositions([
      { href: 'a', type: 't', size: 2048 },
      { href: 'b', type: 't', size: 1024 },
    ])
    expect(positions.map((p) => p.locations.position)).toEqual([1, 2, 3])
    expect(positions.map((p) => p.href)).toEqual(['a', 'a', 'b'])
    expect(positions[0].locations.totalProgression).toBe(0)
    expect(positions[2].locations.totalProgression).toBeCloseTo(2 / 3)
  })

  test('resolves relative paths inside the zip', () => {
    expect(resolveZipPath('OEBPS/content.opf', 'text/ch%201.xhtml#a')).toBe('OEBPS/text/ch 1.xhtml')
    expect(resolveZipPath('OEBPS/text/ch1.xhtml', '../images/c.png')).toBe('OEBPS/images/c.png')
  })
})

describe('a comic archive', () => {
  test('lists page images in natural order, not string order', () => {
    expect(comicPageNames(['p10.png', 'p2.png', 'p1.png', 'notes.txt', '__MACOSX/p3.png'])).toEqual([
      'p1.png',
      'p2.png',
      'p10.png',
    ])
    expect(makeCbz().length).toBeGreaterThan(0)
  })
})
