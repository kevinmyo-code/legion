import { createFileRoute } from '@tanstack/react-router'

import { AccountScreen } from '@/screens/settings/account'

export const Route = createFileRoute('/_authed/settings/account')({
  component: AccountScreen,
})
