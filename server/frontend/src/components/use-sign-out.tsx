import { useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'

import { api } from '@/api/client'

/**
 * Sign out, from Settings, Account (`screens/settings/account.tsx`).
 *
 * Best-effort: whether or not the server call lands, the client drops its own
 * idea of who is signed in and sends the person back to `/login`. A failed
 * revoke here should not trap someone on a screen they explicitly asked to
 * leave.
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
