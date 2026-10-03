import { Outlet, createFileRoute } from '@tanstack/react-router'

import { SettingsSideNav } from '@/components/settings/settings-side-nav'
import { useSurface } from '@/lib/surface'

/**
 * The Settings frame, for every `/settings/*` route (spec D12). On the family
 * surface it is just the page; on the workbench it adds the side list of
 * sections. Two trees, picked by viewport, never one hidden by CSS.
 */
export const Route = createFileRoute('/_authed/settings')({
  component: SettingsLayout,
})

function SettingsLayout() {
  const surface = useSurface()
  if (surface === 'family') return <Outlet />
  return (
    <div className="flex items-start gap-10">
      <SettingsSideNav />
      <div className="min-w-0 flex-1">
        <Outlet />
      </div>
    </div>
  )
}
