import { useEffect, useRef, useState, useSyncExternalStore, type KeyboardEvent } from 'react'

import type { DemoPhone, DemoPhoneClip, DemoPhoneWalkthrough } from '@/api/demo'

/**
 * "LEGION on the phone" as one video (Kevin, 2026-11-19: "one video showcasing what the legion
 * phone can do. and how it works in the back"). The chapters are buttons: a press seeks the video
 * there and plays it, the chapter playing is marked as it moves, and left/right step between
 * chapters. Each chapter's line is text on the page, so it reads without sound.
 *
 * Nothing plays by itself and nothing loads before a press: the video shows its poster
 * (`preload="none"`) and plays with sound when the visitor asks. The full clips under it (at most
 * three, the schema's limit) are the same kind of `<video>`.
 */

const REDUCED = '(prefers-reduced-motion: reduce)'

function useReducedMotion(): boolean {
  return useSyncExternalStore(
    (onChange) => {
      if (typeof window.matchMedia !== 'function') return () => {}
      const query = window.matchMedia(REDUCED)
      query.addEventListener('change', onChange)
      return () => query.removeEventListener('change', onChange)
    },
    () => typeof window.matchMedia === 'function' && window.matchMedia(REDUCED).matches,
    () => false,
  )
}

/** "1:05" from 65 seconds. */
function clock(seconds: number): string {
  const s = Math.floor(seconds)
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}

/** The last chapter that has started at `time`, or -1 before the first. */
export function chapterAt(chapters: { at: number }[], time: number): number {
  let current = -1
  chapters.forEach((chapter, index) => {
    if (chapter.at <= time) current = index
  })
  return current
}

export function PhoneWalkthrough({ phone, walk }: { phone: DemoPhone; walk: DemoPhoneWalkthrough }) {
  const video = useRef<HTMLVideoElement | null>(null)
  const buttons = useRef<(HTMLButtonElement | null)[]>([])
  const [current, setCurrent] = useState(-1)
  const reduced = useReducedMotion()
  const { chapters } = walk

  useEffect(() => {
    const element = video.current
    if (!element) return
    const follow = () => setCurrent(chapterAt(chapters, element.currentTime))
    element.addEventListener('timeupdate', follow)
    element.addEventListener('seeked', follow)
    return () => {
      element.removeEventListener('timeupdate', follow)
      element.removeEventListener('seeked', follow)
    }
  }, [chapters])

  function seek(index: number) {
    const element = video.current
    if (!element) return
    const at = chapters[index].at
    if (element.readyState < HTMLMediaElement.HAVE_METADATA) {
      // Nothing is loaded before a press (preload="none"), and a seek before the metadata is lost:
      // the press starts the load, and the seek lands once the length is known.
      element.addEventListener('loadedmetadata', () => (element.currentTime = at), { once: true })
    }
    element.currentTime = at
    setCurrent(index)
    // A press on a chapter is the visitor asking to watch from there.
    element.play()?.catch(() => {})
  }

  function step(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    const next = event.key === 'ArrowRight' ? index + 1 : event.key === 'ArrowLeft' ? index - 1 : null
    if (next === null) return
    event.preventDefault()
    if (next < 0 || next >= chapters.length) return
    seek(next)
    buttons.current[next]?.focus()
  }

  return (
    <section
      id="phone"
      aria-labelledby="phone-title"
      className="rc-section"
      // One sound at a time: a video that starts pauses the others in the section.
      onPlayCapture={(event) => {
        for (const other of Array.from(event.currentTarget.querySelectorAll('video'))) if (other !== event.target) other.pause()
      }}
    >
      <h2 id="phone-title" className="rc-h2">
        {phone.title}
      </h2>
      {phone.intro && <p>{phone.intro}</p>}
      <div className="rc-walk">
        <video
          ref={video}
          className="rc-walk-video"
          src={walk.video}
          poster={walk.poster}
          aria-label={phone.title}
          // Hosted off-site (a storage bucket); the captions track needs CORS to load.
          crossOrigin="anonymous"
          playsInline
          controls
          preload="none"
        >
          {walk.captions && <track kind="captions" src={walk.captions} srcLang="en" label="Captions" default />}
        </video>
        <ol className="rc-chapters" aria-label="Chapters" data-motion={reduced ? 'reduce' : undefined}>
          {chapters.map((chapter, index) => (
            <li key={`${index}-${chapter.title}`}>
              <button
                ref={(element) => {
                  buttons.current[index] = element
                }}
                type="button"
                className="rc-chapter"
                aria-current={index === current ? 'step' : undefined}
                onClick={() => seek(index)}
                onKeyDown={(event) => step(event, index)}
              >
                <span className="rc-chapter-at">{clock(chapter.at)}</span>
                <span className="rc-chapter-title">{chapter.title}</span>
                <span className="rc-chapter-line">{chapter.line}</span>
              </button>
            </li>
          ))}
        </ol>
      </div>
      {phone.clips.length > 0 && (
        <div className="rc-full-clips">
          {phone.clips.map((clip, index) => (
            <FullClip key={`${index}-${clip.title}`} clip={clip} />
          ))}
        </div>
      )}
    </section>
  )
}

function FullClip({ clip }: { clip: DemoPhoneClip }) {
  return (
    <div className="rc-full-clip">
      <h3 className="rc-h3">{clip.title}</h3>
      <p>{clip.body}</p>
      {clip.video && (
        <div className="rc-inline">
          <div className="rc-pscreen">
            <div className="rc-clip" data-on="">
              <video
                src={clip.video}
                poster={clip.poster}
                aria-label={clip.title}
                crossOrigin="anonymous"
                playsInline
                controls
                preload="none"
              >
                {clip.captions && <track kind="captions" src={clip.captions} srcLang="en" label="Captions" default />}
              </video>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
