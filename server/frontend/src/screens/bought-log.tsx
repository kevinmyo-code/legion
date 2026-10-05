import { Link, useNavigate } from '@tanstack/react-router'
import { ChevronLeft } from 'lucide-react'

import { PurchaseForm } from '@/components/purchases/form'
import { useSurface } from '@/lib/surface'
import { WorkbenchBought } from '@/screens/bought'

/**
 * "Log it", on its own page at phone width (ticket 05: item, date, optional
 * store / price / note, and the private switch). Saving takes you back to the
 * search for what you just logged, so the answer on screen is the entry that now
 * exists. The desk has no separate page: its form is the side panel on `/bought`.
 */
export function BoughtLogScreen({ item }: { item?: string }) {
  const surface = useSurface()
  const navigate = useNavigate()
  if (surface === 'workbench') return <WorkbenchBought initialQuery={item} />

  return (
    <div className="flex flex-col gap-3 pb-16">
      <header className="flex items-center gap-1 pt-1">
        <Link
          to="/bought"
          aria-label="Back to Bought"
          className="-ml-2 grid size-11 place-items-center rounded-full text-muted-foreground outline-none hover:bg-surface-2 focus-visible:outline-2 focus-visible:outline-primary"
        >
          <ChevronLeft className="size-6" />
        </Link>
        <h1 className="text-[1.5rem] leading-tight tracking-tight">Log something bought</h1>
      </header>
      <PurchaseForm
        defaultItem={item}
        onSaved={(saved) => void navigate({ to: '/bought', search: { q: saved?.item ?? item } })}
        onCancel={() => void navigate({ to: '/bought', search: { q: item } })}
      />
    </div>
  )
}
