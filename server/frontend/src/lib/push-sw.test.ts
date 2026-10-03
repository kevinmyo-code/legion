import { describe, expect, test, vi } from 'vitest'

import {
  OFF_ACTION,
  OFF_CONFIRMED_TITLE,
  OFF_FAILED_BODY,
  OFF_FAILED_TITLE,
  SETTINGS_URL,
  UNREADABLE_BODY,
  UNREADABLE_TITLE,
  handleNotificationClick,
  handlePush,
  notificationFor,
  readPayload,
  sameOriginUrl,
  type ClickDeps,
  type ClientLike,
} from '@/lib/push-sw'
import swSource from '../sw.ts?raw'

/**
 * The service worker's push and click handlers, as plain functions (jsdom has no
 * service worker). The payloads are exactly the shape `server/push/dispatch.py`
 * sends.
 */

const ORIGIN = 'https://home.example'

const LIST_PUSH = JSON.stringify({
  kind: 'list_changes',
  title: 'Groceries',
  body: 'Kevin added oat milk, eggs and 2 more to Groceries.',
  url: '/lists',
  tag: 'list:abc:2026-10-03T14:00:00Z',
  off: { kind: 'list_changes', label: 'Turn these off' },
})

describe('reading a payload', () => {
  test('takes the engine payload as it is', () => {
    expect(readPayload(LIST_PUSH)).toEqual({
      kind: 'list_changes',
      title: 'Groceries',
      body: 'Kevin added oat milk, eggs and 2 more to Groceries.',
      url: '/lists',
      tag: 'list:abc:2026-10-03T14:00:00Z',
      off: { kind: 'list_changes', label: 'Turn these off' },
    })
  })

  test.each([[null], [''], ['not json'], ['[]'], ['42'], ['{}'], ['{"url":"/lists"}']])(
    'an unreadable payload (%s) still reads as something to show, saying so',
    (text) => {
      const payload = readPayload(text)
      expect(payload.title).toBe(UNREADABLE_TITLE)
      expect(payload.body).toBe(UNREADABLE_BODY)
      expect(payload.off).toBeNull()
    },
  )

  test('a payload with no off block has no silence button, and no kind is invented', () => {
    const payload = readPayload(JSON.stringify({ title: 'Due today', body: '3 things due today: HW 4.', url: '/' }))
    expect(payload.off).toBeNull()
    expect(notificationFor(payload).options.actions).toBeUndefined()
  })
})

describe('the notification a payload becomes', () => {
  test('carries the body, the tag, the url, and a "Turn these off" action', () => {
    const { title, options } = notificationFor(readPayload(LIST_PUSH))
    expect(title).toBe('Groceries')
    expect(options.body).toBe('Kevin added oat milk, eggs and 2 more to Groceries.')
    expect(options.tag).toBe('list:abc:2026-10-03T14:00:00Z')
    expect(options.actions).toEqual([{ action: OFF_ACTION, title: 'Turn these off' }])
    expect(options.data).toEqual({ url: '/lists', off: { kind: 'list_changes', label: 'Turn these off' } })
  })

  test('with no tag it sets none, so it never replaces something unrelated', () => {
    const { options } = notificationFor(readPayload(JSON.stringify({ title: 'T', body: 'B' })))
    expect(options.tag).toBeUndefined()
  })
})

describe('push', () => {
  test('shows the notification, and keeps the worker alive until it is shown', () => {
    const show = vi.fn().mockResolvedValue(undefined)
    const waitUntil = vi.fn()
    handlePush({ data: { text: () => LIST_PUSH }, waitUntil }, show)

    expect(show).toHaveBeenCalledOnce()
    expect(show.mock.calls[0][0]).toBe('Groceries')
    expect(waitUntil).toHaveBeenCalledOnce()
    expect(waitUntil.mock.calls[0][0]).toBeInstanceOf(Promise)
  })

  test('a push with no data at all still shows a notification', () => {
    const show = vi.fn().mockResolvedValue(undefined)
    handlePush({ data: null, waitUntil: vi.fn() }, show)
    expect(show).toHaveBeenCalledWith(UNREADABLE_TITLE, expect.objectContaining({ body: UNREADABLE_BODY }))
  })

  test('data that throws when read still shows a notification', () => {
    const show = vi.fn().mockResolvedValue(undefined)
    handlePush(
      {
        data: {
          text: () => {
            throw new Error('bad')
          },
        },
        waitUntil: vi.fn(),
      },
      show,
    )
    expect(show).toHaveBeenCalledOnce()
  })
})

function client(url: string, overrides: Partial<ClientLike> = {}): ClientLike {
  return { url, focus: vi.fn().mockResolvedValue(undefined), navigate: vi.fn().mockResolvedValue(undefined), ...overrides }
}

function deps(overrides: Partial<ClickDeps> = {}): ClickDeps {
  return {
    origin: ORIGIN,
    fetch: vi.fn().mockResolvedValue({ ok: true, json: async () => ({ detail: 'You will not get list changes any more. Turn them back on in Settings, Notifications.' }) }),
    matchAll: vi.fn().mockResolvedValue([]),
    openWindow: vi.fn().mockResolvedValue(null),
    show: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  }
}

function click(action: string, data: unknown) {
  const closed = vi.fn()
  const waited: Promise<unknown>[] = []
  return {
    event: {
      action,
      notification: { close: closed, data },
      waitUntil: (promise: Promise<unknown>) => void waited.push(promise),
    },
    closed,
    settled: () => Promise.all(waited),
  }
}

const DATA = { url: '/lists', off: { kind: 'list_changes', label: 'Turn these off' } }

describe('tapping the notification', () => {
  test('closes it and opens a new window on the payload page when the app is not open', async () => {
    const d = deps()
    const tap = click('', DATA)
    handleNotificationClick(tap.event, d)
    await tap.settled()

    expect(tap.closed).toHaveBeenCalledOnce()
    expect(d.openWindow).toHaveBeenCalledWith(`${ORIGIN}/lists`)
    expect(d.fetch).not.toHaveBeenCalled()
  })

  test('focuses an open window and takes it to the payload page, rather than opening a second', async () => {
    const open = client(`${ORIGIN}/calendar`)
    const d = deps({ matchAll: vi.fn().mockResolvedValue([open]) })
    const tap = click('', DATA)
    handleNotificationClick(tap.event, d)
    await tap.settled()

    expect(open.focus).toHaveBeenCalledOnce()
    expect(open.navigate).toHaveBeenCalledWith(`${ORIGIN}/lists`)
    expect(d.openWindow).not.toHaveBeenCalled()
  })

  test('an open window already on that page is focused and not navigated', async () => {
    const open = client(`${ORIGIN}/lists`)
    const d = deps({ matchAll: vi.fn().mockResolvedValue([open]) })
    const tap = click('', DATA)
    handleNotificationClick(tap.event, d)
    await tap.settled()
    expect(open.focus).toHaveBeenCalledOnce()
    expect(open.navigate).not.toHaveBeenCalled()
  })

  test('a window of another origin is not reused', async () => {
    const other = client('https://elsewhere.example/lists')
    const d = deps({ matchAll: vi.fn().mockResolvedValue([other]) })
    const tap = click('', DATA)
    handleNotificationClick(tap.event, d)
    await tap.settled()
    expect(other.focus).not.toHaveBeenCalled()
    expect(d.openWindow).toHaveBeenCalledWith(`${ORIGIN}/lists`)
  })

  test('a window that cannot be navigated falls back to opening one', async () => {
    const stuck = client(`${ORIGIN}/calendar`, { navigate: vi.fn().mockRejectedValue(new Error('not controlled')) })
    const d = deps({ matchAll: vi.fn().mockResolvedValue([stuck]) })
    const tap = click('', DATA)
    handleNotificationClick(tap.event, d)
    await tap.settled()
    expect(d.openWindow).toHaveBeenCalledWith(`${ORIGIN}/lists`)
  })

  test('a payload cannot send anyone to another site', () => {
    expect(sameOriginUrl('https://evil.example/phish', ORIGIN)).toBe(`${ORIGIN}/`)
    expect(sameOriginUrl('//evil.example/phish', ORIGIN)).toBe(`${ORIGIN}/`)
    expect(sameOriginUrl('/calendar?day=3', ORIGIN)).toBe(`${ORIGIN}/calendar?day=3`)
    expect(sameOriginUrl(undefined, ORIGIN)).toBe(`${ORIGIN}/`)
    expect(sameOriginUrl('', ORIGIN)).toBe(`${ORIGIN}/`)
  })

  test('notification data that is missing still opens the app', async () => {
    const d = deps()
    const tap = click('', undefined)
    handleNotificationClick(tap.event, d)
    await tap.settled()
    expect(d.openWindow).toHaveBeenCalledWith(`${ORIGIN}/`)
  })
})

describe('"Turn these off"', () => {
  test('posts the kind with the session cookie, then says what the engine said', async () => {
    const d = deps()
    const tap = click(OFF_ACTION, DATA)
    handleNotificationClick(tap.event, d)
    await tap.settled()

    expect(tap.closed).toHaveBeenCalledOnce()
    expect(d.fetch).toHaveBeenCalledWith(
      `${ORIGIN}/api/push/preferences/off`,
      expect.objectContaining({
        method: 'POST',
        credentials: 'same-origin',
        body: JSON.stringify({ kind: 'list_changes' }),
      }),
    )
    expect(d.show).toHaveBeenCalledWith(OFF_CONFIRMED_TITLE, {
      body: 'You will not get list changes any more. Turn them back on in Settings, Notifications.',
      data: { url: SETTINGS_URL, off: null },
    })
    // It does not open the app: the button is the whole interaction.
    expect(d.openWindow).not.toHaveBeenCalled()
  })

  test('says nothing was changed when the engine refuses, never that it worked', async () => {
    const d = deps({ fetch: vi.fn().mockResolvedValue({ ok: false, json: async () => ({}) }) })
    const tap = click(OFF_ACTION, DATA)
    handleNotificationClick(tap.event, d)
    await tap.settled()
    expect(d.show).toHaveBeenCalledOnce()
    expect(vi.mocked(d.show).mock.calls[0][0]).toBe(OFF_FAILED_TITLE)
    expect(vi.mocked(d.show).mock.calls[0][1].body).toBe(OFF_FAILED_BODY)
  })

  test('says nothing was changed when the network is down', async () => {
    const d = deps({ fetch: vi.fn().mockRejectedValue(new TypeError('network error')) })
    const tap = click(OFF_ACTION, DATA)
    handleNotificationClick(tap.event, d)
    await tap.settled()
    expect(vi.mocked(d.show).mock.calls[0][0]).toBe(OFF_FAILED_TITLE)
  })

  test('a 200 with no sentence is not claimed as success either', async () => {
    const d = deps({ fetch: vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) }) })
    const tap = click(OFF_ACTION, DATA)
    handleNotificationClick(tap.event, d)
    await tap.settled()
    expect(vi.mocked(d.show).mock.calls[0][0]).toBe(OFF_FAILED_TITLE)
  })

  test('the failure notification leads to the page where it can be done by hand', () => {
    expect(SETTINGS_URL).toBe('/settings/notifications')
    expect(OFF_FAILED_BODY).toContain('Settings')
    expect(OFF_FAILED_BODY).toContain('Notifications')
  })

  test('the action with no kind in the data does nothing but open the app', async () => {
    const d = deps()
    const tap = click(OFF_ACTION, { url: '/lists' })
    handleNotificationClick(tap.event, d)
    await tap.settled()
    expect(d.fetch).not.toHaveBeenCalled()
    expect(d.openWindow).toHaveBeenCalledWith(`${ORIGIN}/lists`)
  })
})

describe('the words written in the worker', () => {
  test('pass the compulsion test: no absence, streak or app-use language', () => {
    const words = [UNREADABLE_TITLE, UNREADABLE_BODY, OFF_FAILED_TITLE, OFF_FAILED_BODY, OFF_CONFIRMED_TITLE]
    for (const word of words) {
      expect(word).not.toMatch(/haven't|miss|streak|days since|come back/i)
    }
  })
})

/**
 * `src/sw.ts` cannot be imported (it needs a worker), so its non-negotiable rules
 * are guarded by reading it. Crude, and said to be: it fails if a line that keeps
 * the shell fresh or keeps data out of every cache is removed. The generated
 * `sw.js` was compared to the old one by hand when this landed (ticket 15).
 */
describe('src/sw.ts keeps the rules the generated worker had', () => {
  const code = swSource.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')

  test('takes over at once', () => {
    expect(code).toMatch(/self\.skipWaiting\(\)/)
    expect(code).toMatch(/clientsClaim\(\)/)
  })

  test('/api/ is NetworkOnly', () => {
    expect(code).toMatch(/pathname\.startsWith\('\/api\/'\)[^)]*\)?,\s*new NetworkOnly\(\)/)
  })

  test('never caches a response: no cache strategy, no cache write', () => {
    expect(code).not.toMatch(/CacheFirst|StaleWhileRevalidate|NetworkFirst|caches\.open|cache\.put|cache\.add/)
  })

  test('navigations to the paths Django owns are not answered with the shell', () => {
    for (const prefix of ['api', 'admin', 'media', 'static', 'health']) {
      expect(code).toContain(`/^\\/${prefix}/`)
    }
  })

  test('handles push and notificationclick', () => {
    expect(code).toContain("addEventListener('push'")
    expect(code).toContain("addEventListener('notificationclick'")
  })
})
