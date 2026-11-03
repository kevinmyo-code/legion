import { createFileRoute } from '@tanstack/react-router'

import { BranchReportScreen } from '@/screens/runs'

/** `/runs/report/<name>?version=N`: one procedure version's branch report (procedures
 * ticket 14). Without a version the engine picks the active one, else the newest. */
export const Route = createFileRoute('/_authed/runs/report/$name')({
  validateSearch: (search: Record<string, unknown>): { version?: number } => {
    const version = Number(search.version)
    return Number.isInteger(version) && version >= 1 ? { version } : {}
  },
  component: Page,
})

function Page() {
  const { name } = Route.useParams()
  const { version } = Route.useSearch()
  return <BranchReportScreen name={name} version={version} />
}
