import { createFileRoute } from '@tanstack/react-router'

import { HouseholdScreen } from '@/screens/settings/household'

export const Route = createFileRoute('/_authed/settings/household')({
  component: HouseholdScreen,
})
