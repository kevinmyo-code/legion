import { createFileRoute } from '@tanstack/react-router'

import { BankScreen } from '@/screens/settings/bank'

export const Route = createFileRoute('/_authed/settings/bank')({
  component: BankScreen,
})
