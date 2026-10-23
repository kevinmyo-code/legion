import { createFileRoute } from '@tanstack/react-router'

import { BooksScreen } from '@/screens/books'

/** `/books`: read EPUB, PDF and CBZ from a laptop folder, synced with the phone (books ticket 10).
 * Desk only in the nav: the family tabs are fixed at four (spec D1). */
export const Route = createFileRoute('/_authed/books')({
  component: BooksScreen,
})
