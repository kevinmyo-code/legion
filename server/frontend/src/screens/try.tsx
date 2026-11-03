import { useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { Download, Eye } from 'lucide-react'
import { useEffect, useRef, useState, type ReactNode } from 'react'

import { DEMO_SESSION_KEY, enterDemo, useDemoContent, useDemoSession, type DemoContent } from '@/api/demo'
import { useMe } from '@/api/queries'
import { AssistantProvider } from '@/assistant/assistant-context'
import { preloadAudio } from '@/assistant/deps'
import { AssistantChat } from '@/components/assistant/chat'
import { AccessCodeForm } from '@/components/demo/access-code-form'

/**
 * The recruiter section (recruiter-demo ticket 07, layout = prototype B
 * "editorial"). Three ways in, three different pages:
 *
 *  - a recruiter's visit (`GET /api/demo/session` says `recruiter`): the page,
 *    with the assistant greeting them;
 *  - a signed-in member (Kevin checking it): the same page as a preview, with
 *    a banner, and the assistant is still his own;
 *  - nobody: the code box and a way back to sign-in, nothing else.
 *
 * The assistant is the member app's own (`AssistantProvider` + `AssistantChat`,
 * the same live client, audio and `/api/assistant/*` doors). Kevin's ruling: one
 * assistant, same code. What a recruiter gets differently is the engine's call
 * by role, never this page's.
 */

/** The hidden first turn that makes the assistant speak first to a visitor (ticket 05). */
export const OPENING_TURN = '(The visitor just joined the demo.)'

/** Ticket 05's suggested first questions. They name Kevin, never the assistant. */
export const VISITOR_SUGGESTIONS = [
  'What did I spend on groceries last month?',
  'When is the car due for service?',
  'Which card charges are unverified?',
  "What's Kevin's experience with Power BI?",
] as const

/**
 * What a visitor's conversation ends with (Kevin, 2026-11-02: five minutes a
 * demo session). The engine's recruiter token expires then
 * (`LEGION_DEMO_MAX_SESSION_MINUTES`); the client ends the conversation at that
 * moment too, because Google only "may" close the socket when a token expires.
 */
export const VISITOR_SESSION_ENDED = 'Demo sessions last 5 minutes. Start another any time.'

export function TryScreen({ code, refTag }: { code?: string; refTag?: string }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [entering, setEntering] = useState(code !== undefined)
  const [linkError, setLinkError] = useState<string | null>(null)
  const started = useRef(false)

  // The resume's direct link. Submitted once (a double effect must not spend
  // two of the per-IP tries), and the code leaves the address bar before the
  // answer is back, so it is never in the history or a copied URL.
  useEffect(() => {
    if (code === undefined || started.current) return
    started.current = true
    void navigate({ to: '/try', search: refTag ? { ref: refTag } : {}, replace: true })
    void enterDemo(code, refTag).then(async (result) => {
      if (result.ok) {
        await queryClient.invalidateQueries()
      } else {
        setLinkError(result.message)
      }
      setEntering(false)
    })
  }, [code, refTag, navigate, queryClient])

  const session = useDemoSession(!entering)
  const me = useMe()
  const recruiter = session.data?.role === 'recruiter'
  const member = me.data?.signedIn === true

  // When the visit runs out, ask again, so the page goes back to the code box
  // instead of showing an assistant that will refuse.
  const expiresAt = recruiter ? session.data?.expires_at : undefined
  useEffect(() => {
    if (!expiresAt) return
    const wait = Date.parse(expiresAt) - Date.now() + 1000
    if (!Number.isFinite(wait)) return
    const timer = setTimeout(() => void queryClient.invalidateQueries({ queryKey: DEMO_SESSION_KEY }), Math.max(wait, 0))
    return () => clearTimeout(timer)
  }, [expiresAt, queryClient])

  if (entering || session.isPending || me.isPending) {
    return (
      <Shell>
        <p role="status" className="py-24 text-center text-muted-foreground">
          {entering ? 'Opening the demo…' : 'Checking your access…'}
        </p>
      </Shell>
    )
  }

  if (recruiter) {
    return <RecruiterPage mode="visitor" expiresAt={session.data?.expires_at} />
  }
  if (member) {
    return <RecruiterPage mode="preview" />
  }

  return (
    <Shell>
      <Gate
        refTag={refTag}
        initialError={linkError}
        lookupError={session.isError ? session.error.message : null}
        onEntered={async () => {
          await queryClient.invalidateQueries()
        }}
      />
    </Shell>
  )
}

function Shell({ children }: { children: ReactNode }) {
  return (
    <div className="recruiter min-h-screen overflow-x-clip">
      <div className="mx-auto max-w-[880px] px-4 sm:px-6">{children}</div>
    </div>
  )
}

function Gate({
  refTag,
  initialError,
  lookupError,
  onEntered,
}: {
  refTag?: string
  initialError: string | null
  lookupError: string | null
  onEntered: () => Promise<void>
}) {
  return (
    <main className="flex min-h-screen flex-col justify-center py-12">
      <h1 className="font-editorial text-[clamp(2rem,6vw,3rem)] leading-tight font-semibold">Try LEGION</h1>
      <p className="mt-3 mb-6 max-w-xl text-lg text-muted-foreground">
        Type the access code from Kevin's resume to open the demo: a voice and text assistant on a
        made-up household. No account needed.
      </p>
      {lookupError && (
        <p role="alert" className="mb-4 text-[0.9375rem] text-destructive">
          Could not ask the engine whether this browser already has a demo visit. {lookupError}
        </p>
      )}
      <div className="max-w-md rounded-card border border-outline-variant bg-card p-5">
        <AccessCodeForm refTag={refTag} initialError={initialError} onEntered={onEntered} />
      </div>
      <p className="mt-6 text-[0.9375rem]">
        Have an account?{' '}
        <Link to="/login" className="inline-flex min-h-11 items-center font-medium text-primary underline-offset-4 hover:underline">
          Sign in
        </Link>
      </p>
    </main>
  )
}

function expiryWords(iso: string | undefined): string | null {
  if (!iso) return null
  const when = new Date(iso)
  if (Number.isNaN(when.getTime())) return null
  return `Your demo access lasts until ${when.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}.`
}

function RecruiterPage({ mode, expiresAt }: { mode: 'visitor' | 'preview'; expiresAt?: string }) {
  const content = useDemoContent(true)
  const expiry = mode === 'visitor' ? expiryWords(expiresAt) : null

  let body: ReactNode
  if (content.isPending) {
    body = (
      <p role="status" className="py-24 text-center text-muted-foreground">
        Loading the page…
      </p>
    )
  } else if (content.isError) {
    body = (
      <p role="alert" className="py-24 text-center">
        Could not load this page. {content.error.message}
      </p>
    )
  } else if (content.data.kind === 'not-set-up') {
    body = (
      <div className="py-24 text-center">
        <h1 className="font-editorial text-3xl font-semibold">This page isn't set up yet</h1>
        <p className="mt-2 text-muted-foreground">Its words have not been written on this engine. Nothing is missing on your side.</p>
      </div>
    )
  } else {
    body = <Sections content={content.data.content} mode={mode} />
  }

  return (
    <Shell>
      {mode === 'preview' && (
        <p
          role="note"
          className="mt-4 flex items-center gap-2 rounded-control bg-primary-container px-4 py-3 text-[0.9375rem] text-primary-container-foreground"
        >
          <Eye className="size-4 flex-none" aria-hidden="true" />
          <span>
            Preview. This is what a visitor with the access code sees; the assistant here is still yours.{' '}
            <Link to="/" className="font-medium underline underline-offset-4">
              Back to your app
            </Link>
          </span>
        </p>
      )}
      {expiry && (
        <p role="note" className="mt-4 text-[0.875rem] text-muted-foreground">
          {expiry}
        </p>
      )}
      {mode === 'visitor' && <ExploreTheApp />}
      {body}
    </Shell>
  )
}

/**
 * The way from this page into the normal app (ticket 13, rulings 1 and 2): the
 * same screens a member uses, on the visitor's own copy of the made-up
 * household. The app's demo banner links back here.
 */
function ExploreTheApp() {
  return (
    <section
      aria-labelledby="explore-the-app"
      className="mt-4 flex flex-wrap items-center justify-between gap-3 rounded-card border border-outline-variant bg-card px-5 py-4"
    >
      <div className="max-w-xl">
        <h2 id="explore-the-app" className="font-semibold">
          Explore the app
        </h2>
        <p className="text-[0.9375rem] text-muted-foreground">
          Money, Fleet, Lists, Calendar, Places and Notes, on your own copy of the made-up household.
          Add or change anything; it stays in your copy and is deleted when your visit ends.
        </p>
      </div>
      <Link
        to="/"
        className="inline-flex min-h-11 items-center rounded-full bg-primary px-5 font-medium text-primary-foreground outline-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
      >
        Explore the app
      </Link>
    </section>
  )
}

function Sections({ content, mode }: { content: DemoContent; mode: 'visitor' | 'preview' }) {
  const { hero, look_for: lookFor, work, experience, links, assets } = content
  return (
    <>
      <header className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 py-4 text-[0.875rem]">
        <span className="font-semibold">LEGION</span>
        <nav aria-label="On this page" className="flex flex-wrap gap-x-4">
          <a href="#talk" className="inline-flex min-h-11 items-center text-primary">Talk</a>
          <a href="#work" className="inline-flex min-h-11 items-center text-primary">Work</a>
          <a href="#experience" className="inline-flex min-h-11 items-center text-primary">Experience</a>
        </nav>
      </header>

      <main>
        <section aria-labelledby="hero-title" className="border-b border-outline-variant pt-8 pb-8 sm:pt-12">
          <h1 id="hero-title" className="font-editorial text-[clamp(2.125rem,6vw,3.5rem)] leading-[1.1] font-semibold break-words">
            {hero.title}
          </h1>
          <p className="font-editorial mt-4 max-w-[640px] text-xl leading-relaxed text-muted-foreground">{hero.lead}</p>
          {hero.facts.length > 0 && (
            <ul aria-label="Facts" className="mt-6 flex flex-wrap gap-2 text-[0.8125rem]">
              {hero.facts.map((fact) => (
                <li key={fact} className="rounded-full border border-outline-variant bg-card px-3 py-1">
                  {fact}
                </li>
              ))}
            </ul>
          )}
        </section>

        <Section id="talk" title="Talk to my assistant">
          <p className="mb-5 text-muted-foreground">
            It runs on LEGION's real engine, against a made-up household. Type, or tap the mic to talk.
          </p>
          <TryAssistant mode={mode} />
        </Section>

        {lookFor.length > 0 && (
          <Section id="look-for" title="What to look for">
            <div className="grid gap-4 sm:grid-cols-3">
              {lookFor.map((item) => (
                <div key={item.title}>
                  <h3 className="font-editorial text-lg font-semibold">{item.title}</h3>
                  <p className="mt-1 text-[0.9375rem] text-muted-foreground">{item.body}</p>
                </div>
              ))}
            </div>
          </Section>
        )}

        <Section id="work" title={work.title}>
          <p className="mb-5 text-muted-foreground">{work.intro}</p>
          {work.stats.length > 0 && (
            <dl className="grid grid-cols-2 gap-3 md:grid-cols-4">
              {work.stats.map((stat) => (
                <div key={stat.label} className="flex flex-col-reverse rounded-xl border border-outline-variant bg-card px-4 py-3">
                  <dt className="text-[0.8125rem] text-muted-foreground">{stat.label}</dt>
                  <dd className="text-[1.75rem] leading-tight font-semibold">{stat.value}</dd>
                </div>
              ))}
            </dl>
          )}
          {work.diagram_svg && <Diagram svg={work.diagram_svg} caption={work.diagram_caption} />}
          {assets.report && (
            <figure className="my-6">
              <h3 className="font-editorial mb-3 text-[1.375rem] font-semibold">Report samples</h3>
              <div className="overflow-hidden rounded-card border border-outline-variant bg-card">
                {/* `allow-scripts` without `allow-same-origin`: the mocks may run their
                    own interactions, and can reach nothing of this page or its cookies. */}
                <iframe
                  title="Report samples"
                  src={assets.report}
                  sandbox="allow-scripts"
                  loading="lazy"
                  referrerPolicy="no-referrer"
                  className="block h-[min(80dvh,56rem)] min-h-[28rem] w-full border-0"
                />
              </div>
              <figcaption className="mt-2 text-[0.8125rem] text-muted-foreground">
                Illustrative mocks with made-up data. It scrolls inside its box.
              </figcaption>
            </figure>
          )}
          {work.cards.length > 0 && (
            <div className="grid gap-4 sm:grid-cols-2">
              {work.cards.map((card) => (
                <div key={card.title} className="rounded-card border border-outline-variant bg-card p-5">
                  <h3 className="font-editorial text-lg font-semibold">{card.title}</h3>
                  <p className="mt-1 text-[0.9375rem] text-muted-foreground">{card.body}</p>
                </div>
              ))}
            </div>
          )}
        </Section>

        <Section id="experience" title="Experience">
          {assets.resume && (
            <p className="mb-5">
              <a
                href={assets.resume}
                className="inline-flex min-h-11 items-center gap-2 font-medium text-primary underline-offset-4 hover:underline"
              >
                <Download className="size-4" aria-hidden="true" />
                Download resume (PDF)
              </a>
            </p>
          )}
          <ol className="flex flex-col gap-5">
            {experience.map((job) => (
              <li key={`${job.when}-${job.title}`} className="grid gap-x-5 gap-y-1 sm:grid-cols-[160px_1fr]">
                <p className="text-[0.875rem] text-muted-foreground">{job.when}</p>
                <div>
                  <h3 className="font-editorial text-lg font-semibold">{job.title}</h3>
                  {job.bullets.length > 0 && (
                    <ul className="mt-1 list-disc pl-5 text-[0.9375rem]">
                      {job.bullets.map((bullet) => (
                        <li key={bullet}>{bullet}</li>
                      ))}
                    </ul>
                  )}
                </div>
              </li>
            ))}
          </ol>
        </Section>
      </main>

      <footer className="flex flex-wrap gap-x-5 py-8 pb-12 text-[0.875rem] text-muted-foreground">
        {links.linkedin && <FooterLink href={links.linkedin}>LinkedIn</FooterLink>}
        {links.github && <FooterLink href={links.github}>GitHub</FooterLink>}
        {links.email && <FooterLink href={`mailto:${links.email}`}>{links.email}</FooterLink>}
      </footer>
    </>
  )
}

function Section({ id, title, children }: { id: string; title: string; children: ReactNode }) {
  return (
    <section id={id} aria-labelledby={`${id}-title`} className="scroll-mt-4 border-b border-outline-variant py-10">
      <h2 id={`${id}-title`} className="font-editorial mb-2 text-[1.75rem] leading-tight font-semibold">
        {title}
      </h2>
      {children}
    </section>
  )
}

function FooterLink({ href, children }: { href: string; children: ReactNode }) {
  return (
    <a href={href} className="inline-flex min-h-11 items-center text-primary underline-offset-4 hover:underline" rel="noreferrer">
      {children}
    </a>
  )
}

/**
 * The diagram is the owner's own SVG, but it is still drawn as an image, never
 * injected as markup: an `<img>` runs no script and reaches nothing in the page,
 * whatever the SVG holds. Wide diagrams scroll inside their box, not the page.
 */
function Diagram({ svg, caption }: { svg: string; caption: string }) {
  const src = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`
  return (
    <figure className="my-6">
      <div role="region" className="overflow-x-auto rounded-card border border-outline-variant bg-card p-3" tabIndex={0} aria-label="Diagram, scrolls sideways">
        <img src={src} alt={caption || 'Diagram'} className="block h-auto w-full min-w-[760px]" />
      </div>
      {caption && <figcaption className="mt-2 text-[0.8125rem] text-muted-foreground">{caption}</figcaption>}
    </figure>
  )
}

/**
 * The member app's assistant, mounted in the page. A visitor's conversation
 * starts with the hidden opening turn so the assistant greets them; a member's
 * preview sends none, as in their own app.
 */
function TryAssistant({ mode }: { mode: 'visitor' | 'preview' }) {
  const inputRef = useRef<HTMLInputElement>(null)
  // The mic tap needs the audio code loaded before it (the tap is the only
  // moment a phone allows the microphone); the member app loads it when the
  // chat opens, and here the chat is open from the start.
  useEffect(() => {
    void preloadAudio().catch(() => {})
  }, [])
  return (
    <AssistantProvider
      openingTurn={mode === 'visitor' ? OPENING_TURN : undefined}
      sessionLimit={mode === 'visitor' ? VISITOR_SESSION_ENDED : undefined}
    >
      <div className="h-[min(36rem,78dvh)] overflow-hidden rounded-card border border-outline-variant bg-card">
        <AssistantChat
          layout="panel"
          inputRef={inputRef}
          subtitle={mode === 'visitor' ? 'Demo assistant, made-up data' : 'Your assistant (preview)'}
          intro={
            mode === 'visitor'
              ? 'Ask about the demo household, or about Kevin. Type, or tap the mic to talk.'
              : undefined
          }
          suggestions={mode === 'visitor' ? VISITOR_SUGGESTIONS : undefined}
        />
      </div>
    </AssistantProvider>
  )
}
