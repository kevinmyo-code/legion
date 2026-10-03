import { Link } from '@tanstack/react-router'
import type { ReactNode } from 'react'

import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { useSurface } from '@/lib/surface'

/**
 * What a workbench-only page says at family width: this was made for a bigger
 * screen, and here is the way home.
 *
 * Mia can reach a desktop-only URL by a link or a bookmark; the answer is one
 * plain card and a button, never a dense table squeezed into 390 px (spec user
 * story 31). It says what happened in words rather than redirecting silently,
 * because a page that quietly takes you somewhere else reads as a bug.
 */
export function BiggerScreen() {
  return (
    <Card className="mx-auto mt-6 max-w-md rounded-sheet">
      <CardHeader>
        <CardTitle className="text-xl">This page is made for a bigger screen.</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <p className="text-muted-foreground">
          It has tables and side-by-side panels that do not fit a phone. Open it on a laptop or
          desktop, or carry on from Home.
        </p>
        <Button asChild size="lg">
          <Link to="/">Go to Home</Link>
        </Button>
      </CardContent>
    </Card>
  )
}

/**
 * Wraps a workbench-only route's content. At family width the content is not
 * rendered at all, not hidden (the table must not be in the DOM, the
 * accessibility tree or the bundle's work for a phone), and the bigger-screen
 * card stands in. Every `/money`, `/pantry`, `/body`, `/fleet`, `/places` and
 * `/notes` route is its children.
 */
export function WorkbenchOnly({ children }: { children: ReactNode }) {
  const surface = useSurface()
  return surface === 'workbench' ? children : <BiggerScreen />
}
