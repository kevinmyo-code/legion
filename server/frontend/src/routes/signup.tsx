import { createFileRoute } from '@tanstack/react-router'

import { SignupCodeScreen } from '@/screens/signup-code'

/** `/signup`, outside `_authed`: a code box for an invite that was typed, not clicked. */
export const Route = createFileRoute('/signup')({
  component: SignupCodeScreen,
})
