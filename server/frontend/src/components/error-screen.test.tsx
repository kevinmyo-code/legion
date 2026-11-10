import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'

import { AppErrorBoundary, RouteErrorScreen } from './error-screen'

afterEach(() => vi.restoreAllMocks())

function Boom(): never {
  throw new Error('secret detail at db.example.test')
}

test('the app boundary shows words, not the error text, and can retry', () => {
  vi.spyOn(console, 'error').mockImplementation(() => {})
  render(
    <AppErrorBoundary>
      <Boom />
    </AppErrorBoundary>,
  )
  expect(screen.getByRole('alert').textContent).toContain('This page stopped working')
  expect(screen.queryByText(/db\.example\.test/)).toBeNull()
  expect(screen.getByRole('button', { name: 'Try again' })).toBeTruthy()
})

test('the route error screen calls reset on Try again', () => {
  vi.spyOn(console, 'error').mockImplementation(() => {})
  const reset = vi.fn()
  render(<RouteErrorScreen error={new Error('x')} reset={reset} info={{ componentStack: '' }} />)
  fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
  expect(reset).toHaveBeenCalledTimes(1)
})
