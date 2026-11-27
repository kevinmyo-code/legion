import { fireEvent, screen, within } from '@testing-library/react'
import { describe, expect, test } from 'vitest'

import type { ConversationDevice } from '@/api/conversations'
import { createEngine, seedHousehold } from '@/test/engine'
import { makeBatch } from '@/test/engine-conversations'
import { renderApp } from '@/test/render-app'

/**
 * `/settings/conversations` (#160): the engine's rendered conversation batches,
 * newest first, one panel per device. Every sentence the page promises is
 * asserted verbatim, and the engine being down is asserted to be its own
 * sentence and never the empty one.
 */

const PHONE: ConversationDevice = {
  device_label: 'Phone',
  total: 14,
  batches: [
    makeBatch('b-new', '2026-11-20T09:30:00Z', ['USER: what is on my list', 'TOOL -> read_lists()', 'TOOL <- read_lists ok 120ms']),
    makeBatch('b-old', '2026-11-19T18:00:00Z', ['USER: good evening']),
  ],
}
const WEB: ConversationDevice = {
  device_label: 'web',
  total: 1,
  batches: [makeBatch('w-1', '2026-11-18T12:00:00Z', ['USER: hello from the browser'])],
}

function engineWith(devices: ConversationDevice[]) {
  return createEngine({ ...seedHousehold(), conversations: { devices } })
}

describe('the conversations page', () => {
  test('is a Settings section, listed in the side list and the index', async () => {
    renderApp('/settings/conversations', engineWith([PHONE]), 'workbench')
    const side = await screen.findByRole('navigation', { name: 'Settings sections' })
    expect(within(side).getByRole('link', { name: 'Conversations' })).toHaveAttribute('aria-current', 'page')
  })

  test('shows one panel per device with its batches newest first, as the engine rendered them', async () => {
    renderApp('/settings/conversations', engineWith([PHONE, WEB]), 'workbench')
    expect(await screen.findByRole('heading', { name: 'Phone' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'web' })).toBeInTheDocument()

    const timeline = screen.getByText(/USER: what is on my list/)
    expect(timeline.textContent).toBe('USER: what is on my list\nTOOL -> read_lists()\nTOOL <- read_lists ok 120ms')
    const order = screen.getAllByText(/^USER:/).map((node) => node.textContent)
    expect(order).toEqual([
      expect.stringContaining('what is on my list'),
      'USER: good evening',
      'USER: hello from the browser',
    ])
    expect(screen.getByText(/=== batch b-new /)).toBeInTheDocument()
  })

  test('says how many of the device\'s uploads are shown, and asks the engine for more on request', async () => {
    const engine = engineWith([PHONE, WEB])
    renderApp('/settings/conversations', engine, 'workbench')
    expect(await screen.findByText('Showing the newest 2 of 14 uploads.')).toBeInTheDocument()
    expect(screen.getByText('1 upload.')).toBeInTheDocument()
    expect(engine.searches['GET /api/device-logs/conversations/']).toBe('limit=10')

    fireEvent.click(screen.getByRole('button', { name: 'Show up to 30 per device' }))
    await screen.findByRole('heading', { name: 'Phone' })
    expect(engine.searches['GET /api/device-logs/conversations/']).toBe('limit=30')
  })

  test('a device the 60-batch cap left without any says how many older batches are not shown', async () => {
    const capped: ConversationDevice = { device_label: 'Tablet', total: 3, batches: [] }
    renderApp('/settings/conversations', engineWith([PHONE, capped]), 'workbench')
    expect(await screen.findByText('3 older batches not shown.')).toBeInTheDocument()
  })

  test('says replies and tool details are not shown, and that a split conversation is two blocks', async () => {
    renderApp('/settings/conversations', engineWith([WEB]), 'workbench')
    expect(
      await screen.findByText(
        "The assistant's replies and tool details are not shown here. A conversation a device uploaded in two parts shows as two blocks.",
      ),
    ).toBeInTheDocument()
  })

  test('an unreadable batch is named in words, not dropped', async () => {
    const broken = {
      ...makeBatch('bad', '2026-11-17T08:00:00Z', []),
      readable: false,
      problem: 'could not be read: The file is not valid gzip data.',
    }
    renderApp('/settings/conversations', engineWith([{ device_label: 'Phone', total: 1, batches: [broken] }]), 'workbench')
    expect(await screen.findByText('could not be read: The file is not valid gzip data.')).toBeInTheDocument()
    expect(screen.getByText('(could not be read)')).toBeInTheDocument()
  })

  test('an engine with none says so, in words', async () => {
    renderApp('/settings/conversations', engineWith([]), 'workbench')
    expect(await screen.findByText('The engine holds no conversations from your devices.')).toBeInTheDocument()
  })

  test('a refusal is worded as a sign-in problem, not as the engine being down', async () => {
    const engine = engineWith([PHONE])
    engine.conversations.failing = 403
    renderApp('/settings/conversations', engine, 'workbench')
    expect(
      await screen.findByText(
        'The engine refused this request (you may need to sign in again), so this is not the real list of conversations. Nothing was changed.',
      ),
    ).toBeInTheDocument()
    expect(screen.queryByText(/Could not reach the engine/)).not.toBeInTheDocument()
  })

  test('an engine error is worded as an error, and a demo visit is told the page is not available', async () => {
    const broken = engineWith([PHONE])
    broken.conversations.failing = 500
    renderApp('/settings/conversations', broken, 'workbench')
    expect(
      await screen.findByText(
        'The engine had an error, so this is not the real list of conversations. Nothing was changed.',
      ),
    ).toBeInTheDocument()
  })

  test('a recruiter demo visit is told the page is not available in the demo', async () => {
    const demo = engineWith([PHONE])
    demo.conversations.failing = 403
    demo.conversations.failingDetail =
      "Nothing was read or written. This is a recruiter's demo visit, and device logs come from a member's phone."
    renderApp('/settings/conversations', demo, 'workbench')
    expect(await screen.findByText('This page is not available in the demo.')).toBeInTheDocument()
  })

  test('the engine being down is its own sentence, never the empty one', async () => {
    const engine = engineWith([PHONE])
    engine.conversations.failing = 503
    renderApp('/settings/conversations', engine, 'workbench')
    expect(
      await screen.findByText(
        'Could not reach the engine, so this is not the real list of conversations. Nothing was changed.',
      ),
    ).toBeInTheDocument()
    expect(screen.queryByText('The engine holds no conversations from your devices.')).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Phone' })).not.toBeInTheDocument()
  })
})
