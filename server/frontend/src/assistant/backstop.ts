import { WINDOW_MS } from './endpointer'

/** Hidden this long with a voice conversation open, and it closes. */
export const HIDDEN_CLOSE_MS = 5_000
/** Voice open but nothing going on (not listening, playing or thinking) this long: listen again. */
export const STUCK_MS = 5_000
const CHECK_MS = 1_000

/**
 * Wall-clock backstops for an open voice conversation (review r3). The follow-up window runs on
 * the capture's own chunks, and a browser that suspends capture (tab hidden, phone locked, audio
 * focus lost) sends none, so the window would never lapse and the mic would stay open:
 * - no chunk for a whole window while listening: `lapsed`;
 * - the page hidden for `HIDDEN_CLOSE_MS`: `hidden`;
 * - `stuck()` true for `STUCK_MS` (voice open, yet nothing listening, playing or thinking): `unstick`.
 */
export class Backstop {
  private interval: ReturnType<typeof setInterval> | null = null
  private hiddenTimer: ReturnType<typeof setTimeout> | null = null
  private lastChunkAt = 0
  private stuckSince: number | null = null
  private readonly now: () => number
  private readonly lapsed: () => void
  private readonly hiddenTooLong: () => void
  private readonly stuck: () => boolean
  private readonly unstick: () => void

  constructor(
    now: () => number,
    lapsed: () => void,
    hiddenTooLong: () => void,
    stuck: () => boolean = () => false,
    unstick: () => void = () => {},
  ) {
    this.now = now
    this.lapsed = lapsed
    this.hiddenTooLong = hiddenTooLong
    this.stuck = stuck
    this.unstick = unstick
  }

  /** Listening starts: the window's clock starts now. */
  listening() {
    this.lastChunkAt = this.now()
    this.interval ??= setInterval(() => {
      if (this.now() - this.lastChunkAt >= WINDOW_MS) this.lapsed()
      if (!this.stuck()) this.stuckSince = null
      else if (this.stuckSince === null) this.stuckSince = this.now()
      else if (this.now() - this.stuckSince >= STUCK_MS) {
        this.stuckSince = null
        this.unstick()
      }
    }, CHECK_MS)
  }

  chunk() {
    this.lastChunkAt = this.now()
  }

  visibility(hidden: boolean) {
    if (this.hiddenTimer) clearTimeout(this.hiddenTimer)
    this.hiddenTimer = hidden ? setTimeout(this.hiddenTooLong, HIDDEN_CLOSE_MS) : null
  }

  stop() {
    if (this.interval) clearInterval(this.interval)
    this.interval = null
    if (this.hiddenTimer) clearTimeout(this.hiddenTimer)
    this.hiddenTimer = null
  }
}
