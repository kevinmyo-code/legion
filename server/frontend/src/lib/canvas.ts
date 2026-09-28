import { relativeTime } from '@/components/freshness'

/**
 * What `ingest/canvas.py` writes into `structured_meta` on every Canvas-
 * backed task (backend-etl ticket 04/05, migration 0005). Canvas never
 * ticks `done` - that stays Kevin's - so this is read-only evidence beside
 * the row, not a second source of truth for completion.
 *
 * Every field is `unknown` on the wire (`Event.structured_meta`); this is
 * the narrow, defensive read of it this screen actually needs, not a
 * re-derivation of Canvas's own semantics (`is_submitted` etc. already ran
 * server-side and its answer is `canvas_submitted`).
 */
export interface CanvasMeta {
  canvas_assignment_id?: unknown
  canvas_submitted?: boolean
  submission_state?: string | null
  submitted_at?: string | null
  missing?: boolean | null
  late?: boolean | null
  excused?: boolean | null
  score?: number | null
  grade?: string | null
  points_possible?: number | null
  read_at?: string | null
}

/** Only rows Canvas actually placed carry `canvas_assignment_id`
 * (`structured_meta ? 'canvas_assignment_id'` on the server). A row with no
 * `structured_meta` at all, or one from a source other than Canvas, reads as
 * `null` here and gets no line. */
export function canvasMetaOf(structuredMeta: unknown): CanvasMeta | null {
  if (
    typeof structuredMeta !== 'object' ||
    structuredMeta === null ||
    !('canvas_assignment_id' in structuredMeta)
  ) {
    return null
  }
  return structuredMeta as CanvasMeta
}

// `ingest/freshness.py`'s `STALE_AFTER[Source.CANVAS]`. Duplicated rather
// than fetched, same as this screen's other server-mirrored constants -
// there is no endpoint that hands a client a threshold, only a verdict
// (`/api/freshness`'s `stale`), and that verdict is about the LAST POLL, not
// this one row's `read_at`. If the server value changes, this one goes
// stale with it; nothing catches that mismatch today.
const CANVAS_FRESHNESS_THRESHOLD_MS = 2 * 60 * 60 * 1000

function gradeSuffix(meta: CanvasMeta): string {
  if (meta.score != null && meta.points_possible != null) {
    return `, ${meta.score}/${meta.points_possible}`
  }
  if (meta.grade) {
    return `, ${meta.grade}`
  }
  return ''
}

/**
 * The line under a Canvas-backed task's title, per
 * `.scratch/backend-etl/issues/11-canvas-submitted-on-the-web.md`'s table.
 * Words, never colour or a glyph alone. Returns `''` for the one row the
 * table says shows nothing (not done, not submitted) - a row with nothing
 * to report renders nothing, not an empty-looking truth.
 *
 * `done` is the task's own `done` flag; `meta` is `canvasMetaOf`'s read of
 * `structured_meta`. `now` is injectable for the test suite only.
 */
export function canvasLine(done: boolean, meta: CanvasMeta, now: number = Date.now()): string {
  let base: string
  if (meta.excused === true) {
    base = 'Canvas: excused'
  } else if (meta.missing === true) {
    base = 'Canvas: marked missing'
  } else if (meta.canvas_submitted === true) {
    base = (done ? 'Canvas: submitted' : 'Canvas says submitted') + gradeSuffix(meta)
  } else if (done) {
    base = 'Canvas: not submitted'
  } else {
    return ''
  }

  if (!meta.read_at) {
    return base
  }
  const readAt = new Date(meta.read_at).getTime()
  if (Number.isNaN(readAt) || now - readAt <= CANVAS_FRESHNESS_THRESHOLD_MS) {
    return base
  }
  return `${base} as of ${relativeTime(readAt, now)}`
}
