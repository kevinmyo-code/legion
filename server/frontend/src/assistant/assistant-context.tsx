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

import { createLiveDeps, preloadAudio } from './deps'
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
const UNNAMED = 'Assistant'

interface AssistantUi {
  open: boolean
  setOpen(open: boolean): void
  /** Bumps each time something asks for the message box to take focus ("/" on the workbench). */
  focusTick: number
  focusBox(): void
}

interface AssistantContextValue {
  client: LiveClient
  ui: AssistantUi
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

export function AssistantProvider({ children, deps }: { children: ReactNode; deps?: LiveDeps }) {
  const surface = useSurface()
  const surfaceRef = useRef(surface)
  useEffect(() => {
    surfaceRef.current = surface
  }, [surface])

  // A phone ends a conversation when the page is hidden (a screen lock or an
  // app switch kills the mic and the socket there anyway, web-assistant 02). A
  // desk tab switch must not: Kevin reading another tab is not a lock.
  const [client] = useState(
    () => new LiveClient(deps ?? createLiveDeps(), { endOnHidden: () => surfaceRef.current === 'family' }),
  )
  useEffect(() => {
    client.attach()
    return () => client.dispose()
  }, [client])

  const [open, setOpenState] = useState(false)
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
    () => ({ client, ui: { open, setOpen, focusTick, focusBox }, name }),
    [client, open, setOpen, focusTick, focusBox, name],
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
