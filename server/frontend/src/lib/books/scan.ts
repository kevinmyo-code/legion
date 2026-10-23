/**
 * The folder on the laptop (ticket 07 ruling 2, ticket 10 "Folder").
 *
 * The File System Access API's types are not in TypeScript's DOM lib, so the
 * slice used here is declared by hand. Only what is read is declared, which is
 * also what a test double has to provide.
 */

export type Format = 'epub' | 'pdf' | 'cbz' | 'cbr'

export interface FileHandleLike {
  kind: 'file'
  name: string
  getFile(): Promise<File>
}

export interface DirHandleLike {
  kind: 'directory'
  name: string
  values(): AsyncIterable<FileHandleLike | DirHandleLike>
  queryPermission?(descriptor: { mode: 'read' }): Promise<'granted' | 'denied' | 'prompt'>
  requestPermission?(descriptor: { mode: 'read' }): Promise<'granted' | 'denied' | 'prompt'>
}

export interface BookFile {
  /** Path under the picked folder, `/`-separated; the list's stable key. */
  path: string
  name: string
  format: Format
  handle: FileHandleLike
}

export const NO_FOLDER_ACCESS =
  'This browser cannot open a folder. Use Chrome or Edge to read a whole folder, or pick files one at a time.'

export const CBR_SENTENCE = 'CBR is not supported. Convert it to CBZ to read it here.'

export function formatOf(name: string): Format | undefined {
  const ext = name.slice(name.lastIndexOf('.') + 1).toLowerCase()
  return ext === 'epub' || ext === 'pdf' || ext === 'cbz' || ext === 'cbr' ? ext : undefined
}

export function titleOf(name: string): string {
  return name.replace(/\.[^.]+$/, '').replace(/[_]+/g, ' ')
}

/** Every book file under `dir`, sorted by name the way a person reads a shelf. */
export async function scanFolder(dir: DirHandleLike, prefix = '', depth = 0): Promise<BookFile[]> {
  const found: BookFile[] = []
  for await (const entry of dir.values()) {
    if (entry.kind === 'directory') {
      // A shelf of series folders is normal; a symlink loop or a drive root is not.
      if (depth < 4) found.push(...(await scanFolder(entry, `${prefix}${entry.name}/`, depth + 1)))
      continue
    }
    const format = formatOf(entry.name)
    if (format) found.push({ path: `${prefix}${entry.name}`, name: entry.name, format, handle: entry })
  }
  return found.sort((a, b) => a.path.localeCompare(b.path, undefined, { numeric: true, sensitivity: 'base' }))
}

type PickerWindow = {
  showDirectoryPicker?: (options?: { mode?: 'read' }) => Promise<DirHandleLike>
  showOpenFilePicker?: (options?: {
    multiple?: boolean
    types?: { description: string; accept: Record<string, string[]> }[]
  }) => Promise<FileHandleLike[]>
}

export function canPickFolder(w: PickerWindow = window as unknown as PickerWindow): boolean {
  return typeof w.showDirectoryPicker === 'function'
}

export function canPickFiles(w: PickerWindow = window as unknown as PickerWindow): boolean {
  return typeof w.showOpenFilePicker === 'function'
}

export function pickFolder(w: PickerWindow = window as unknown as PickerWindow): Promise<DirHandleLike> {
  if (!w.showDirectoryPicker) throw new Error(NO_FOLDER_ACCESS)
  return w.showDirectoryPicker({ mode: 'read' })
}

export async function pickFiles(w: PickerWindow = window as unknown as PickerWindow): Promise<FileHandleLike[]> {
  if (!w.showOpenFilePicker) throw new Error(NO_FOLDER_ACCESS)
  return w.showOpenFilePicker({
    multiple: true,
    types: [
      {
        description: 'Books',
        accept: {
          'application/epub+zip': ['.epub'],
          'application/pdf': ['.pdf'],
          'application/vnd.comicbook+zip': ['.cbz'],
        },
      },
    ],
  })
}

/** Natural order, so `p2.png` comes before `p10.png`. */
export function naturalSort(names: string[]): string[] {
  return [...names].sort((a, b) => a.localeCompare(b, undefined, { numeric: true, sensitivity: 'base' }))
}

const IMAGE = /\.(png|jpe?g|gif|webp|avif)$/i

/** The page images of a CBZ, in natural order. */
export function comicPageNames(names: string[]): string[] {
  return naturalSort(names.filter((n) => IMAGE.test(n) && !n.split('/').pop()!.startsWith('.') && !n.startsWith('__MACOSX')))
}
