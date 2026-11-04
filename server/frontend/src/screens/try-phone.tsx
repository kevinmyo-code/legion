import { useEffect, useRef, useState, useSyncExternalStore, type Ref } from 'react'

import type { DemoPhone, DemoPhoneClip } from '@/api/demo'

/**
 * "LEGION on the phone" (prototype D "typeset", 2026-11-03): one clip per
 * capability, cut from one recording. At a wide screen the capability texts
 * scroll past a phone frame that stays put, and the text in the middle of the
 * screen picks the clip the frame shows. Under 1300px, or with reduced motion,
 * there is no frame: each clip sits under its own text.
 *
 * Nothing plays by itself. A clip with a `video` is a muted `<video>` with
 * controls that loads nothing until asked (`preload="none"`); a clip without
 * one is a dark placeholder that says the recording is still to come. A clip
 * the stage moves away from is paused, so two never play at once.
 */

/** The stage needs room and motion. Asked positively, so "no answer" is the inline layout. */
const STAGE_QUERY = '(min-width: 1300px) and (prefers-reduced-motion: no-preference)'

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

export function PhoneSection({ phone }: { phone: DemoPhone }) {
  const stage = useStage()
  const { clips } = phone
  const [active, setActive] = useState(0)
  const steps = useRef<(HTMLDivElement | null)[]>([])
  const videos = useRef<(HTMLVideoElement | null)[]>([])

  // The step whose text crosses the middle tenth of the screen is the one on stage.
  useEffect(() => {
    if (!stage || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (!entry.isIntersecting) continue
          const index = steps.current.indexOf(entry.target as HTMLDivElement)
          if (index >= 0) setActive(index)
        }
      },
      { rootMargin: '-45% 0px -45% 0px', threshold: 0 },
    )
    for (const step of steps.current) if (step) observer.observe(step)
    return () => observer.disconnect()
  }, [stage, clips.length])

  // A clip the stage moved away from stops playing.
  useEffect(() => {
    if (!stage) return
    videos.current.forEach((video, index) => {
      if (video && index !== active && !video.paused) video.pause()
    })
  }, [stage, active])

  if (clips.length === 0) return null
  const shown = Math.min(active, clips.length - 1)
  const fullTake = safeHref(phone.full_take)

  return (
    <section id="phone" aria-labelledby="phone-title" className="rc-section">
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
                {clips.map((clip, index) => (
                  <Clip
                    key={`${index}-${clip.title}`}
                    clip={clip}
                    index={index}
                    on={index === shown}
                    videoRef={(element) => {
                      videos.current[index] = element
                    }}
                  />
                ))}
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
          muted
          playsInline
          controls
          preload="none"
        >
          {clip.captions && <track kind="captions" src={clip.captions} srcLang="en" label="Captions" default />}
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
