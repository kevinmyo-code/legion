import type { DemoSectionMedia } from '@/api/demo'
import { ModelViewer } from '@/components/demo/model-viewer'

/**
 * A project section's media slot: the video when there is one (hosted off-site like the phone
 * clips, so the captions track needs CORS), else its note ("coming soon") as a quiet line; then
 * its 3D models, each a viewer a visitor can turn.
 */
export function Media({ media, title }: { media: DemoSectionMedia; title: string }) {
  return (
    <>
      <Clip media={media} title={title} />
      {media.models?.map((model) => (
        <ModelViewer key={model.model} model={model} />
      ))}
    </>
  )
}

function Clip({ media, title }: { media: DemoSectionMedia; title: string }) {
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
