import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '@/api/client'
import { runWrite, runWriteData } from '@/api/refusal'
import type { components } from '@/api/schema'
import {
  PushProblem,
  browserTimeZone,
  currentSubscription,
  keyBytes,
  readPermission,
  workerRegistration,
  type BrowserPermission,
} from '@/lib/push-client'

/**
 * Notifications, from this browser to the engine (web-revamp ticket 15, spec D7).
 *
 * Two different things live here and the page keeps them apart: what THIS
 * BROWSER is doing (permission, whether it holds a subscription: read from the
 * browser, never from the engine, which keeps no list of a person's browsers to
 * read back) and what the PERSON wants sent (the three kinds and the morning
 * time: read from and saved to the engine, and the same on every device).
 *
 * Writes are not optimistic. A switch that flipped and then silently flipped back
 * would be a notification setting that lied; each waits for the engine and the
 * switch shows what the engine said.
 */

export type Preference = components['schemas']['Preference']
export type PreferenceKind = 'list_changes' | 'event_reminders' | 'task_due_morning'

export const VAPID_KEY = ['push', 'vapid'] as const
export const PREFERENCES_KEY = ['push', 'preferences'] as const
export const BROWSER_KEY = ['push', 'browser'] as const

export const DENIED_SENTENCE =
  "Notifications are blocked for LEGION in this device's or browser's settings, so nothing was turned on. Allow them there, then reload this page."
export const DISMISSED_SENTENCE =
  'Nothing was turned on: the permission question was closed without an answer.'

/** `GET /api/push/vapid-public-key`: is push set up on this server, and its key. */
export function useVapid() {
  return useQuery({
    queryKey: VAPID_KEY,
    queryFn: async () => {
      const { data, response } = await api.GET('/api/push/vapid-public-key')
      if (!response.ok || !data) throw new Error(`GET /api/push/vapid-public-key answered ${response.status}`)
      return data
    },
    retry: false,
    // Whether an engine has keys changes when someone edits its environment, not
    // while a page is open.
    refetchOnWindowFocus: false,
  })
}

export function usePreferences(enabled: boolean) {
  return useQuery({
    queryKey: PREFERENCES_KEY,
    queryFn: async () => {
      const { data, response } = await api.GET('/api/push/preferences')
      if (!response.ok || !data) throw new Error(`GET /api/push/preferences answered ${response.status}`)
      return data
    },
    enabled,
    retry: false,
  })
}

export function useSavePreferences() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (next: Preference) => {
      const saved = await runWriteData('saved', () => api.PUT('/api/push/preferences', { body: next }))
      if (!saved) throw new Error('The engine accepted the change but sent nothing back.')
      return saved
    },
    // What the engine now holds, not what was asked for.
    onSuccess: (saved) => queryClient.setQueryData(PREFERENCES_KEY, saved),
  })
}

export interface BrowserState {
  permission: BrowserPermission
  /** This browser holds a push subscription. */
  subscribed: boolean
}

/** Read from the browser. Re-read on focus, because permission is changed in
 * browser settings, in another window, while this page stays open. */
export function useBrowserState(enabled: boolean) {
  return useQuery({
    queryKey: BROWSER_KEY,
    queryFn: async (): Promise<BrowserState> => {
      const permission = readPermission()
      if (permission !== 'granted') return { permission, subscribed: false }
      return { permission, subscribed: (await currentSubscription()) !== null }
    },
    enabled,
    retry: false,
    refetchOnWindowFocus: 'always',
  })
}

/**
 * Turn notifications on for this browser: ask permission, subscribe with the
 * engine's key, tell the engine. In that order, and undone in reverse if the
 * last step fails, so the browser is never left subscribed to something the
 * engine does not know about (it would hold a delivery route to nobody).
 */
export function useSubscribeBrowser(publicKey: string | null) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async () => {
      if (publicKey === null) {
        throw new PushProblem('Notifications are not set up on this server. Nothing was turned on.')
      }
      const answer = await window.Notification.requestPermission()
      if (answer === 'denied') throw new PushProblem(DENIED_SENTENCE)
      if (answer !== 'granted') throw new PushProblem(DISMISSED_SENTENCE)

      const registration = await workerRegistration()
      let subscription: PushSubscription
      try {
        subscription = await registration.pushManager.subscribe({
          userVisibleOnly: true,
          applicationServerKey: keyBytes(publicKey),
        })
      } catch (error) {
        const why = error instanceof Error ? ` ${error.message}` : ''
        throw new PushProblem(`This browser would not subscribe, so nothing was turned on.${why}`)
      }

      const json = subscription.toJSON()
      if (!json.endpoint || !json.keys?.p256dh || !json.keys.auth) {
        await subscription.unsubscribe().catch(() => false)
        throw new PushProblem('This browser gave back an incomplete subscription, so nothing was turned on.')
      }
      const { endpoint, keys } = json as { endpoint: string; keys: { p256dh: string; auth: string } }
      try {
        await runWrite('saved', () =>
          api.POST('/api/push/subscriptions', {
            body: {
              endpoint,
              keys: { p256dh: keys.p256dh, auth: keys.auth },
              user_agent: navigator.userAgent,
              tz: browserTimeZone(),
            },
          }),
        )
      } catch (error) {
        await subscription.unsubscribe().catch(() => false)
        throw error
      }
    },
    onSettled: async () => {
      await queryClient.invalidateQueries({ queryKey: BROWSER_KEY })
      await queryClient.invalidateQueries({ queryKey: PREFERENCES_KEY })
    },
  })
}

/**
 * Turn notifications off for this browser. The engine is told FIRST and the
 * browser unsubscribes only once it has said yes, so a failure leaves both sides
 * agreeing that this device is still subscribed (and the page saying so), rather
 * than a browser that stopped listening while the engine keeps sending.
 *
 * The engine lists no subscriptions and the page keeps no id, so it learns this
 * browser's id by sending the subscription again: the engine answers a repeat
 * with the same row (200) rather than a second one.
 */
export function useUnsubscribeBrowser(publicKey: string | null) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async () => {
      const registration = await workerRegistration()
      const subscription = await registration.pushManager.getSubscription()
      if (!subscription) return
      const json = subscription.toJSON()
      if (!json.endpoint || !json.keys?.p256dh || !json.keys.auth || publicKey === null) {
        throw new PushProblem('Nothing was turned off: this browser would not say which subscription it holds.')
      }
      const known = await runWriteData('removed', () =>
        api.POST('/api/push/subscriptions', {
          body: {
            endpoint: json.endpoint as string,
            keys: { p256dh: json.keys!.p256dh, auth: json.keys!.auth },
            user_agent: navigator.userAgent,
            tz: browserTimeZone(),
          },
        }),
      )
      if (!known) throw new PushProblem('Nothing was turned off: the engine did not say which subscription this is.')
      await runWrite('removed', () =>
        api.DELETE('/api/push/subscriptions/{subscription_id}', {
          params: { path: { subscription_id: known.id } },
        }),
      )
      if (!(await subscription.unsubscribe())) {
        throw new PushProblem(
          'The engine will send nothing more here, but this browser would not drop its subscription.',
        )
      }
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: BROWSER_KEY }),
  })
}
