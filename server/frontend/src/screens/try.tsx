import { useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { Fragment, useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'

import {
  enterDemo,
  useDemoContent,
  useDemoSession,
  type DemoContent,
  type DemoProject,
  type DemoProjectSection,
  type DemoQuality,
  type SectionTheme,
} from '@/api/demo'
import { useMe } from '@/api/queries'
import {
  AssistantProvider,
  ENGINE_QUERY,
  UNNAMED,
  useAssistant,
  useAssistantEngine,
  type ChatCopy,
} from '@/assistant/assistant-context'
import { CascadeClient } from '@/assistant/cascade-client'
import { FamilyAssistant } from '@/components/assistant/family-assistant'
import { AssistantModals } from '@/components/assistant/assistant-modals'
import { usePresenter } from '@/components/assistant/cards/presenter-context'
import { RESUME_ALT } from '@/components/assistant/cards/resume'
import { AssistantDock } from '@/components/assistant/workbench-assistant'
import { AccessCodeForm } from '@/components/demo/access-code-form'
import { EnterWait } from '@/components/demo/enter-wait'
import { StoreRepSection } from '@/components/demo/store-rep/store-rep-section'
import { useSurface } from '@/lib/surface'
import { postFunnel } from '@/assistant/deps'
import { VoiceStage } from '@/components/demo/voice-stage'
import { Media } from '@/screens/try-media'
import { LegionScene } from '@/screens/try-legion-scene'
import { PhoneSection } from '@/screens/try-phone'
import { useOrbPlace, useSectionThemes, useStoryMotion, useStorySections, type OrbSide } from '@/components/demo/scroll-story'

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

/**
 * What a later conversation on the same visit starts with instead (demo-modals: the five-minute
 * cap ended Kevin's first conversation and the next one greeted him again). The engine's prompt
 * says what it means (`assistant/prompt.py` RECRUITER_CONTINUE); a server test pins the match.
 */
export const CONTINUE_TURN = '(The visitor started another conversation on the same visit.)'

/** Ticket 05's suggested first questions. They name Kevin, never the assistant. */
export const VISITOR_SUGGESTIONS = [
  'What did I spend on groceries last month?',
  'When is the car due for service?',
  'Which card charges are unverified?',
  "What's Kevin's experience with Power BI?",
] as const

/**
 * The first questions under the hero's Ask button (Kevin, 2026-11-05): tapping one starts the
 * conversation with it as typed text. One about the made-up household, one about how LEGION is
 * built, so both of the assistant's halves are a tap away.
 */
export const FIRST_QUESTIONS = ['What did this household spend last month?', 'How is LEGION built?'] as const

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
  // The link's code (Kevin, 2026-11-22: the link shows the same full page as a typed code). It
  // leaves the address bar at once, so it is never in the history or a copied URL; the visit is
  // entered as the page opens (`linkEntry` below), and the audio still starts on the orb's tap.
  const [linkCode] = useState(code)
  const [linkError, setLinkError] = useState<string | null>(null)
  const counted = useRef(false)

  useEffect(() => {
    if (!counted.current) {
      counted.current = true
      postFunnel('page_load', refTag)
    }
    if (code !== undefined) void navigate({ to: '/try', search: refTag ? { ref: refTag } : {}, replace: true })
  }, [code, refTag, navigate])

  const session = useDemoSession(true)
  const me = useMe()
  const recruiter = session.data?.role === 'recruiter'
  // A visit's own session is signed in too: a member is someone signed in who is not a visitor.
  // Never decided while the link is entering: entering refetches both, and `me` landing first
  // briefly read the new visitor as a member, which unmounted the orb (review item 2). Nor once a
  // link's visit has ended: `me` may still say signed in until it is asked again.
  const [entering, setEntering] = useState(false)
  const [visitEnded, setVisitEnded] = useState(false)
  const member = me.data?.signedIn === true && !recruiter && !entering && !visitEnded
  // The link's entry: started once on load, and a tap that comes while it runs waits on the same
  // one, so one link never spends two of the per-address tries or seeds two visits. It is dropped
  // when it fails, when the tap waiting on it gives up (its request is aborted), and when the visit
  // ends, so the next tap enters afresh with the link's code.
  const linkEntry = useRef<{ done: Promise<boolean>; abort: AbortController } | null>(null)
  const enterLink = useCallback(
    (signal?: AbortSignal) => {
      let entry = linkEntry.current
      if (!entry) {
        const abort = new AbortController()
        // This entry is the page's own until it is dropped: it is known by its abort controller.
        const mine = () => linkEntry.current?.abort === abort
        const current = () => mine() && !abort.signal.aborted
        const made: { done: Promise<boolean>; abort: AbortController } = {
          abort,
          done: (async () => {
            setEntering(true)
            try {
              const result = await enterDemo(linkCode ?? '', refTag, abort.signal)
              if (!current()) return false
              if (!result.ok) {
                setLinkError(result.message)
                return false
              }
              // The visit's engine before its greeting: Live means the Live page, and no cascade turn.
              const chosen = await queryClient.fetchQuery(ENGINE_QUERY).catch(() => null)
              if (!current()) return false
              await queryClient.invalidateQueries()
              setVisitEnded(false)
              return current() && chosen?.engine !== 'live'
            } finally {
              if (mine() || linkEntry.current === null) setEntering(false)
            }
          })(),
        }
        void made.done.then((ok) => {
          if (!ok && mine()) linkEntry.current = null
        })
        linkEntry.current = made
        entry = made
      }
      const waited = entry
      signal?.addEventListener(
        'abort',
        () => {
          if (linkEntry.current !== waited) return
          waited.abort.abort()
          linkEntry.current = null
        },
        { once: true },
      )
      return waited.done
    },
    [linkCode, refTag, queryClient],
  )
  // A link's visit that ends (its two hours run out) is forgotten: the page waits for a tap, which
  // enters again. Never on its own: an open tab must not renew a visit nobody is using.
  const wasRecruiter = useRef(false)
  useEffect(() => {
    if (session.isPending) return
    if (wasRecruiter.current && !recruiter && linkCode !== undefined) {
      linkEntry.current = null
      setVisitEnded(true)
    }
    wasRecruiter.current = recruiter
  }, [recruiter, session.isPending, linkCode])
  // Entered on load once the page knows who is here, once per page: never over a visit this
  // browser already holds (a second click on the resume link inside the two hours), never for a
  // signed-in member (Kevin checking his own link would be signed out by the visit's login), and
  // only once the page is actually shown: a prerendered or background tab waits until it is looked at.
  const settled = !session.isPending && !me.isPending
  const holdsVisit = recruiter || me.data?.signedIn === true
  const triedOnLoad = useRef(false)
  useEffect(() => {
    if (linkCode === undefined || !settled || holdsVisit || triedOnLoad.current) return
    const go = () => {
      if (document.visibilityState !== 'visible') return false
      triedOnLoad.current = true
      void enterLink()
      return true
    }
    if (go()) return
    const onVisible = () => {
      if (go()) document.removeEventListener('visibilitychange', onVisible)
    }
    document.addEventListener('visibilitychange', onVisible)
    return () => document.removeEventListener('visibilitychange', onVisible)
  }, [linkCode, settled, holdsVisit, enterLink])
  // The engine's switch (ADR 0102): a visit whose engine is Live gets the Live dock page, so a
  // server-side rollback needs no new web build. Asked once a visit exists (it is the visit's own
  // answer); before that, the entry asks it before any greeting (`enterLink` above).
  const engine = useAssistantEngine(recruiter)
  const liveVisit = recruiter && engine.data?.engine === 'live'

  // When the visit runs out, ask again (who is here, too), so the page goes back to the code box,
  // or for a link to a tap that enters again, instead of showing an assistant that will refuse.
  const expiresAt = recruiter ? session.data?.expires_at : undefined
  useEffect(() => {
    if (!expiresAt) return
    const wait = Date.parse(expiresAt) - Date.now() + 1000
    if (!Number.isFinite(wait)) return
    const timer = setTimeout(() => void queryClient.invalidateQueries(), Math.max(wait, 0))
    return () => clearTimeout(timer)
  }, [expiresAt, queryClient])

  const awaitingEngine = recruiter && engine.isPending
  if (!member && !liveVisit && !linkError && !awaitingEngine && (linkCode !== undefined || recruiter)) {
    // Every visitor gets the voice stage and the page under it (Kevin, 2026-11-22: the link shows
    // the same full page as a typed code): a link with its
    // code (entered on load), a reload of a visit, or a code typed in the box. One branch, so
    // entering never remounts the stage.
    return (
      <VoiceFirstVisit
        refTag={refTag}
        entered={recruiter}
        entering={entering}
        ended={visitEnded}
        expiresAt={expiresAt}
        enter={enterLink}
      />
    )
  }

  if (session.isPending || me.isPending || awaitingEngine) {
    return (
      <Shell>
        <p role="status" className="rc-status">
          Checking your access…
        </p>
      </Shell>
    )
  }

  if (recruiter) {
    return <RecruiterPage mode="visitor" expiresAt={session.data?.expires_at} refTag={refTag} />
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
function Shell({
  children,
  assistant = false,
  dock = true,
  voice = false,
}: {
  children: ReactNode
  assistant?: boolean
  /** False on the voice-first page: its stage is the conversation, so no second dock or bar. */
  dock?: boolean
  /** A visitor's voice-first page: the orb flies over it, and its sections keep room for it. */
  voice?: boolean
}) {
  const desk = useSurface() === 'workbench'
  // The page's mood follows the section in view (`useSectionThemes`); the sign-in door has none.
  const theme = useOrbPlace().theme
  const moody = voice || assistant
  const page = (
    <div
      className="recruiter rc-layout"
      data-voice={voice ? 'on' : undefined}
      data-page-theme={moody ? theme : undefined}
    >
      {moody && (
        <div className="rc-ambient" aria-hidden="true">
          <span />
          <span />
          <span />
        </div>
      )}
      <div className="rc-column rc-prose">
        <div className="rc-inner">{children}</div>
        {assistant && dock && !desk && <FamilyAssistant launcher="bar" />}
      </div>
      {assistant && dock && desk && <AssistantDock pinned />}
    </div>
  )
  // The diagrams and the resume open over the whole page (demo-modals), from the conversation.
  return assistant ? <AssistantModals>{page}</AssistantModals> : page
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

/**
 * A visitor's page (voice-first, Kevin 2026-11-17): one provider for the whole page, so the tap on
 * the orb that unlocks the audio is the same conversation that then runs. A link's visit is entered
 * as the page opens (Kevin, 2026-11-22); a tap that comes before that entry has answered waits on
 * the same entry (`enter`), never a second one.
 */
function VoiceFirstVisit({
  refTag,
  entered,
  entering,
  ended,
  expiresAt,
  enter,
}: {
  refTag?: string
  entered: boolean
  /** The entry request is in flight (the wait's clock starts with it, not with the page). */
  entering: boolean
  /** A link's visit ran out: the next tap enters again. */
  ended: boolean
  expiresAt?: string
  enter: (signal: AbortSignal) => Promise<boolean>
}) {
  // A browser that already holds a visit (a reload) is not entered again by the tap.
  const enteredRef = useRef(entered)
  const enterRef = useRef(enter)
  useEffect(() => {
    enteredRef.current = entered
    enterRef.current = enter
  }, [entered, enter])
  const queryClient = useQueryClient()
  const voiceFirst = useMemo(
    () => ({
      prepare: (signal: AbortSignal) => (enteredRef.current ? Promise.resolve(true) : enterRef.current(signal)),
      funnelRef: refTag ?? '',
      sessionMinutes: 5,
      // The server switched this visit to Live mid-visit: ask again, and `TryScreen` swaps pages.
      onEngineLive: () => void queryClient.invalidateQueries({ queryKey: ENGINE_QUERY.queryKey }),
    }),
    [refTag, queryClient],
  )
  return (
    <AssistantProvider
      voiceFirst={voiceFirst}
      continueTurn={CONTINUE_TURN}
      sessionLimit={VISITOR_SESSION_ENDED}
      copy={VISITOR_COPY}
    >
      <VisitorBody entered={entered} entering={entering} ended={ended} expiresAt={expiresAt} />
    </AssistantProvider>
  )
}

/**
 * While a link's visit is being entered: the stage alone, and a line saying so. Then the same
 * stage, with the page under it. One tree for both, so entering never mounts the stage again (the
 * browser run, 2026-11-19: a remount restarted the orb and the page modal's provider with it).
 */
function VisitorBody({
  entered,
  entering,
  ended,
  expiresAt,
}: {
  entered: boolean
  entering: boolean
  ended: boolean
  expiresAt?: string
}) {
  const content = useDemoContent(entered)
  // The visit this conversation entered is over: the next tap enters again (`forgetEntry`).
  const { client } = useAssistant()
  useEffect(() => {
    if (ended && client instanceof CascadeClient) client.forgetEntry()
  }, [ended, client])
  const access = <AccessNote expiry={expiryWords(expiresAt)} />
  const loaded = entered && content.data && content.data.kind !== 'not-set-up' ? content.data.content : null
  return (
    <Shell assistant dock={false} voice>
      <VoiceStage content={loaded} />
      {entered ? (
        loaded ? (
          <Sections content={loaded} access={access} ask={false} />
        ) : (
          access
        )
      ) : ended ? (
        <p role="status" className="rc-status">
          Your demo visit has ended. Tap the orb to start a new one.
        </p>
      ) : entering ? (
        // Mounted with the request, so its clock is the request's: a link opened in a background
        // tab enters only once looked at, and must not show a nearly full bar before it has begun.
        <EnterWait className="rc-status" />
      ) : (
        <p role="status" className="rc-status">
          Opening the demo…
        </p>
      )}
    </Shell>
  )
}

function RecruiterPage({ mode, expiresAt, refTag }: { mode: 'visitor' | 'preview'; expiresAt?: string; refTag?: string }) {
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
      funnelRef={mode === 'visitor' ? (refTag ?? '') : undefined}
      openingTurn={mode === 'visitor' ? OPENING_TURN : undefined}
      continueTurn={mode === 'visitor' ? CONTINUE_TURN : undefined}
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
 * What a visitor's visit is: until when, and that the assistant's changes land only in their own
 * copy. No way into the members' web app from here (Kevin, 2026-11-22: the demo shows the phone
 * and the assistant, not the web app); the expiry sentence is its own element so it can be found
 * and read on its own.
 */
function AccessNote({ expiry }: { expiry: string | null }) {
  return (
    <p className="rc-access">
      {expiry && <span>{expiry}</span>}
      {expiry && ' '}
      Anything the assistant changes happens only in your own copy of the made-up household, and is deleted when
      the visit ends.
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

function Sections({ content, access, ask = true }: { content: DemoContent; access: ReactNode; ask?: boolean }) {
  const { hero, work, experience, links, assets, phone, legion } = content
  const name = content.name?.trim() || 'LEGION'
  const hasPhone = phone !== undefined && phone.clips.length > 0
  const projects = content.projects ?? []
  const projectSections = content.project_sections ?? []
  // The scroll story (components/demo/scroll-story.ts): sections rise in, and the orb rides beside whichever is in
  // view, on alternating sides in reading order. Off under reduced motion: the plain page.
  const motion = useStoryMotion()
  const main = useRef<HTMLElement>(null)
  useStorySections(main, motion, content)
  useSectionThemes(main, content)
  let sideIndex = 0
  const nextSide = (): OrbSide => (sideIndex++ % 2 === 0 ? 'right' : 'left')
  return (
    <>
      <header className="rc-top">
        <span className="rc-caps">{name}</span>
        <nav aria-label="On this page" className="rc-caps">
          {projects.length > 0 && <a href="#projects">Projects</a>}
          {content.legion && <a href="#legion">LEGION</a>}
          {hasPhone && <a href="#phone">Phone</a>}
          {work && <a href="#work">Work</a>}
          <a href="#experience">Experience</a>
        </nav>
      </header>

      <main ref={main} data-story={motion ? 'on' : undefined}>
        <section aria-labelledby="hero-title" className="rc-hero">
          <h1 id="hero-title" className="rc-h1">
            {hero.title}
          </h1>
          <p className="rc-lead">{hero.lead}</p>
          {/* The voice-first page has its stage above: no second way in. */}
          {ask && <AskPrompt />}
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

        {projects.length > 0 && <ProjectsStrip projects={projects} side={nextSide()} />}

        {legion && (
          <Section id="legion" title={legion.title} side={nextSide()} pinned={Boolean(legion.diagram_svg)}>
            <p>{legion.intro}</p>
            {legion.diagram_svg ? (
              // The scrollytelling stage: the diagram pinned, each part's words under the boxes it
              // lights (reduced motion included: the same steps, with instant changes).
              <LegionScene svg={legion.diagram_svg} caption={legion.diagram_caption ?? ''} parts={legion.parts} lead={runIn} />
            ) : (
              legion.parts.length > 0 && (
                <div className="rc-runin">
                  {legion.parts.map((part) => (
                    <p key={part.title}>
                      <b>{runIn(part.title)}</b> {part.body}
                    </p>
                  ))}
                </div>
              )
            )}
            {legion.quality && <Quality quality={legion.quality} />}
          </Section>
        )}

        {hasPhone && <PhoneSection phone={phone} side={nextSide()} />}

        {projectSections.map((section) => (
          <ProjectSection key={section.id} section={section} side={nextSide()} />
        ))}

        {work && (
          <Section id="work" title={work.title} side={nextSide()}>
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
        )}

        <Section id="experience" title="Experience" side={nextSide()}>
          {assets.resume && <ResumeLinks pdf={assets.resume} image={assets.resume_image ?? null} />}
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

function Section({
  id,
  title,
  side,
  pinned = false,
  theme,
  mark,
  children,
}: {
  id: string
  title: string
  /** The side the orb rides on while this section is in view (the scroll story). */
  side?: OrbSide
  /** A pinned scene: the orb rides alone beside it. */
  pinned?: boolean
  /** Its palette (`index.css` `[data-page-theme]`); none is LEGION's. */
  theme?: SectionTheme
  /** The mark the orb takes on beside it. */
  mark?: 'q' | 'larkspur'
  children: ReactNode
}) {
  return (
    <section
      id={id}
      aria-labelledby={`${id}-title`}
      className="rc-section"
      data-orb-side={side}
      data-pinned={pinned ? 'true' : undefined}
      data-theme={theme}
      data-orb-mark={mark}
    >
      <h2 id={`${id}-title`} className="rc-h2">
        {title}
      </h2>
      {children}
    </section>
  )
}

/**
 * The hero's way in (Kevin, 2026-11-05: visitors read the page and never talked to the
 * assistant). One plain ink button that starts a voice conversation, the same as tapping the
 * dock's orb, and two first questions that start it typed. Above the fold on a phone and a desk.
 *
 * The button calls `client.startVoice()` straight from the tap, the only moment an iPhone opens
 * a microphone; at phone width it also raises the sheet so the orb shows what is happening. A
 * question goes in as typed text and unfolds the chat (`focusBox`), so its answer is read there
 * rather than flashing past as a caption. A conversation that already ended starts over.
 */
function AskPrompt() {
  const { client, state, name, ui } = useAssistant()
  const desk = useSurface() === 'workbench'
  const who = name === UNNAMED ? 'the assistant' : name
  const freshIfEnded = () => {
    if (state.phase === 'ended') client.reset()
  }
  const talk = () => {
    freshIfEnded()
    void client.startVoice()
    if (!desk) ui.setOpen(true)
  }
  const ask = (question: string) => {
    freshIfEnded()
    if (client.send(question)) ui.focusBox()
  }
  return (
    <div className="rc-ask">
      <button type="button" className="rc-ask-button" onClick={talk}>
        Ask {who}
      </button>
      <div className="rc-ask-try">
        <span className="rc-caps">Or start with a question</span>
        {FIRST_QUESTIONS.map((question) => (
          <button key={question} type="button" className="rc-ask-q" onClick={() => ask(question)}>
            {question}
          </button>
        ))}
      </div>
      <p className="rc-ask-how">
        {who === 'the assistant' ? 'It' : who} answers about the made-up household, how LEGION is built, and Kevin's
        work. Voice asks your browser for the microphone first.
      </p>
    </div>
  )
}

/** The Projects strip (Kevin, 2026-11-05): one run-in line each, the title linking where it can. */
function ProjectsStrip({ projects, side }: { projects: DemoProject[]; side?: OrbSide }) {
  return (
    <Section id="projects" title="Projects" side={side}>
      <div className="rc-runin">
        {projects.map((project) => (
          <p key={project.title}>
            <b>
              {project.href ? (
                <a href={project.href} rel={project.href.startsWith('#') ? undefined : 'noreferrer'}>
                  {runIn(project.title)}
                </a>
              ) : (
                runIn(project.title)
              )}
            </b>{' '}
            {project.line}
            {project.media?.video && (
              <>
                {' '}
                <a href={project.media.video} rel="noreferrer">
                  Watch the clip
                </a>
                .
              </>
            )}
          </p>
        ))}
      </div>
    </Section>
  )
}

/** A project after LEGION's: the same parts as the LEGION section, plus its media slot. */
function ProjectSection({ section, side }: { section: DemoProjectSection; side?: OrbSide }) {
  return (
    <Section id={section.id} title={section.title} side={side} theme={section.theme} mark={section.orb_mark}>
      <p>{section.intro}</p>
      {section.link && (
        <p className="rc-demo-link">
          {/* A live demo elsewhere: a new tab, and it learns nothing of this page. */}
          <a href={section.link.href} target="_blank" rel="noopener noreferrer">
            {section.link.label}
          </a>
        </p>
      )}
      {/* A live demo this page draws itself (Kevin, 2026-11-22: the store rep as its own section),
          right under the pitch so its control is the first thing to tap. */}
      {section.widget === 'store_rep' && <StoreRepSection />}
      {section.diagram_svg && <Diagram svg={section.diagram_svg} caption={section.diagram_caption ?? ''} />}
      {section.parts.length > 0 && (
        <div className="rc-runin">
          {section.parts.map((part) => (
            <p key={part.title}>
              <b>{runIn(part.title)}</b> {part.body}
            </p>
          ))}
        </div>
      )}
      {section.media && <Media media={section.media} title={section.title} />}
    </Section>
  )
}

/**
 * "Quality for voice agents" (Kevin, 2026-11-22), inside the LEGION section: a row of figures,
 * then the checks behind them as run-in lines. Both are lists, so a new check is one more row.
 */
function Quality({ quality }: { quality: DemoQuality }) {
  return (
    <div className="rc-quality" aria-labelledby="legion-quality-title">
      <h3 id="legion-quality-title" className="rc-h3">
        {quality.title}
      </h3>
      {quality.intro && <p>{quality.intro}</p>}
      {quality.stats.length > 0 && (
        <dl className="rc-tiles">
          {quality.stats.map((stat) => (
            <div key={`${stat.value}-${stat.label}`} className="rc-tile">
              <dt>{stat.value}</dt>
              <dd>{stat.label}</dd>
            </div>
          ))}
        </dl>
      )}
      {quality.checks.length > 0 && (
        <div className="rc-runin">
          {quality.checks.map((check) => (
            <p key={check.title}>
              <b>{runIn(check.title)}</b> {check.body}
            </p>
          ))}
        </div>
      )}
    </div>
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

/**
 * The resume's hands path (ADR 0035: every voice capability has one). "See the resume" opens
 * the same modal `show_resume` opens, through the same presenter; the PDF link stays.
 */
function ResumeLinks({ pdf, image }: { pdf: string; image: string | null }) {
  const presenter = usePresenter()
  return (
    <p className="rc-resume-links">
      {image && presenter && (
        <>
          <button
            type="button"
            className="rc-link-button"
            onClick={() => presenter.show({ card: 'resume', title: "Kevin's resume", image, pdf, alt: RESUME_ALT })}
          >
            See the resume
          </button>
          {' · '}
        </>
      )}
      <a href={pdf}>Download the resume (PDF)</a>
    </p>
  )
}
