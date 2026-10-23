import { Link, Locator, LocatorLocations, Manifest, Publication, Resource, type Fetcher } from '@readium/shared'
import { unzipSync } from 'fflate'

/**
 * Opening an EPUB in the browser, for Readium's web navigator.
 *
 * `@readium/navigator` and `@readium/shared` ship NO EPUB reader: their
 * `Publication` wants a Readium Web Publication Manifest and a `Fetcher`, and the
 * toolkit expects a streamer on a server to produce both (the Go toolkit's
 * `rwp serve`). Book files here never leave the browser (ticket 07 ruling 2), so
 * this module is the streamer: unzip with fflate, read `container.xml` and the
 * package document, and build the manifest and a fetcher over the zip entries.
 *
 * **Hrefs are zip-root-relative with no leading slash** (`OEBPS/ch1.xhtml`), the
 * shape the phone's Readium Kotlin 3.3 emits for an EPUB, so a stored locator
 * names the same resource on both devices.
 */

export interface TocEntry {
  title: string
  href: string
  depth: number
}

export interface OpenedEpub {
  publication: Publication
  /** Approximate positions list, see `buildPositions`. */
  positions: Locator[]
  toc: TocEntry[]
  title: string
  /** href (no fragment) -> chapter title, for naming where a locator is. */
  chapterByHref: Map<string, string>
}

export class EpubError extends Error {}

const XHTML = 'application/xhtml+xml'

function parseXml(bytes: Uint8Array, what: string): Document {
  const doc = new DOMParser().parseFromString(new TextDecoder().decode(bytes), 'application/xml')
  if (doc.getElementsByTagName('parsererror').length > 0) {
    throw new EpubError(`This file's ${what} could not be read, so it cannot be opened as an EPUB.`)
  }
  return doc
}

/** Resolve `rel` against the directory of `base` inside the zip (both zip-root-relative). */
export function resolveZipPath(base: string, rel: string): string {
  const withoutFragment = rel.split('#')[0]
  let decoded = withoutFragment
  try {
    decoded = decodeURIComponent(withoutFragment)
  } catch {
    /* keep the raw path */
  }
  const stack = base.includes('/') ? base.slice(0, base.lastIndexOf('/')).split('/') : []
  for (const part of decoded.split('/')) {
    if (part === '' || part === '.') continue
    if (part === '..') stack.pop()
    else stack.push(part)
  }
  return stack.join('/')
}

class ZipResource extends Resource {
  private readonly _link: Link
  private readonly bytes: Uint8Array | undefined
  constructor(link: Link, bytes: Uint8Array | undefined) {
    super()
    this._link = link
    this.bytes = bytes
  }
  async link() {
    return this._link
  }
  async length() {
    return this.bytes?.length
  }
  async read(range?: { start: number; endInclusive: number }) {
    if (!this.bytes) return undefined
    return range ? this.bytes.slice(range.start, range.endInclusive + 1) : this.bytes
  }
  close() {}
}

class ZipFetcher implements Fetcher {
  private readonly files: Record<string, Uint8Array>
  private readonly all: Link[]
  constructor(files: Record<string, Uint8Array>, all: Link[]) {
    this.files = files
    this.all = all
  }
  links() {
    return this.all
  }
  get(link: Link) {
    return new ZipResource(link, this.files[resolveZipPath('', link.href)])
  }
  close() {}
}

function mediaTypeFor(path: string): string {
  const ext = path.slice(path.lastIndexOf('.') + 1).toLowerCase()
  const map: Record<string, string> = {
    xhtml: XHTML,
    html: 'text/html',
    htm: 'text/html',
    css: 'text/css',
    png: 'image/png',
    jpg: 'image/jpeg',
    jpeg: 'image/jpeg',
    gif: 'image/gif',
    svg: 'image/svg+xml',
    webp: 'image/webp',
    ttf: 'font/ttf',
    otf: 'font/otf',
    woff: 'font/woff',
    woff2: 'font/woff2',
    ncx: 'application/x-dtbncx+xml',
    xml: 'application/xml',
  }
  return map[ext] ?? 'application/octet-stream'
}

function byLocal(root: Element | Document, localName: string): Element[] {
  return Array.from(root.getElementsByTagName('*')).filter((e) => e.localName === localName)
}

interface Item {
  href: string
  type: string
  props: string
}

/** Table of contents from the EPUB 3 nav document, else the EPUB 2 NCX. */
function readToc(files: Record<string, Uint8Array>, items: Map<string, Item>): TocEntry[] {
  const out: TocEntry[] = []
  const frag = (src: string) => (src.includes('#') ? '#' + src.split('#')[1] : '')
  const nav = [...items.values()].find((i) => i.props.split(/\s+/).includes('nav'))
  if (nav && files[nav.href]) {
    const doc = new DOMParser().parseFromString(new TextDecoder().decode(files[nav.href]), 'text/html')
    const tocNav =
      Array.from(doc.querySelectorAll('nav')).find((n) => (n.getAttribute('epub:type') ?? '').includes('toc')) ??
      doc.querySelector('nav')
    const walk = (list: Element | null | undefined, depth: number) => {
      if (!list) return
      for (const li of Array.from(list.children).filter((c) => c.localName === 'li')) {
        const a = Array.from(li.children).find((c) => c.localName === 'a')
        const href = a?.getAttribute('href')
        if (a && href) {
          out.push({
            title: (a.textContent ?? '').replace(/\s+/g, ' ').trim(),
            href: resolveZipPath(nav.href, href) + frag(href),
            depth,
          })
        }
        walk(Array.from(li.children).find((c) => c.localName === 'ol'), depth + 1)
      }
    }
    walk(tocNav?.querySelector('ol'), 0)
    if (out.length > 0) return out
  }
  const ncx = [...items.values()].find((i) => i.type === 'application/x-dtbncx+xml')
  if (ncx && files[ncx.href]) {
    const doc = parseXml(files[ncx.href], 'table of contents')
    const walk = (parent: Element, depth: number) => {
      for (const point of Array.from(parent.children).filter((c) => c.localName === 'navPoint')) {
        const label = byLocal(point, 'text')[0]?.textContent?.trim()
        const src = byLocal(point, 'content')[0]?.getAttribute('src')
        if (label && src) out.push({ title: label, href: resolveZipPath(ncx.href, src) + frag(src), depth })
        walk(point, depth + 1)
      }
    }
    const map = byLocal(doc, 'navMap')[0]
    if (map) walk(map, 0)
  }
  return out
}

/**
 * An approximate positions list: one position per ~1 KiB of each reading-order
 * resource, with `totalProgression` spread by size. It is what the navigator needs
 * to report `totalProgression` and to find a spot from one. Readium Kotlin builds
 * its own list the same way (by archive length) but not byte-for-byte, so the two
 * devices' position NUMBERS differ; only the 0-1 `totalProgression` is ever relied
 * on across devices, and only as the "near" fallback.
 */
export function buildPositions(order: { href: string; type: string; size: number }[]): Locator[] {
  const counts = order.map((o) => Math.max(1, Math.ceil(o.size / 1024)))
  const total = counts.reduce((a, b) => a + b, 0)
  const out: Locator[] = []
  let position = 0
  order.forEach((item, i) => {
    for (let k = 0; k < counts[i]; k++) {
      position += 1
      out.push(
        new Locator({
          href: item.href,
          type: item.type,
          locations: new LocatorLocations({
            position,
            progression: k / counts[i],
            totalProgression: (position - 1) / total,
          }),
        }),
      )
    }
  })
  return out
}

/** Unzip and describe an EPUB. Throws `EpubError` with a sentence for a person. */
export function openEpub(bytes: Uint8Array): OpenedEpub {
  let files: Record<string, Uint8Array>
  try {
    files = unzipSync(bytes)
  } catch {
    throw new EpubError('This file is not a readable EPUB (it is not a valid zip archive).')
  }
  const container = files['META-INF/container.xml']
  if (!container) throw new EpubError('This EPUB has no META-INF/container.xml, so it cannot be opened.')
  const rootfile = byLocal(parseXml(container, 'container.xml'), 'rootfile')[0]?.getAttribute('full-path')
  if (!rootfile || !files[rootfile]) throw new EpubError('This EPUB names a package document it does not contain.')
  const opf = parseXml(files[rootfile], 'package document')

  const items = new Map<string, Item>()
  const all: Item[] = []
  for (const item of byLocal(opf, 'item')) {
    const id = item.getAttribute('id')
    const rel = item.getAttribute('href')
    if (!id || !rel) continue
    const href = resolveZipPath(rootfile, rel)
    const rec = {
      href,
      type: item.getAttribute('media-type') ?? mediaTypeFor(href),
      props: item.getAttribute('properties') ?? '',
    }
    items.set(id, rec)
    all.push(rec)
  }
  const spine = byLocal(opf, 'itemref')
    .map((r) => r.getAttribute('idref'))
    .map((id) => (id ? items.get(id) : undefined))
    .filter((i): i is Item => !!i)
  if (spine.length === 0) throw new EpubError('This EPUB has an empty reading order, so there is nothing to show.')

  const toc = readToc(files, items)
  const chapterByHref = new Map<string, string>()
  for (const t of toc) {
    const key = t.href.split('#')[0]
    if (!chapterByHref.has(key)) chapterByHref.set(key, t.title)
  }

  const title = byLocal(opf, 'title')[0]?.textContent?.trim() || 'Untitled'
  const language = byLocal(opf, 'language')[0]?.textContent?.trim() || 'en'
  const dir = byLocal(opf, 'spine')[0]?.getAttribute('page-progression-direction')
  const readingOrder = spine.map((s) => ({
    href: s.href,
    type: s.type || XHTML,
    ...(chapterByHref.get(s.href) ? { title: chapterByHref.get(s.href) } : {}),
  }))
  const inOrder = new Set(spine.map((s) => s.href))
  const resources = all.filter((p) => !inOrder.has(p.href)).map((p) => ({ href: p.href, type: p.type }))

  const manifest = Manifest.deserialize({
    '@context': 'https://readium.org/webpub-manifest/context.jsonld',
    metadata: { title, language: [language], readingProgression: dir === 'rtl' ? 'rtl' : 'ltr' },
    links: [],
    readingOrder,
    resources,
    toc: toc.filter((t) => t.depth === 0).map((t) => ({ href: t.href, title: t.title })),
  })
  if (!manifest) throw new EpubError('This EPUB could not be described, so it cannot be opened.')

  const links = [...readingOrder, ...resources].map((l) => new Link({ href: l.href, type: l.type }))
  const publication = new Publication({ manifest, fetcher: new ZipFetcher(files, links) })
  const positions = buildPositions(
    spine.map((s) => ({ href: s.href, type: s.type || XHTML, size: files[s.href]?.length ?? 0 })),
  )
  return { publication, positions, toc, title, chapterByHref }
}
