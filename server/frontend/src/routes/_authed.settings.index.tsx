import { createFileRoute } from '@tanstack/react-router'

import { SettingsIndex } from '@/screens/settings/index'

export const Route = createFileRoute('/_authed/settings/')({
  component: SettingsIndex,
})
