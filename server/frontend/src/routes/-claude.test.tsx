import { screen } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import type { ClaudeTask } from '@/api/claude'
import { CANT_READ_CLAUDE } from '@/api/claude'
import { CLAUDE_COPY } from '@/screens/claude'
import { createEngine, seedHousehold } from '@/test/engine'
import { renderApp } from '@/test/render-app'

/**
 * The Claude session on the web desk (claude-session ticket 09): each task with its status in
 * words, a delivered page with an Open link to the engine's signed URL in a new tab, a page that
 * has not arrived said in words, empty and unreadable as different sentences.
 */

afterEach(() => vi.unstubAllGlobals())

function task(partial: Partial<ClaudeTask> = {}): ClaudeTask {
  return {
    id: 56,
    request: 'make me an itinerary for a day trip into Atlanta',
    machine: 'Alienware',
    status: 'done',
    summary: 'Your Atlanta day trip is ready.',
    question: null,
    created_at: '2026-11-03T15:00:00Z',
    finished_at: '2026-11-03T15:09:00Z',
    files: [{ id: 1410, name: 'atlanta.html', size: 9000, uploaded: true, requested: true, content_type: 'text/html', received: false, gone: false }],
    artifacts: [
      {
        id: 1410,
        filename: 'atlanta.html',
        title: 'Atlanta day trip',
        size: 9000,
        url: '/api/claude/pages/1410/?t=signed',
        expires_in: 600,
      },
    ],
    parent: null,
    origin: 'offered',
    deliver: 'phone',
    confirms: false,
    progress: null,
    progress_at: null,
    steps: [],
    ...partial,
  }
}

describe('the Claude session page', () => {
  test('lists the task and opens its page in a new tab from the signed link', async () => {
    renderApp('/claude', createEngine({ ...seedHousehold(), claude: [task()] }))
    expect(await screen.findByText('make me an itinerary for a day trip into Atlanta')).toBeInTheDocument()
    expect(screen.getByText(/Task 56 on the Alienware: Done/)).toBeInTheDocument()
    const open = screen.getByRole('link', { name: /Open page: Atlanta day trip/ })
    expect(open).toHaveAttribute('href', '/api/claude/pages/1410/?t=signed')
    expect(open).toHaveAttribute('target', '_blank')
    expect(open).toHaveAttribute('rel', 'noopener noreferrer')
  })

  test('a page that has not arrived is said in words, with no link', async () => {
    const waiting = task({ artifacts: [], files: [{ id: 1410, name: 'atlanta.html', size: 9000, uploaded: false, requested: true, content_type: '', received: false, gone: false }] })
    renderApp('/claude', createEngine({ ...seedHousehold(), claude: [waiting] }))
    expect(await screen.findByText(CLAUDE_COPY.pageWaiting)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Open page/ })).toBeNull()
  })

  test('empty and unreadable are different sentences', async () => {
    renderApp('/claude', createEngine({ ...seedHousehold() }))
    expect(await screen.findByText(CLAUDE_COPY.empty)).toBeInTheDocument()
  })

  test('is in the desk rail', async () => {
    renderApp('/claude', createEngine({ ...seedHousehold() }))
    expect(await screen.findByRole('link', { name: 'Claude' })).toHaveAttribute('href', '/claude')
    expect(CANT_READ_CLAUDE).not.toEqual(CLAUDE_COPY.empty)
  })
})
