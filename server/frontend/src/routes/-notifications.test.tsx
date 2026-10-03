import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import { DENIED_SENTENCE, DISMISSED_SENTENCE } from '@/api/push'
import { SETTINGS_SECTIONS } from '@/lib/settings-nav'
import { NOTIFICATION_COPY } from '@/screens/settings/notifications'
import { ME, createEngine, seedHousehold } from '@/test/engine'
import { ENDPOINT, stubPushBrowser, type PushBrowserOptions } from '@/test/push-browser'
import { renderApp } from '@/test/render-app'

/**
 * `/settings/notifications` (ticket 15): the iOS install card, subscribe and
 * unsubscribe, the three kinds, the morning time, and the master off. The browser
 * is a stub (`src/test/push-browser.ts`) because jsdom has none; the engine is the
 * fake one speaking the real server's sentences.
 */

let browser: ReturnType<typeof stubPushBrowser> | null = null

afterEach(() => {
  browser?.restore()
  browser = null
  vi.unstubAllGlobals()
})

function setUp(options: PushBrowserOptions = {}, settings = {}) {
  browser = stubPushBrowser({ permission: 'default', ...options })
  const engine = createEngine({
    ...seedHousehold(),
    members: [
      { role: 'member', user_id: ME.user_id, email: ME.email, name: 'Mia', joined_at: '2026-09-01T12:00:00Z' },
    ],
    settings,
  })
  return engine
}

async function open(engine: ReturnType<typeof setUp>, surface: 'family' | 'workbench' = 'family') {
  const view = renderApp('/settings/notifications', engine, surface)
  await screen.findByRole('heading', { name: 'Notifications' })
  return view
}

// The engine's own copy test greps for these (`server/push/copy.py`); `miss` so that
// "permission" and "dismissed" are not mistaken for "missed".
const BANNED = /haven't|miss|streak|days since|come back/i

describe('an iPhone outside the installed app', () => {
  test('shows the three-step install card instead of the subscribe button', async () => {
    const engine = setUp({ ios: true, standalone: false })
    await open(engine)

    expect(await screen.findByText(NOTIFICATION_COPY.iosTitle)).toBeInTheDocument()
    const steps = within(screen.getByRole('list', { name: 'How to add LEGION to the Home Screen' })).getAllByRole('listitem')
    expect(steps).toHaveLength(3)
    expect(steps[0]).toHaveTextContent('Share button')
    expect(steps[1]).toHaveTextContent('Add to Home Screen')
    expect(steps[2]).toHaveTextContent('Open LEGION from its new icon')
    expect(screen.getByText(/iOS 16\.4 or later/)).toBeInTheDocument()

    expect(screen.queryByRole('button', { name: /Turn on notifications/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('switch')).not.toBeInTheDocument()
  })

  test('once installed (standalone) it is an ordinary browser with a subscribe button', async () => {
    const engine = setUp({ ios: true, standalone: true })
    await open(engine)
    expect(await screen.findByRole('button', { name: 'Turn on notifications on this device' })).toBeInTheDocument()
    expect(screen.queryByText(NOTIFICATION_COPY.iosTitle)).not.toBeInTheDocument()
  })
})

describe('when notifications cannot be had', () => {
  test('a server with no keys says so in the one sentence, and offers nothing', async () => {
    const engine = setUp({}, { push: { ...createEngine().settings.push, enabled: false } })
    await open(engine)

    expect(await screen.findByText('Notifications are not set up on this server.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Turn on notifications/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('switch')).not.toBeInTheDocument()
  })

  test('an engine that could not be reached is not "not set up"', async () => {
    const engine = setUp()
    engine.refusals['GET /api/push/vapid-public-key'] = { status: 503, body: { detail: 'down' } }
    await open(engine)

    expect(await screen.findByText(/Could not reach the engine, so this page cannot say whether notifications are set up\./)).toBeInTheDocument()
    expect(screen.queryByText('Notifications are not set up on this server.')).not.toBeInTheDocument()
  })

  test('a browser with no push says so', async () => {
    const engine = setUp({ unsupported: true })
    await open(engine)
    expect(await screen.findByText(NOTIFICATION_COPY.unsupported)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Turn on notifications/ })).not.toBeInTheDocument()
  })

  test('a permission the browser has denied is its own sentence, with no button that cannot work', async () => {
    const engine = setUp({ permission: 'denied' })
    await open(engine)

    expect(await screen.findByText(DENIED_SENTENCE)).toBeInTheDocument()
    expect(screen.getByText(/Allow them there, then reload this page\./)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Turn on notifications/ })).not.toBeInTheDocument()
    expect(browser!.requestPermission).not.toHaveBeenCalled()
  })
})

describe('turning this device on', () => {
  test('asks permission, subscribes with the engine key, and posts the subscription with the time zone', async () => {
    const engine = setUp()
    await open(engine)

    fireEvent.click(await screen.findByRole('button', { name: 'Turn on notifications on this device' }))
    expect(await screen.findByText(NOTIFICATION_COPY.deviceOn)).toBeInTheDocument()

    expect(browser!.requestPermission).toHaveBeenCalledOnce()
    expect(browser!.pushManager.subscribe).toHaveBeenCalledOnce()
    const options = browser!.pushManager.subscribe.mock.calls[0][0]
    expect(options.userVisibleOnly).toBe(true)
    // The engine's public key, decoded: a 65-byte uncompressed P-256 point.
    expect(options.applicationServerKey).toBeInstanceOf(Uint8Array)
    expect(options.applicationServerKey).toHaveLength(65)

    expect(engine.writes).toHaveLength(1)
    expect(engine.writes[0]).toEqual({
      method: 'POST',
      pathname: '/api/push/subscriptions',
      body: {
        endpoint: ENDPOINT,
        keys: { p256dh: 'p256dh-key', auth: 'auth-secret' },
        user_agent: navigator.userAgent,
        tz: Intl.DateTimeFormat().resolvedOptions().timeZone,
      },
    })
    expect(engine.settings.push.subscriptions.map((sub) => sub.endpoint)).toEqual([ENDPOINT])
    expect(engine.settings.push.subscriptions[0].tz).toBe('America/Chicago')
    expect(screen.getByRole('button', { name: 'Turn off on this device' })).toBeInTheDocument()
  })

  test('a permission question that was closed without an answer turns nothing on', async () => {
    const engine = setUp({ answer: 'default' })
    await open(engine)
    fireEvent.click(await screen.findByRole('button', { name: 'Turn on notifications on this device' }))

    expect(await screen.findByText(DISMISSED_SENTENCE)).toBeInTheDocument()
    expect(browser!.pushManager.subscribe).not.toHaveBeenCalled()
    expect(engine.writes).toEqual([])
  })

  test('a permission refused at the question says it is blocked, and turns nothing on', async () => {
    const engine = setUp({ answer: 'denied' })
    await open(engine)
    fireEvent.click(await screen.findByRole('button', { name: 'Turn on notifications on this device' }))

    expect(await screen.findAllByText(DENIED_SENTENCE)).not.toHaveLength(0)
    expect(engine.writes).toEqual([])
  })

  test('a browser that will not subscribe says nothing was turned on and posts nothing', async () => {
    const engine = setUp({ subscribeError: new Error('Registration failed - push service error') })
    await open(engine)
    fireEvent.click(await screen.findByRole('button', { name: 'Turn on notifications on this device' }))

    expect(await screen.findByText(/This browser would not subscribe, so nothing was turned on\. Registration failed/)).toBeInTheDocument()
    expect(engine.writes).toEqual([])
  })

  test('an engine that refuses the subscription leaves the browser unsubscribed again, and says what did not happen', async () => {
    const engine = setUp()
    engine.refusals['POST /api/push/subscriptions'] = {
      status: 503,
      body: { detail: 'Notifications are not set up on this server. Nothing was saved.' },
    }
    await open(engine)
    fireEvent.click(await screen.findByRole('button', { name: 'Turn on notifications on this device' }))

    expect(await screen.findByText('Notifications are not set up on this server. Nothing was saved.')).toBeInTheDocument()
    await waitFor(() => expect(browser!.subscription.unsubscribe).toHaveBeenCalledOnce())
    expect(browser!.held).toBe(false)
    expect(screen.getByRole('button', { name: 'Turn on notifications on this device' })).toBeInTheDocument()
  })

  test('a worker that never becomes ready is said in words', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      const engine = setUp({ neverReady: true })
      await open(engine)
      fireEvent.click(await screen.findByRole('button', { name: 'Turn on notifications on this device' }))
      await vi.advanceTimersByTimeAsync(6000)
      expect(await screen.findByText(/Could not start the notification worker in this browser, so nothing was turned on\./)).toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('turning this device off', () => {
  test('tells the engine first, then the browser, and says the device is off', async () => {
    const engine = setUp({ subscribed: true, permission: 'granted' })
    engine.settings.push.subscriptions.push({ id: '00000000-0000-4000-8000-0000000000aa', endpoint: ENDPOINT, tz: 'America/Chicago' })
    await open(engine)

    fireEvent.click(await screen.findByRole('button', { name: 'Turn off on this device' }))
    expect(await screen.findByText(NOTIFICATION_COPY.deviceOff)).toBeInTheDocument()

    // The engine is asked which subscription this is (a repeat is answered with
    // the same row), deleted, and only then does the browser drop it.
    expect(engine.writes.map((write) => `${write.method} ${write.pathname}`)).toEqual([
      'POST /api/push/subscriptions',
      'DELETE /api/push/subscriptions/00000000-0000-4000-8000-0000000000aa',
    ])
    expect(engine.settings.push.subscriptions).toEqual([])
    expect(browser!.subscription.unsubscribe).toHaveBeenCalledOnce()
  })

  test('an engine that refuses leaves the browser subscribed and says nothing was removed', async () => {
    const engine = setUp({ subscribed: true, permission: 'granted' })
    engine.settings.push.subscriptions.push({ id: '00000000-0000-4000-8000-0000000000aa', endpoint: ENDPOINT, tz: 'UTC' })
    engine.refusals['DELETE /api/push/subscriptions/*'] = {
      status: 404,
      body: { detail: 'No subscription of yours has that id. Nothing was removed.' },
    }
    await open(engine)
    fireEvent.click(await screen.findByRole('button', { name: 'Turn off on this device' }))

    expect(await screen.findByText('No subscription of yours has that id. Nothing was removed.')).toBeInTheDocument()
    expect(browser!.subscription.unsubscribe).not.toHaveBeenCalled()
    expect(screen.getByText(NOTIFICATION_COPY.deviceOn)).toBeInTheDocument()
  })
})

describe('what to send', () => {
  test('shows the three kinds as the engine holds them', async () => {
    const engine = setUp()
    engine.settings.push.preferences.event_reminders = false
    await open(engine)

    expect(await screen.findByRole('switch', { name: 'List changes' })).toBeChecked()
    expect(screen.getByRole('switch', { name: 'Event reminders' })).not.toBeChecked()
    expect(screen.getByRole('switch', { name: 'Due today' })).toBeChecked()
    expect(screen.getByLabelText('Morning time')).toHaveValue('07:30')
  })

  test('a switch saves the whole preference and then shows what the engine now holds', async () => {
    const engine = setUp()
    await open(engine)

    fireEvent.click(await screen.findByRole('switch', { name: 'List changes' }))
    expect(await screen.findByText('List changes turned off.')).toBeInTheDocument()
    expect(engine.writes.at(-1)).toEqual({
      method: 'PUT',
      pathname: '/api/push/preferences',
      body: { list_changes: false, event_reminders: true, task_due_morning: true, morning_time: '07:30' },
    })
    expect(screen.getByRole('switch', { name: 'List changes' })).not.toBeChecked()
  })

  test('a refused save keeps the switch where it was and says nothing was saved', async () => {
    const engine = setUp()
    engine.refusals['PUT /api/push/preferences'] = { status: 400, body: { detail: 'Nothing was saved: the engine is read-only right now.' } }
    await open(engine)

    fireEvent.click(await screen.findByRole('switch', { name: 'Event reminders' }))
    expect(await screen.findByText('Nothing was saved: the engine is read-only right now.')).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: 'Event reminders' })).toBeChecked()
    expect(screen.queryByText(/turned off\./)).not.toBeInTheDocument()
  })

  test('saves the morning time', async () => {
    const engine = setUp()
    await open(engine)

    const save = await screen.findByRole('button', { name: 'Save time' })
    expect(save).toBeDisabled()
    fireEvent.change(screen.getByLabelText('Morning time'), { target: { value: '08:15' } })
    expect(save).toBeEnabled()
    fireEvent.click(save)

    expect(await screen.findByText(/is now sent at 08:15\./)).toBeInTheDocument()
    expect(engine.settings.push.preferences.morning_time).toBe('08:15')
  })

  test('the master off turns all three off in one tap, and then says everything is off', async () => {
    const engine = setUp()
    await open(engine)

    fireEvent.click(await screen.findByRole('button', { name: 'Turn everything off' }))
    expect(await screen.findByText(NOTIFICATION_COPY.allOff)).toBeInTheDocument()
    expect(engine.settings.push.preferences).toMatchObject({
      list_changes: false,
      event_reminders: false,
      task_due_morning: false,
    })
    expect(screen.queryByRole('button', { name: 'Turn everything off' })).not.toBeInTheDocument()
    for (const name of ['List changes', 'Event reminders', 'Due today']) {
      expect(screen.getByRole('switch', { name })).not.toBeChecked()
    }
  })

  test('preferences that could not be read are not drawn as everything off', async () => {
    const engine = setUp()
    engine.refusals['GET /api/push/preferences'] = { status: 503, body: { detail: 'down' } }
    await open(engine)
    expect(await screen.findByText(/this is not what is really turned on\. Nothing was changed\./)).toBeInTheDocument()
    expect(screen.queryByText(NOTIFICATION_COPY.allOff)).not.toBeInTheDocument()
  })
})

describe('the page, as a place to be found', () => {
  test('Settings lists Notifications, on both surfaces', async () => {
    expect(SETTINGS_SECTIONS.map((section) => section.label)).toContain('Notifications')

    const family = renderApp('/settings', setUp(), 'family')
    expect(await screen.findByRole('link', { name: /^Notifications/ })).toBeInTheDocument()
    family.unmount()
    browser?.restore()

    const wide = renderApp('/settings/notifications', setUp(), 'workbench')
    const side = await screen.findByRole('navigation', { name: 'Settings sections' })
    expect(within(side).getByRole('link', { name: 'Notifications' })).toHaveAttribute('aria-current', 'page')
    wide.unmount()
  })

  test('works at the workbench width too', async () => {
    const engine = setUp()
    await open(engine, 'workbench')
    expect(await screen.findByRole('switch', { name: 'Due today' })).toBeInTheDocument()
  })
})

describe('the compulsion test (CLAUDE.md section 7)', () => {
  test('no word the page is written with refers to absence, a streak, or how the app is used', () => {
    const words: string[] = [
      NOTIFICATION_COPY.subtitle,
      NOTIFICATION_COPY.notSetUp,
      NOTIFICATION_COPY.unsupported,
      NOTIFICATION_COPY.iosTitle,
      NOTIFICATION_COPY.iosLead,
      ...NOTIFICATION_COPY.iosSteps,
      NOTIFICATION_COPY.deviceOn,
      NOTIFICATION_COPY.deviceOff,
      NOTIFICATION_COPY.morningHelp,
      NOTIFICATION_COPY.allOff,
      DENIED_SENTENCE,
      DISMISSED_SENTENCE,
      ...Object.values(NOTIFICATION_COPY.kinds).flatMap((kind) => [kind.label, kind.blurb]),
    ]
    for (const word of words) expect(word).not.toMatch(BANNED)
  })

  test('nor does anything it actually renders, in its busiest state', async () => {
    const engine = setUp({ subscribed: true, permission: 'granted' })
    await open(engine)
    await screen.findByRole('switch', { name: 'Due today' })
    expect(document.body.textContent ?? '').not.toMatch(BANNED)
  })
})
