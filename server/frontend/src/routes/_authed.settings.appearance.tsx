import { createFileRoute } from '@tanstack/react-router'

import { AppearanceScreen } from '@/screens/settings/appearance'

export const Route = createFileRoute('/_authed/settings/appearance')({
  component: AppearanceScreen,
})
