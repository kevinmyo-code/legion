import { describe, expect, it, vi } from 'vitest'

import { makeControllerChangeHandler, pageIsBusy, type ReloadDeps } from './sw-reload'

function deps(over: Partial<ReloadDeps> = {}) {
  let hiddenRun: (() => void) | null = null
  const d: ReloadDeps = {
    hadController: true,
    isBusy: () => false,
    isHidden: () => false,
    reload: vi.fn(),
    onHidden: (run) => {
      hiddenRun = run
    },
    ...over,
  }
  return { d, fireHidden: () => hiddenRun?.() }
}

describe('reload when a new deploy takes over', () => {
  it('reloads once when an old worker is replaced and the page is idle', () => {
    const { d } = deps()
    const handler = makeControllerChangeHandler(d)
    handler()
    handler()
    expect(d.reload).toHaveBeenCalledTimes(1)
  })

  it('never reloads on the first install, when nothing controlled the page', () => {
    const { d } = deps({ hadController: false })
    makeControllerChangeHandler(d)()
    expect(d.reload).not.toHaveBeenCalled()
  })

  it('reloads on a later deploy within a first-visit page life', () => {
    const { d } = deps({ hadController: false })
    const handler = makeControllerChangeHandler(d)
    handler()
    expect(d.reload).not.toHaveBeenCalled()
    handler()
    expect(d.reload).toHaveBeenCalledTimes(1)
  })

  it('waits for the page to be hidden while someone is mid-edit', () => {
    const { d, fireHidden } = deps({ isBusy: () => true })
    makeControllerChangeHandler(d)()
    expect(d.reload).not.toHaveBeenCalled()
    fireHidden()
    expect(d.reload).toHaveBeenCalledTimes(1)
  })

  it('reloads straight away when the page is already hidden, busy or not', () => {
    const { d } = deps({ isBusy: () => true, isHidden: () => true })
    makeControllerChangeHandler(d)()
    expect(d.reload).toHaveBeenCalledTimes(1)
  })
})

describe('pageIsBusy', () => {
  it('is busy with an open dialog', () => {
    document.body.innerHTML = '<div role="dialog"></div>'
    expect(pageIsBusy()).toBe(true)
  })

  it('is busy with focus in a field', () => {
    document.body.innerHTML = '<input id="t" />'
    ;(document.getElementById('t') as HTMLInputElement).focus()
    expect(pageIsBusy()).toBe(true)
  })

  it('is idle on a plain page', () => {
    document.body.innerHTML = '<button id="b">Go</button>'
    ;(document.getElementById('b') as HTMLButtonElement).focus()
    expect(pageIsBusy()).toBe(false)
  })
})
