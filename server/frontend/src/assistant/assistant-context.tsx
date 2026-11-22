import { useQuery } from '@tanstack/react-query'
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
  type ReactNode,
} from 'react'

import { api } from '@/api/client'
import { useSurface } from '@/lib/surface'

import { CascadeClient, type CascadeDeps } from './cascade-client'
import type { AssistantClient } from './client'
import { createCascadeDeps, createLiveDeps, preloadAudio, type VoiceSurface } from './deps'
import { LiveClient, type AssistantState, type LiveDeps } from './live-client'

/**
 * Where the one conversation lives for the whole signed-in app.
 *
 * `AppShell` mounts this, so the client survives navigating between screens
 * (the chat does not reset when you tap a tab) and dies with the shell (signing
 * out, closing the page). Two views read it: the family orb and sheet, and the
 * workbench's docked panel. Which of them is showing is `useSurface`; the
 * conversation is the same object either way, so resizing a window across 1024 px
 * does not drop it.
 *
 * Open or closed is held in memory only. Nothing about a conversation, not even
 * that the panel was open, is written to storage (web-assistant map: history is
 * this session only).
 */

/** What the rail and the orb call the assistant before the engine has named it in a session. */
export const UNNAMED = 'Assistant'

interface AssistantUi {
  open: boolean
  setOpen(open: boolean): void
  /** Bumps each time something asks for the message box to take focus ("/" on the workbench). */
  focusTick: number
  focusBox(): void
}

/** What a page may say in the chat in place of the household defaults (the recruiter page). */
export interface ChatCopy {
  /** The line under the companion's name. */
  subtitle?: string
  /** What the empty conversation says it can be asked about. */
  intro?: string
  /** The tappable first questions. */
  suggestions?: readonly string[]
  /**
   * The desk dock shows `VoiceFirstChat`: the orb is the star and typing folds
   * under it (Kevin, 2026-11-04, the recruiter page). The phone sheet is unchanged.
   */
  voiceFirst?: boolean
}

interface AssistantContextValue {
  client: AssistantClient
  ui: AssistantUi
  /** The page's chat copy, if it set any; `AssistantChat` falls back to its own. */
  copy?: ChatCopy
  /** The member's companion name: the session's if one has been minted, else the engine's read. */
  name: string
}

const AssistantContext = createContext<AssistantContextValue | null>(null)

export function useCompanionRead() {
  return useQuery({
    queryKey: ['assistant', 'companion'],
    queryFn: async () => {
      const { data, response } = await api.GET('/api/assistant/companion')
      if (!data) throw new Error(`GET /api/assistant/companion answered ${response.status}`)
      return data
    },
    retry: false,
    staleTime: Infinity,
  })
}

/**
 * Which engine this caller's assistant runs on (ADR 0102), and what the cascade needs to know.
 * The server decides per role; the other engine's door refuses.
 */
export const ENGINE_QUERY = {
  queryKey: ['assistant', 'engine'],
  queryFn: async () => {
    const { data, response } = await api.GET('/api/assistant/session')
    if (!data) throw new Error(`GET /api/assistant/session answered ${response.status}`)
    return data
  },
  retry: false,
  staleTime: Infinity,
} as const

export function useAssistantEngine(enabled = true) {
  return useQuery({ ...ENGINE_QUERY, enabled })
}

export function AssistantProvider({
  children,
  deps,
  cascadeDeps,
  openingTurn,
  continueTurn,
  sessionLimit,
  copy,
  defaultOpen = false,
  voiceFirst,
  funnelRef,
  voiceSurface = 'demo',
}: {
  children: ReactNode
  deps?: LiveDeps
  /** Tests: stand in for the engine's turn route when the engine answers `cascade`. */
  cascadeDeps?: CascadeDeps
  /** Opt-in: a hidden first turn so the assistant speaks first (`LiveOptions.openingTurn`). */
  openingTurn?: string
  /** Opt-in: what a later conversation on the same page starts with instead (`LiveOptions.continueTurn`). */
  continueTurn?: string
  /** Opt-in: end each conversation when its token expires, with this sentence (`LiveOptions.sessionLimit`). */
  sessionLimit?: string
  /** Opt-in: the chat's subtitle, intro and first questions, wherever it opens (dock or sheet). */
  copy?: ChatCopy
  /**
   * Opt-in: the desk's docked chat starts open (Kevin, 2026-11-03: one place to
   * type, and it is the dock). Desk width only: at phone width "open" is the
   * full-screen sheet, which must never pop up on its own.
   */
  defaultOpen?: boolean
  /**
   * The demo's voice-first door (Kevin, 2026-11-17): the conversation is the engine's cascade from
   * the first tap, before any visit exists to ask which engine; `prepare` enters the visit inside
   * that tap, and `funnelRef` tags the page's funnel counts.
   */
  voiceFirst?: {
    prepare: (signal: AbortSignal) => Promise<boolean>
    funnelRef?: string
    sessionMinutes?: number | null
    /** The engine answered `live_engine`: ask it again (the page then shows the Live page). */
    onEngineLive?: () => void
  }
  /** The demo's ref tag: when set, the conversation counts its funnel steps under it. */
  funnelRef?: string
  /**
   * Which door this is, for the phone's turn-taking on the web (`deps.ts` TALK_OVER_DEFAULT). The
   * members' shell says `members`; anything that does not say is the demo, which keeps it off.
   */
  voiceSurface?: VoiceSurface
}) {
  const surface = useSurface()
  const surfaceRef = useRef(surface)
  useEffect(() => {
    surfaceRef.current = surface
  }, [surface])

  // A phone ends a conversation when the page is hidden (a screen lock or an
  // app switch kills the mic and the socket there anyway, web-assistant 02). A
  // desk tab switch must not: Kevin reading another tab is not a lock.
  const openingRef = useRef(openingTurn)
  useEffect(() => {
    openingRef.current = openingTurn
  }, [openingTurn])
  const continueRef = useRef(continueTurn)
  useEffect(() => {
    continueRef.current = continueTurn
  }, [continueTurn])
  const limitRef = useRef(sessionLimit)
  useEffect(() => {
    limitRef.current = sessionLimit
  }, [sessionLimit])
  const engine = useAssistantEngine(!voiceFirst)
  const voiceFirstRef = useRef(voiceFirst)
  const [client, setClient] = useState<AssistantClient>(() =>
    voiceFirstRef.current
      ? new CascadeClient(cascadeDeps ?? createCascadeDeps({ funnelRef: voiceFirstRef.current.funnelRef, surface: 'demo' }), {
          companionName: null,
          sessionMinutes: voiceFirstRef.current.sessionMinutes ?? null,
          endOnHidden: () => surfaceRef.current === 'family',
          openingTurn: () => openingRef.current ?? null,
          continueTurn: () => continueRef.current ?? null,
          sessionLimit: () => limitRef.current ?? null,
          prepare: (signal) => voiceFirstRef.current!.prepare(signal),
          speakTyped: true,
          ...(voiceFirstRef.current.onEngineLive ? { onEngineLive: () => voiceFirstRef.current?.onEngineLive?.() } : {}),
        })
      : new LiveClient(deps ?? createLiveDeps(), {
        endOnHidden: () => surfaceRef.current === 'family',
        openingTurn: () => openingRef.current ?? null,
        continueTurn: () => continueRef.current ?? null,
        sessionLimit: () => limitRef.current ?? null,
      }),
  )
  // The engine answered `cascade`: the conversation runs on the engine's own voice pipeline
  // (ADR 0102). Swapped once, before anything was said (the answer arrives as the page mounts).
  const cascadeInfo = engine.data?.engine === 'cascade' ? engine.data : null
  useEffect(() => {
    if (!cascadeInfo) return
    setClient((current) => {
      if (current instanceof CascadeClient) return current
      const next: AssistantClient = new CascadeClient(cascadeDeps ?? createCascadeDeps({ funnelRef, surface: voiceSurface }), {
        companionName: cascadeInfo.companion_name,
        sessionMinutes: cascadeInfo.session_minutes ?? null,
        endOnHidden: () => surfaceRef.current === 'family',
        openingTurn: () => openingRef.current ?? null,
        continueTurn: () => continueRef.current ?? null,
        sessionLimit: () => limitRef.current ?? null,
      })
      return next
    })
  }, [cascadeInfo, cascadeDeps, funnelRef, voiceSurface])
  useEffect(() => {
    client.attach()
    return () => client.dispose()
  }, [client])
  // The mic tap must open the audio synchronously (iPhone rule, `LiveClient.startVoice`), so the
  // audio chunk has to be loaded before the tap. Opening the chat loaded it, but a pinned dock
  // (the recruiter page) is never "opened", and the mic failed with "couldn't be opened"
  // (Kevin, 2026-11-03). Load it as soon as an assistant is on the page.
  useEffect(() => {
    void preloadAudio().catch(() => {})
  }, [])

  const [open, setOpenState] = useState(() => defaultOpen && surface === 'workbench')
  // Crossing 1024 px swaps dock for sheet. The sheet must not open itself on the
  // way down; on the way back up a default-open dock opens again.
  const lastSurface = useRef(surface)
  useEffect(() => {
    if (lastSurface.current === surface) return
    lastSurface.current = surface
    setOpenState(defaultOpen && surface === 'workbench')
  }, [surface, defaultOpen])
  const [focusTick, setFocusTick] = useState(0)
  const setOpen = useCallback((next: boolean) => {
    setOpenState(next)
    if (next) void preloadAudio().catch(() => {})
  }, [])
  const focusBox = useCallback(() => {
    setOpen(true)
    setFocusTick((tick) => tick + 1)
  }, [setOpen])

  const state = useSyncExternalStore(client.subscribe, client.getState)
  const read = useCompanionRead()
  const name = state.companion ?? read.data?.name ?? UNNAMED

  const value = useMemo<AssistantContextValue>(
    () => ({ client, ui: { open, setOpen, focusTick, focusBox }, name, copy }),
    [client, open, setOpen, focusTick, focusBox, name, copy],
  )
  return <AssistantContext.Provider value={value}>{children}</AssistantContext.Provider>
}

function useAssistantContext(): AssistantContextValue {
  const value = useContext(AssistantContext)
  if (!value) throw new Error('useAssistant must be used inside AssistantProvider.')
  return value
}

/** The conversation, its state, the companion's name and the open/closed switch. */
export function useAssistant(): { state: AssistantState } & AssistantContextValue {
  const value = useAssistantContext()
  const state = useSyncExternalStore(value.client.subscribe, value.client.getState)
  return { ...value, state }
}
