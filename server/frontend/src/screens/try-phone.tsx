import { useRef, useState, type Ref } from 'react'
import { flushSync } from 'react-dom'

import type { DemoPhone, DemoPhoneClip } from '@/api/demo'
import type { OrbSide } from '@/components/demo/scroll-story'

/**
 * "LEGION on the phone": one clip per capability, cut from one recording. ONE player (Kevin,
 * 2026-11-19: "each section should be a separate video, like the old clips"), full width of the
 * column at 16:9, because the clips are landscape (Kevin's ruling, 2026-11-22: no phone frame,
 * a full-width video). A wide player cannot sit beside the texts, so it no longer pins or follows the
 * scroll: under it, each capability's text has a Play button that puts its clip in the player.
 *
 * Nothing plays by itself. A clip with a `video` is a `<video>` with controls that loads nothing
 * until asked (`preload="none"`). It plays with sound when the visitor presses play, because the
 * clips are of a voice assistant (Kevin, 2026-11-04: the voice must be heard); captions are on. A
 * clip without one is a dark placeholder that says the recording is still to come. One player, so
 * two clips never play at once.
 */

/** A link the page may draw: same-site paths and http(s) only, never `javascript:`. */
export function safeHref(href: string | undefined): string | null {
  if (!href) return null
  const value = href.trim()
  if (value.startsWith('/') && !value.startsWith('//')) return value
  return /^https?:\/\//i.test(value) ? value : null
}

function reducedMotion(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

export function PhoneSection({ phone, side }: { phone: DemoPhone; side?: OrbSide }) {
  const { clips } = phone
  const [active, setActive] = useState(0)
  const box = useRef<HTMLDivElement>(null)
  const player = useRef<HTMLVideoElement>(null)

  if (clips.length === 0) return null
  const shown = Math.min(active, clips.length - 1)
  const fullTake = safeHref(phone.full_take)

  // A tap is the visitor asking: the clip goes into the player now (flushSync, so its source is
  // set while the tap still counts as one, which a phone's browser needs to play with sound).
  // Play first, then bring the player into view: at phone width the row sits below it.
  const play = (index: number) => {
    flushSync(() => setActive(index))
    void player.current?.play().catch(() => {})
    box.current?.scrollIntoView?.({ block: 'nearest', behavior: reducedMotion() ? 'auto' : 'smooth' })
  }

  return (
    <section id="phone" aria-labelledby="phone-title" className="rc-section" data-orb-side={side}>
      <h2 id="phone-title" className="rc-h2">
        {phone.title}
      </h2>
      {phone.intro && <p>{phone.intro}</p>}
      <div className="rc-scrolly">
        <figure className="rc-player-wrap">
          <div ref={box} className="rc-player">
            {/* One player: a new source stops the last clip and loads nothing until asked. */}
            <Clip clip={clips[shown]} index={shown} videoRef={player} />
          </div>
          <figcaption className="rc-stage-caption">
            {shown + 1} of {clips.length}: {clips[shown].title}
          </figcaption>
        </figure>
        <div>
          {clips.map((clip, index) => (
            <div key={`${index}-${clip.title}`} className="rc-step">
              <h3 className="rc-h3">{clip.title}</h3>
              <p>{clip.body}</p>
              {clip.video && (
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
            </div>
          ))}
        </div>
      </div>
      {fullTake && (
        <p className="rc-access">
          The whole take, one recording: <a href={fullTake}>watch it end to end</a>.
        </p>
      )}
    </section>
  )
}

function Clip({ clip, index, videoRef }: { clip: DemoPhoneClip; index: number; videoRef?: Ref<HTMLVideoElement> }) {
  return (
    <div className="rc-clip">
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
