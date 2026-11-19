import type { DemoMedia } from '@/api/demo'

/**
 * A project section's media slot: the video when there is one (hosted off-site like the phone
 * clips, so the captions track needs CORS), else its note ("coming soon") as a quiet line, else
 * nothing.
 */
export function Media({ media, title }: { media: DemoMedia; title: string }) {
  if (media.video) {
    return (
      <figure className="rc-figure">
        <video
          className="rc-video"
          src={media.video}
          poster={media.poster}
          aria-label={title}
          crossOrigin="anonymous"
          playsInline
          controls
          preload="none"
        >
          {media.captions && <track kind="captions" src={media.captions} srcLang="en" label="Captions" default />}
        </video>
        {media.note && <figcaption className="rc-figcaption">{media.note}</figcaption>}
      </figure>
    )
  }
  if (media.note) return <p className="rc-media-note">{media.note}</p>
  return null
}
