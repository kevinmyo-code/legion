import { useEffect, useRef, useState, useSyncExternalStore, type Ref } from 'react'
import { flushSync } from 'react-dom'

import type { DemoPhone, DemoPhoneClip } from '@/api/demo'
import type { OrbSide } from '@/components/demo/scroll-story'

/**
 * "LEGION on the phone" (prototype D "typeset", 2026-11-03): one clip per
 * capability, cut from one recording. At a wide screen the capability texts
 * scroll past a phone frame that stays put (`position: sticky`), and the text in
 * the middle of the screen picks the clip the frame shows. Since the scroll story
 * (Kevin, 2026-11-19: "each section should be a separate video, like the old
 * clips") the frame holds ONE player, from 900 px: scrolling swaps only its clip
 * (poster and title, nothing loads or plays); a row's Play button puts that clip
 * in it and plays it. Narrower or shorter, or with reduced motion, there is no frame: each
 * clip sits under its own text, as before.
 *
 * Nothing plays by itself. A clip with a `video` is a `<video>` with controls
 * that loads nothing until asked (`preload="none"`). It plays with sound when
 * the visitor presses play, because the clips are of a voice assistant
 * (Kevin, 2026-11-04: the voice must be heard); captions are on. A clip without
 * one is a dark placeholder that says the recording is still to come. A clip
 * the stage moves away from is paused, so two never play at once.
 */

/** The stage needs room and motion. Asked positively, so "no answer" is the inline layout. */
// And height: the pinned phone (about 680 px with its line) never cut off at the foot (review
// 2026-11-20: a 1024 x 768 or 1280 x 720 window shows about 600-650 px of page).
const STAGE_QUERY = '(min-width: 900px) and (min-height: 720px) and (prefers-reduced-motion: no-preference)'

function subscribe(onChange: () => void) {
  if (typeof window.matchMedia !== 'function') return () => {}
  const query = window.matchMedia(STAGE_QUERY)
  query.addEventListener('change', onChange)
  return () => query.removeEventListener('change', onChange)
}

function stageNow(): boolean {
  if (typeof window.matchMedia !== 'function') return false
  return window.matchMedia(STAGE_QUERY).matches
}

function useStage(): boolean {
  return useSyncExternalStore(subscribe, stageNow, () => false)
}

/** A link the page may draw: same-site paths and http(s) only, never `javascript:`. */
export function safeHref(href: string | undefined): string | null {
  if (!href) return null
  const value = href.trim()
  if (value.startsWith('/') && !value.startsWith('//')) return value
  return /^https?:\/\//i.test(value) ? value : null
}

export function PhoneSection({ phone, side }: { phone: DemoPhone; side?: OrbSide }) {
  const stage = useStage()
  const { clips } = phone
  const [active, setActive] = useState(0)
  const steps = useRef<(HTMLDivElement | null)[]>([])
  const player = useRef<HTMLVideoElement>(null)

  // The step whose text crosses the middle tenth of the screen is the one on stage.
  useEffect(() => {
    if (!stage || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (!entry.isIntersecting) continue
          // A clip the visitor is watching stays put while the page scrolls past other rows
          // (review 2026-11-20); paused, the rows lead again.
          if (player.current && !player.current.paused) continue
          const index = steps.current.indexOf(entry.target as HTMLDivElement)
          if (index >= 0) setActive(index)
        }
      },
      { rootMargin: '-45% 0px -45% 0px', threshold: 0 },
    )
    for (const step of steps.current) if (step) observer.observe(step)
    return () => observer.disconnect()
  }, [stage, clips.length])

  if (clips.length === 0) return null
  const shown = Math.min(active, clips.length - 1)
  const fullTake = safeHref(phone.full_take)

  // A tap is the visitor asking: the clip goes into the player now (flushSync, so its source is
  // set while the tap still counts as one, which a phone's browser needs to play with sound).
  const play = (index: number) => {
    flushSync(() => setActive(index))
    void player.current?.play().catch(() => {})
  }

  return (
    <section
      id="phone"
      aria-labelledby="phone-title"
      className="rc-section"
      data-orb-side={side}
      // The scroll story's pinned scene: the player stays while the rows scroll past.
      data-pinned={stage ? 'true' : undefined}
    >
      <h2 id="phone-title" className="rc-h2">
        {phone.title}
      </h2>
      {phone.intro && <p>{phone.intro}</p>}
      <div className="rc-scrolly" data-mode={stage ? 'stage' : 'inline'}>
        <div>
          {clips.map((clip, index) => (
            <div
              key={`${index}-${clip.title}`}
              ref={(element) => {
                steps.current[index] = element
              }}
              className="rc-step"
              data-active={stage && index === shown ? '' : undefined}
            >
              <h3 className="rc-h3">{clip.title}</h3>
              <p>{clip.body}</p>
              {stage && clip.video && (
                <button
                  type="button"
                  className="rc-step-play"
                  aria-label={`Play: ${clip.title}`}
                  aria-current={index === shown ? 'true' : undefined}
                  onClick={() => play(index)}
                >
                  Play
                </button>
              )}
              {!stage && (
                <div className="rc-inline">
                  <div className="rc-pscreen">
                    <Clip clip={clip} index={index} on />
                  </div>
                </div>
              )}
            </div>
          ))}
        </div>
        {!stage && clips.length > 1 && (
          // Shown only at phone width, where the clips are one swipeable row (CSS).
          <p className="rc-swipe" aria-hidden="true">
            Swipe sideways for all {clips.length}.
          </p>
        )}
        {stage && (
          <div className="rc-stage">
            <div className="rc-phone">
              <div className="rc-pscreen">
                {/* One player: a new source stops the last clip and loads nothing until asked. */}
                <Clip clip={clips[shown]} index={shown} on videoRef={player} />
              </div>
            </div>
            <p className="rc-stage-caption">
              {shown + 1} of {clips.length}: {clips[shown].title}
            </p>
          </div>
        )}
      </div>
      {fullTake && (
        <p className="rc-access">
          The whole take, one recording: <a href={fullTake}>watch it end to end</a>.
        </p>
      )}
    </section>
  )
}

function Clip({
  clip,
  index,
  on,
  videoRef,
}: {
  clip: DemoPhoneClip
  index: number
  on: boolean
  videoRef?: Ref<HTMLVideoElement>
}) {
  return (
    // A clip off stage is invisible, so it is also out of the tab order and the reading order.
    <div className="rc-clip" data-on={on ? '' : undefined} inert={!on} aria-hidden={on ? undefined : true}>
      {clip.video ? (
        <video
          ref={videoRef}
          src={clip.video}
          poster={clip.poster}
          aria-label={clip.title}
          // The clips are hosted off-site (a storage bucket); the captions track needs CORS to load.
          crossOrigin="anonymous"
          playsInline
          controls
          preload="none"
        >
          {clip.captions && (
            <track key={clip.captions} kind="captions" src={clip.captions} srcLang="en" label="Captions" default />
          )}
        </video>
      ) : (
        <>
          <span className="rc-caps">Clip {index + 1}</span>
          <span className="rc-clip-title">{clip.title}</span>
          <span className="rc-clip-meta">Recording to come</span>
        </>
      )}
    </div>
  )
}
