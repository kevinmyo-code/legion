import { createFileRoute } from '@tanstack/react-router'

import { DevicesScreen } from '@/screens/settings/devices'

export const Route = createFileRoute('/_authed/settings/devices')({
  component: DevicesScreen,
})
