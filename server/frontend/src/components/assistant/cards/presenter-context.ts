import { createContext, useContext } from 'react'

import type { ModalCard } from './types'

/** Opens a card over the whole page (`presenter.tsx`). Its own file so the cards can use it. */
export interface Presenter {
  show(card: ModalCard): void
}

export const PresenterContext = createContext<Presenter | null>(null)

/** The page's modal, or null where there is none (a card drawn on its own, in a test). */
export function usePresenter(): Presenter | null {
  return useContext(PresenterContext)
}
