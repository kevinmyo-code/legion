import { deleteBookmark, listBookmarks, listPositions, putBookmark, putPosition } from '@/api/books'

import { kv } from './kv'
import { ReadingSync } from './reading'

let instance: ReadingSync | undefined

/** The page's one `ReadingSync`, wired to the real API and the real IndexedDB. */
export function readingSync(): ReadingSync {
  instance ??= new ReadingSync(
    kv(),
    { listPositions, listBookmarks },
    { putPosition, putBookmark, deleteBookmark },
  )
  return instance
}

/** For tests that swap the store. */
export function resetReadingSync() {
  instance = undefined
}
