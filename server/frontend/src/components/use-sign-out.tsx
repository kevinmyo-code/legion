import { useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { LogOut } from 'lucide-react'

import { api } from '@/api/client'
import { Button } from '@/components/ui/button'

/**
 * Sign out, shared by both shells.
 *
 * Best-effort: whether or not the server call lands, the client drops its own
 * idea of who is signed in and sends the person back to `/login`. A failed
 * revoke here should not trap someone on a screen they explicitly asked to
 * leave. It lives in the shell because there is no Settings screen to hold it
 * yet; when `/settings/account` is built this moves there.
 */
export function useSignOut() {
  const queryClient = useQueryClient()
  const navigate = useNavigate()

  return async function signOut() {
    try {
      await api.POST('/api/auth/session/logout', {
        headers: { 'Content-Length': '0' },
      })
    } finally {
      queryClient.clear()
      await navigate({ to: '/login' })
    }
  }
}

/** The icon-only sign-out of the family top bar. */
export function SignOutIconButton() {
  const signOut = useSignOut()
  return (
    <Button variant="ghost" size="icon" aria-label="Sign out" onClick={signOut}>
      <LogOut />
    </Button>
  )
}
