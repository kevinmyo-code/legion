import { zipSync, strToU8 } from 'fflate'

/**
 * Small book files generated in code, so there is no licence question: the text
 * is written here. Used by the Vitest suites and the Playwright smoke (which
 * serves them through a faked `showDirectoryPicker`).
 */

const SENTENCES = [
  'The lamp on the harbour wall was lit before anyone thought to ask who had climbed up to light it.',
  'She counted the boats twice and got two different numbers, and decided that both were right.',
  'By the third morning the fog had learned the names of the streets and stopped getting lost in them.',
  'Nobody in the village kept a clock, only a habit of looking up when the gulls changed their minds.',
  'He wrote the date at the top of the page and then, for a long while, nothing at all.',
]

function paragraph(seed: number): string {
  return Array.from({ length: 5 }, (_, i) => SENTENCES[(seed + i) % SENTENCES.length]).join(' ')
}

export function chapterText(chapter: number, paragraphs = 24): string {
  return Array.from({ length: paragraphs }, (_, p) => `<p>${paragraph(chapter * 3 + p)}</p>`).join('\n')
}

export interface FixtureEpubOptions {
  title?: string
  chapters?: number
  paragraphs?: number
  /** Nest the package under this directory instead of OEBPS (hrefs then differ). */
  root?: string
}

export function makeEpub(options: FixtureEpubOptions = {}): Uint8Array {
  const { title = 'The Harbour Clock', chapters = 3, paragraphs = 24, root = 'OEBPS' } = options
  const files: Record<string, Uint8Array> = {
    mimetype: strToU8('application/epub+zip'),
    'META-INF/container.xml': strToU8(
      `<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="${root}/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>`,
    ),
  }
  const ids = Array.from({ length: chapters }, (_, i) => i + 1)
  files[`${root}/content.opf`] = strToU8(
    `<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:uuid:legion-fixture-${title}</dc:identifier><dc:title>${title}</dc:title><dc:language>en</dc:language></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>${ids
      .map((n) => `<item id="c${n}" href="ch${n}.xhtml" media-type="application/xhtml+xml"/>`)
      .join('')}</manifest><spine>${ids.map((n) => `<itemref idref="c${n}"/>`).join('')}</spine></package>`,
  )
  files[`${root}/nav.xhtml`] = strToU8(
    `<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol>${ids
      .map((n) => `<li><a href="ch${n}.xhtml">Chapter ${n}</a></li>`)
      .join('')}</ol></nav></body></html>`,
  )
  for (const n of ids) {
    files[`${root}/ch${n}.xhtml`] = strToU8(
      `<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter ${n}</title></head><body><h1>Chapter ${n}</h1>${chapterText(n, paragraphs)}</body></html>`,
    )
  }
  // The mimetype entry must be first and stored, per the EPUB container spec.
  return zipSync(files, { level: 0 })
}

/** A text PDF: `pages` pages, each one line saying which page it is. */
export function makePdf(pages = 3): Uint8Array {
  const objects: string[] = []
  const pageIds = Array.from({ length: pages }, (_, i) => 4 + i * 2)
  objects[1] = '<< /Type /Catalog /Pages 2 0 R >>'
  objects[2] = `<< /Type /Pages /Kids [${pageIds.map((id) => `${id} 0 R`).join(' ')}] /Count ${pages} >>`
  objects[3] = '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>'
  pageIds.forEach((id, i) => {
    const stream = `BT /F1 28 Tf 72 700 Td (Fixture page ${i + 1} of ${pages}) Tj ET`
    objects[id] =
      `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents ${id + 1} 0 R /Resources << /Font << /F1 3 0 R >> >> >>`
    objects[id + 1] = `<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`
  })
  let out = '%PDF-1.4\n'
  const offsets: number[] = []
  for (let i = 1; i < objects.length; i++) {
    if (!objects[i]) continue
    offsets[i] = out.length
    out += `${i} 0 obj\n${objects[i]}\nendobj\n`
  }
  const xrefAt = out.length
  out += `xref\n0 ${objects.length}\n0000000000 65535 f \n`
  for (let i = 1; i < objects.length; i++) {
    out += `${String(offsets[i] ?? 0).padStart(10, '0')} 00000 n \n`
  }
  out += `trailer\n<< /Size ${objects.length} /Root 1 0 R >>\nstartxref\n${xrefAt}\n%%EOF`
  return strToU8(out)
}

/** 1x1 PNG, enough for a comic page that decodes. */
const PNG_1X1 = Uint8Array.from(
  atob('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=='),
  (c) => c.charCodeAt(0),
)

/** A CBZ whose pages are named so that natural order differs from plain string order. */
export function makeCbz(): Uint8Array {
  return zipSync({
    'p10.png': PNG_1X1,
    'p2.png': PNG_1X1,
    'p1.png': PNG_1X1,
  })
}
