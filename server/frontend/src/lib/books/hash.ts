import type { KV } from './kv'

/**
 * A book is the same book on two devices when the SHA-256 of its bytes matches
 * (ticket 07 ruling 3). Hashing a 40 MB PDF costs real time, so the digest is
 * cached by name + size + lastModified: the same triple the phone's cache uses
 * (ticket 09), and a file that was re-saved changes at least one of the three.
 */

export interface HashableFile {
  name: string
  size: number
  lastModified: number
  arrayBuffer(): Promise<ArrayBuffer>
}

interface CachedHash {
  sha256: string
}

export function cacheKey(file: Pick<HashableFile, 'name' | 'size' | 'lastModified'>): string {
  return `${file.name}|${file.size}|${file.lastModified}`
}

export async function sha256Hex(
  bytes: ArrayBuffer | Uint8Array,
  subtle: Pick<SubtleCrypto, 'digest'> = crypto.subtle,
): Promise<string> {
  const input = bytes instanceof Uint8Array ? (bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer) : bytes
  const digest = new Uint8Array(await subtle.digest('SHA-256', input))
  return Array.from(digest, (b) => b.toString(16).padStart(2, '0')).join('')
}

export class HashCache {
  /** Files actually read and digested since this cache was made (tests assert on it). */
  digested = 0

  private readonly store: KV
  private readonly subtle: Pick<SubtleCrypto, 'digest'>
  constructor(store: KV, subtle: Pick<SubtleCrypto, 'digest'> = crypto.subtle) {
    this.store = store
    this.subtle = subtle
  }

  async hash(file: HashableFile): Promise<{ sha256: string; fromCache: boolean }> {
    const key = cacheKey(file)
    const hit = await this.store.get<CachedHash>('hashes', key)
    if (hit) return { sha256: hit.sha256, fromCache: true }
    const sha256 = await sha256Hex(await file.arrayBuffer(), this.subtle)
    this.digested += 1
    await this.store.set('hashes', key, { sha256 } satisfies CachedHash)
    return { sha256, fromCache: false }
  }
}
