import { createFileRoute } from '@tanstack/react-router'

import { ConversationsScreen } from '@/screens/settings/conversations'

export const Route = createFileRoute('/_authed/settings/conversations')({
  component: ConversationsScreen,
})
