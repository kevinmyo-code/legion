import { describe, expect, test } from 'vitest'

import { HashCache, cacheKey, sha256Hex, type HashableFile } from './hash'
import { MemoryKV } from './kv'

function fileOf(text: string, over: Partial<Pick<HashableFile, 'name' | 'size' | 'lastModified'>> = {}): HashableFile {
  const bytes = new TextEncoder().encode(text)
  return {
    name: 'a.epub',
    size: bytes.length,
    lastModified: 1000,
    arrayBuffer: async () => bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer,
    ...over,
  }
}

const subtle = globalThis.crypto.subtle

describe('the hash a book is matched by', () => {
  test('is the SHA-256 of the file bytes, as 64 lowercase hex characters', async () => {
    const hex = await sha256Hex(new TextEncoder().encode('legion'), subtle)
    expect(hex).toBe('1614ceeec50a9336ebf690886caa747d6811c45d37086a3fa7b11c9e83926c6c')
    expect(hex).toMatch(/^[0-9a-f]{64}$/)
  })

  test('is read once: a second ask for the same name, size and date comes from the cache', async () => {
    const cache = new HashCache(new MemoryKV(), subtle)
    const first = await cache.hash(fileOf('chapter one'))
    const second = await cache.hash(fileOf('chapter one'))
    expect(first.fromCache).toBe(false)
    expect(second).toEqual({ sha256: first.sha256, fromCache: true })
    expect(cache.digested).toBe(1)
  })

  test('is read again when the file was re-saved: a new modified time, or a new size', async () => {
    const cache = new HashCache(new MemoryKV(), subtle)
    await cache.hash(fileOf('chapter one'))
    const resaved = await cache.hash(fileOf('chapter one', { lastModified: 2000 }))
    const grown = await cache.hash(fileOf('chapter one and more'))
    expect(resaved.fromCache).toBe(false)
    expect(grown.fromCache).toBe(false)
    expect(cache.digested).toBe(3)
  })

  test('survives a new cache over the same store (a new visit to the page)', async () => {
    const store = new MemoryKV()
    const a = new HashCache(store, subtle)
    const { sha256 } = await a.hash(fileOf('chapter one'))
    const b = new HashCache(store, subtle)
    expect(await b.hash(fileOf('chapter one'))).toEqual({ sha256, fromCache: true })
    expect(b.digested).toBe(0)
  })

  test('keys on the triple, so a re-saved file is never mistaken for the old one', () => {
    expect(cacheKey({ name: 'a.epub', size: 1, lastModified: 2 })).not.toBe(
      cacheKey({ name: 'a.epub', size: 1, lastModified: 3 }),
    )
  })
})
