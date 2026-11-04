import { useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { Fragment, useEffect, useRef, useState, type ReactNode } from 'react'

import { DEMO_SESSION_KEY, enterDemo, useDemoContent, useDemoSession, type DemoContent } from '@/api/demo'
import { useMe } from '@/api/queries'
import { AssistantProvider, type ChatCopy } from '@/assistant/assistant-context'
import { FamilyAssistant } from '@/components/assistant/family-assistant'
import { AssistantDock } from '@/components/assistant/workbench-assistant'
import { AccessCodeForm } from '@/components/demo/access-code-form'
import { useSurface } from '@/lib/surface'
import { PhoneSection } from '@/screens/try-phone'

/**
 * The recruiter section (recruiter-demo ticket 07). The look is prototype D
 * "typeset", Kevin's pick of 2026-11-03: one serif, set like a printed
 * document, hairline rules and no boxes (`.recruiter` in `index.css` holds
 * every value; this file only names the parts). Three ways in, three
 * different pages:
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
 *
 * ONE chat on the page and ONE place to type (Kevin, 2026-11-03: "keep the
 * right panel window open at all times and remove the talk to my assistant chat
 * window in the middle"). At desk width the members' docked panel is pinned
 * open beside the page, with no close; on a phone a single "Ask" bar pinned to
 * the bottom opens the members' sheet. There is no chat or ask bar in the page
 * itself. One `AssistantProvider` for the whole page, so one live client.
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

/** What the chat says to a visitor, wherever it opens. Module constants: the provider's value stays stable. */
const VISITOR_COPY: ChatCopy = {
  subtitle: 'Demo assistant, made-up data',
  intro:
    "Ask about the demo household, or about Kevin. It runs on LEGION's real engine, against a made-up household. Talk or type. A demo conversation lasts up to 5 minutes.",
  suggestions: VISITOR_SUGGESTIONS,
  voiceFirst: true,
}
const PREVIEW_COPY: ChatCopy = { subtitle: 'Your assistant (preview)', voiceFirst: true }

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
        <p role="status" className="rc-status">
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

/**
 * The page column and, when `assistant` is set, the chat: at desk width the
 * pinned dock beside the column (so nothing is laid out under it), on a phone
 * the Ask bar pinned to the bottom of the column.
 */
function Shell({ children, assistant = false }: { children: ReactNode; assistant?: boolean }) {
  const desk = useSurface() === 'workbench'
  return (
    <div className="recruiter rc-layout">
      <div className="rc-column rc-prose">
        <div className="rc-inner">{children}</div>
        {assistant && !desk && <FamilyAssistant launcher="bar" />}
      </div>
      {assistant && desk && <AssistantDock pinned />}
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
    <main className="rc-door">
      <span className="rc-caps">LEGION</span>
      <h1 className="rc-h1">Try LEGION</h1>
      <p className="rc-access">
        Type the access code from Kevin's resume to open the demo: a voice and text assistant on a made-up
        household. No account needed.
      </p>
      {lookupError && (
        <p role="alert" className="text-destructive">
          Could not ask the engine whether this browser already has a demo visit. {lookupError}
        </p>
      )}
      <AccessCodeForm refTag={refTag} initialError={initialError} onEntered={onEntered} />
      <div className="rc-or">
        <p>
          Have an account?{' '}
          <Link to="/login" className="inline-flex min-h-11 items-center">
            Sign in
          </Link>
        </p>
      </div>
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
  // The way into the app is a visitor's only (ticket 13): a member's preview
  // has no copy of the household to send them to, and no visit to run out.
  const access = mode === 'visitor' ? <AccessNote expiry={expiryWords(expiresAt)} /> : null

  let body: ReactNode
  if (content.isPending) {
    body = (
      <p role="status" className="rc-status">
        Loading the page…
      </p>
    )
  } else if (content.isError) {
    body = (
      <div className="rc-status">
        <p role="alert">Could not load this page. {content.error.message}</p>
        {access}
      </div>
    )
  } else if (content.data.kind === 'not-set-up') {
    body = (
      <div className="rc-status">
        <h1 className="rc-h2">This page isn't set up yet</h1>
        <p>Its words have not been written on this engine. Nothing is missing on your side.</p>
        {access}
      </div>
    )
  } else {
    body = <Sections content={content.data.content} access={access} />
  }

  return (
    <AssistantProvider
      openingTurn={mode === 'visitor' ? OPENING_TURN : undefined}
      sessionLimit={mode === 'visitor' ? VISITOR_SESSION_ENDED : undefined}
      copy={mode === 'visitor' ? VISITOR_COPY : PREVIEW_COPY}
    >
      <Shell assistant>
        {mode === 'preview' && (
          <p role="note" className="rc-note">
            Preview. This is what a visitor with the access code sees; the assistant here is still yours.{' '}
            <Link to="/">Back to your app</Link>
          </p>
        )}
        {body}
      </Shell>
    </AssistantProvider>
  )
}

/**
 * The way from this page into the normal app (ticket 13, rulings 1 and 2): the
 * same screens a member uses, on the visitor's own copy of the made-up
 * household. The app's demo banner links back here. The expiry sentence is its
 * own element so it can be found and read on its own.
 */
function AccessNote({ expiry }: { expiry: string | null }) {
  return (
    <p className="rc-access">
      {expiry && <span>{expiry}</span>}
      {expiry && ' '}
      The app itself is open too: Money, Fleet, Lists, Calendar, Places and Notes, on your own copy of the made-up
      household. Anything you change stays in your copy and is deleted when the visit ends.{' '}
      <Link to="/">Open the app</Link>.
    </p>
  )
}

/** A run-in lead: the title set bold, ending in a full stop unless it has its own. */
function runIn(title: string): string {
  return /[.?!:]$/.test(title) ? title : `${title}.`
}

const COUNT_WORDS = ['One', 'Two', 'Three', 'Four', 'Five', 'Six', 'Seven', 'Eight', 'Nine']

function propertiesHeading(count: number): string {
  const word = COUNT_WORDS[count - 1]
  if (!word) return 'Properties'
  return count === 1 ? 'One property' : `${word} properties`
}

function Sections({ content, access }: { content: DemoContent; access: ReactNode }) {
  const { hero, look_for: lookFor, work, experience, links, assets, phone } = content
  const name = content.name?.trim() || 'LEGION'
  const hasPhone = phone !== undefined && phone.clips.length > 0
  return (
    <>
      <header className="rc-top">
        <span className="rc-caps">{name}</span>
        <nav aria-label="On this page" className="rc-caps">
          {hasPhone && <a href="#phone">Phone</a>}
          <a href="#work">Work</a>
          <a href="#experience">Experience</a>
          <Link to="/">Open the app</Link>
        </nav>
      </header>

      <main>
        <section aria-labelledby="hero-title" className="rc-hero">
          <h1 id="hero-title" className="rc-h1">
            {hero.title}
          </h1>
          <p className="rc-lead">{hero.lead}</p>
          {hero.facts.length > 0 && (
            <p className="rc-dateline">
              {/* One dateline: the facts run on, a middle dot before each but the first (CSS). */}
              {hero.facts.map((fact, index) => (
                <Fragment key={`${index}-${fact}`}>
                  {index > 0 && ' '}
                  <span>{fact}</span>
                </Fragment>
              ))}
            </p>
          )}
          {access}
        </section>

        {lookFor.length > 0 && (
          <Section id="look-for" title="What to look for">
            <div className="rc-runin">
              {lookFor.map((item) => (
                <p key={item.title}>
                  <b>{runIn(item.title)}</b> {item.body}
                </p>
              ))}
            </div>
          </Section>
        )}

        {hasPhone && <PhoneSection phone={phone} />}

        <Section id="work" title={work.title}>
          <p>{work.intro}</p>
          {work.stats.length > 0 && (
            <dl className="rc-figures">
              {work.stats.map((stat) => (
                <Figure key={`${stat.value}-${stat.label}`} value={stat.value} label={stat.label} />
              ))}
            </dl>
          )}
          {work.diagram_svg && <Diagram svg={work.diagram_svg} caption={work.diagram_caption} />}
          {work.cards.length > 0 && (
            <div className="rc-runin">
              <h3 className="rc-h3">{propertiesHeading(work.cards.length)}</h3>
              {work.cards.map((card) => (
                <p key={card.title}>
                  <b>{runIn(card.title)}</b> {card.body}
                </p>
              ))}
            </div>
          )}
          {assets.report && (
            <figure className="rc-figure">
              <div className="rc-report">
                {/* `allow-scripts` without `allow-same-origin`: the mocks may run their
                    own interactions, and can reach nothing of this page or its cookies. */}
                <iframe
                  title="Report samples"
                  src={assets.report}
                  sandbox="allow-scripts"
                  loading="lazy"
                  referrerPolicy="no-referrer"
                />
              </div>
              <figcaption className="rc-figcaption">
                Report samples: illustrative mocks with made-up data. The report scrolls inside its frame.
              </figcaption>
            </figure>
          )}
        </Section>

        <Section id="experience" title="Experience">
          {assets.resume && (
            <p>
              <a href={assets.resume}>Download the resume (PDF)</a>
            </p>
          )}
          <div className="rc-exp">
            {experience.map((job) => (
              <Job key={`${job.when}-${job.title}`} when={job.when} title={job.title} bullets={job.bullets} />
            ))}
          </div>
        </Section>
      </main>

      <footer className="rc-foot">
        {links.linkedin && <FooterLink href={links.linkedin}>LinkedIn</FooterLink>}
        {links.github && <FooterLink href={links.github}>GitHub</FooterLink>}
        {links.email && <FooterLink href={`mailto:${links.email}`}>{links.email}</FooterLink>}
      </footer>
    </>
  )
}

/** One figure: the number, right-aligned in tabular figures, then what it counts. */
function Figure({ value, label }: { value: string; label: string }) {
  return (
    <>
      <dt>{value}</dt>
      <dd>{label}</dd>
    </>
  )
}

/** One job: the dates hang in the left column; the bullets run on as one paragraph. */
function Job({ when, title, bullets }: { when: string; title: string; bullets: string[] }) {
  return (
    <>
      <div className="rc-when">{when}</div>
      <div className="rc-job">
        <h3 className="rc-role">{title}</h3>
        {bullets.length > 0 && <p>{bullets.join(' ')}</p>}
      </div>
    </>
  )
}

function Section({ id, title, children }: { id: string; title: string; children: ReactNode }) {
  return (
    <section id={id} aria-labelledby={`${id}-title`} className="rc-section">
      <h2 id={`${id}-title`} className="rc-h2">
        {title}
      </h2>
      {children}
    </section>
  )
}

function FooterLink({ href, children }: { href: string; children: ReactNode }) {
  return (
    <a href={href} rel="noreferrer">
      {children}
    </a>
  )
}

/**
 * The diagram is the owner's own SVG, but it is still drawn as an image, never
 * injected as markup: an `<img>` runs no script and reaches nothing in the page,
 * whatever the SVG holds. Wide diagrams scroll inside their frame, not the page.
 */
function Diagram({ svg, caption }: { svg: string; caption: string }) {
  const src = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`
  return (
    <figure className="rc-figure">
      <div role="region" className="rc-frame" tabIndex={0} aria-label="Diagram, scrolls sideways">
        <img src={src} alt={caption || 'Diagram'} />
      </div>
      <p className="rc-swipe" aria-hidden="true">
        Swipe sideways for the rest of the diagram.
      </p>
      {caption && <figcaption className="rc-figcaption">{caption}</figcaption>}
    </figure>
  )
}
