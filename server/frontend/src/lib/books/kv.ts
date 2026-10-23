/**
 * A tiny key-value seam over IndexedDB, so everything that persists in the Books
 * page (the folder handle, the hash cache, converted EPUBs, the sync queue, local
 * positions and bookmarks) is testable against an in-memory copy and runs against
 * the real database in the browser.
 */

export type StoreName = 'handles' | 'hashes' | 'converted' | 'queue' | 'positions' | 'bookmarks' | 'meta'

export const STORE_NAMES: readonly StoreName[] = [
  'handles',
  'hashes',
  'converted',
  'queue',
  'positions',
  'bookmarks',
  'meta',
]

export interface KV {
  get<T>(store: StoreName, key: string): Promise<T | undefined>
  set(store: StoreName, key: string, value: unknown): Promise<void>
  delete(store: StoreName, key: string): Promise<void>
  /** Every key and value in a store, in key order. */
  entries<T>(store: StoreName): Promise<[string, T][]>
}

export class MemoryKV implements KV {
  private readonly data = new Map<string, Map<string, unknown>>()
  private store(name: StoreName) {
    let s = this.data.get(name)
    if (!s) this.data.set(name, (s = new Map()))
    return s
  }
  async get<T>(store: StoreName, key: string) {
    return this.store(store).get(key) as T | undefined
  }
  async set(store: StoreName, key: string, value: unknown) {
    this.store(store).set(key, value)
  }
  async delete(store: StoreName, key: string) {
    this.store(store).delete(key)
  }
  async entries<T>(store: StoreName) {
    return [...this.store(store).entries()].sort(([a], [b]) => (a < b ? -1 : 1)) as [string, T][]
  }
}

const DB_NAME = 'legion-books'
const DB_VERSION = 1

function request<T>(req: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  })
}

/** The real thing. Opened lazily; every method is one short transaction. */
export class IndexedDbKV implements KV {
  private db: Promise<IDBDatabase> | undefined

  private open(): Promise<IDBDatabase> {
    this.db ??= new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, DB_VERSION)
      req.onupgradeneeded = () => {
        for (const name of STORE_NAMES) {
          if (!req.result.objectStoreNames.contains(name)) req.result.createObjectStore(name)
        }
      }
      req.onsuccess = () => resolve(req.result)
      req.onerror = () => reject(req.error)
    })
    return this.db
  }

  private async tx(store: StoreName, mode: IDBTransactionMode) {
    return (await this.open()).transaction(store, mode).objectStore(store)
  }

  async get<T>(store: StoreName, key: string) {
    return (await request((await this.tx(store, 'readonly')).get(key))) as T | undefined
  }
  async set(store: StoreName, key: string, value: unknown) {
    await request((await this.tx(store, 'readwrite')).put(value, key))
  }
  async delete(store: StoreName, key: string) {
    await request((await this.tx(store, 'readwrite')).delete(key))
  }
  async entries<T>(store: StoreName) {
    const s = await this.tx(store, 'readonly')
    const [keys, values] = await Promise.all([request(s.getAllKeys()), request(s.getAll())])
    return keys.map((k, i) => [String(k), values[i]] as [string, T])
  }
}

let shared: KV | undefined

/** The page's one store. Tests replace it with `setKv(new MemoryKV())`. */
export function kv(): KV {
  shared ??= new IndexedDbKV()
  return shared
}

export function setKv(next: KV | undefined) {
  shared = next
}
