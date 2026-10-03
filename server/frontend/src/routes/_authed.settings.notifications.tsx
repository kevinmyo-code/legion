import { createFileRoute } from '@tanstack/react-router'

import { NotificationsScreen } from '@/screens/settings/notifications'

export const Route = createFileRoute('/_authed/settings/notifications')({
  component: NotificationsScreen,
})
