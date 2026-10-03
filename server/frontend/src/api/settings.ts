import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '@/api/client'
import { WriteRefused, refusalMessage, runWrite, runWriteData } from '@/api/refusal'

/**
 * Everything the join, signup and settings screens ask the engine for, through
 * the generated client (`src/api/client.ts`) and nothing else.
 *
 * Reads say three things, as everywhere in this app: still asking, the data, and
 * could not reach the engine (a thrown `Error`, which the screen words itself).
 * Writes are NOT optimistic: each one waits for the engine and either resolves
 * or throws a `WriteRefused` whose message begins with what did not happen
 * (CLAUDE.md section 7's outcome-verb rule, `src/api/refusal.ts`). A setting that
 * silently reverted would be worse than one that was slow.
 */

export const INVITES_KEY = ['households', 'invites'] as const
export const DEVICES_KEY = ['auth', 'devices'] as const
export const HOUSEHOLD_KEY = ['households', 'me'] as const
export const ME_KEY = ['auth', 'me'] as const

/** `GET /api/auth/invite/<code>`: what a code will do, without spending it.
 * A 404 is an answer ("no such code"), not a failure to reach the engine. */
export type InviteLookup =
  | { found: true; preview: NonNullable<Awaited<ReturnType<typeof getPreview>>['data']> }
  | { found: false; sentence: string }

async function getPreview(code: string) {
  return api.GET('/api/auth/invite/{code}', { params: { path: { code } } })
}

/** The engine throttles invite lookups (5 a minute per IP, shared with signup). */
export class InviteThrottled extends Error {
  constructor() {
    super('Too many tries at once. Wait a minute and open the link again.')
    this.name = 'InviteThrottled'
  }
}

export function useInvitePreview(code: string) {
  return useQuery({
    queryKey: ['auth', 'invite', code],
    queryFn: async (): Promise<InviteLookup> => {
      const { data, error, response } = await getPreview(code)
      if (response.status === 404) {
        const said = (error as { detail?: string } | undefined)?.detail
        return {
          found: false,
          sentence:
            said ?? 'No invite has that code. Nothing was spent - check the link for a typo.',
        }
      }
      if (response.status === 429) throw new InviteThrottled()
      if (error || !data) throw new Error(`GET /api/auth/invite answered ${response.status}`)
      return { found: true, preview: data }
    },
    retry: false,
    // A preview is a fact about a code at one moment; asking again on focus would
    // spend the throttle (5 a minute per IP) for no gain.
    refetchOnWindowFocus: false,
    staleTime: Infinity,
  })
}

export interface SignupInput {
  name: string
  email: string
  password: string
  inviteCode: string
  householdName: string
}

/** A signup the engine refused. `fields` holds the sentences it keyed to a form
 * field (`{password: [...]}`), so the screen can put each beside its box; the
 * message is the rest, and always opens with what did not happen. */
export class SignupRefused extends WriteRefused {
  fields: Record<string, string>
  constructor(message: string, fields: Record<string, string>) {
    super(message)
    this.name = 'SignupRefused'
    this.fields = fields
  }
}

function fieldSentences(body: unknown): Record<string, string> {
  const fields: Record<string, string> = {}
  if (body === null || typeof body !== 'object') return fields
  for (const [field, value] of Object.entries(body as Record<string, unknown>)) {
    if (Array.isArray(value) && value.every((item) => typeof item === 'string')) {
      fields[field] = value.join(' ')
    }
  }
  return fields
}

/**
 * Signup is three calls, because the engine's signup mints a DEVICE token (a
 * phone's credential) and a browser lives on a SESSION cookie:
 *
 *  1. `POST /api/auth/signup`, which creates the account;
 *  2. `POST /api/auth/logout` with that token, so the credential nobody holds
 *     does not sit in Devices as a phone that does not exist (best effort);
 *  3. `POST /api/auth/session/login`, which is the actual sign-in.
 *
 * Throws `SignupRefused` when 1 fails (nothing was created). Resolves with
 * `signedIn: false` when 1 worked and 3 did not, so the screen can say "your
 * account exists, sign in" instead of "signup failed".
 */
export async function signUp(input: SignupInput): Promise<{ signedIn: boolean; problem?: string }> {
  let result: Awaited<ReturnType<typeof postSignup>>
  try {
    result = await postSignup(input)
  } catch {
    throw new SignupRefused('Could not reach the engine. No account was created.', {})
  }
  if (!result.response.ok || !result.data) {
    const fields = fieldSentences(result.error)
    const detail = (result.error as { detail?: string } | undefined)?.detail
    const message =
      detail !== undefined
        ? refusalMessage('created', result.response.status, result.error)
        : Object.keys(fields).length > 0
          ? 'No account was created. Check the fields above.'
          : refusalMessage('created', result.response.status, result.error)
    throw new SignupRefused(message, fields)
  }

  try {
    await api.POST('/api/auth/logout', {
      headers: { Authorization: `Token ${result.data.token}`, 'Content-Length': '0' },
    })
  } catch {
    // The account exists either way; the leftover token is listed and revocable.
  }
  try {
    await runWrite('saved', () =>
      api.POST('/api/auth/session/login', { body: { email: input.email, password: input.password } }),
    )
    return { signedIn: true }
  } catch (error) {
    return {
      signedIn: false,
      problem: error instanceof Error ? error.message : 'Could not sign in.',
    }
  }
}

function postSignup(input: SignupInput) {
  return api.POST('/api/auth/signup', {
    body: {
      email: input.email,
      password: input.password,
      name: input.name,
      invite_code: input.inviteCode,
      household_name: input.householdName,
      device_name: 'Web sign-up',
    },
  })
}

export function useInvites(enabled: boolean) {
  return useQuery({
    queryKey: INVITES_KEY,
    queryFn: async () => {
      const { data, error, response } = await api.GET('/api/households/me/invites')
      if (error || !data) throw new Error(`GET /api/households/me/invites answered ${response.status}`)
      return data
    },
    enabled,
    retry: false,
  })
}

export function useCreateInvite() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (input: { maxUses: number; expiresInDays: number }) =>
      runWriteData('created', () =>
        api.POST('/api/households/me/invites', {
          body: {
            creates_household: false,
            max_uses: input.maxUses,
            expires_in_days: input.expiresInDays,
          },
        }),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: INVITES_KEY }),
  })
}

export function useRevokeInvite() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (code: string) =>
      runWrite('revoked', () =>
        api.DELETE('/api/households/me/invites/{code}', { params: { path: { code } } }),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: INVITES_KEY }),
  })
}

export function useRemoveMember() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (userId: string) =>
      runWrite('removed', () =>
        api.DELETE('/api/households/me/members/{user_id}', { params: { path: { user_id: userId } } }),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: HOUSEHOLD_KEY }),
  })
}

export function useRenameHousehold() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (name: string) =>
      runWrite('saved', () => api.PATCH('/api/households/me', { body: { name } })),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: HOUSEHOLD_KEY }),
  })
}

export function useDevices() {
  return useQuery({
    queryKey: DEVICES_KEY,
    queryFn: async () => {
      const { data, response } = await api.GET('/api/auth/devices')
      if (!response.ok || !data) throw new Error(`GET /api/auth/devices answered ${response.status}`)
      return data
    },
    retry: false,
  })
}

export function useRevokeDevice() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (id: number) =>
      runWrite('revoked', () =>
        api.DELETE('/api/auth/devices/{token_id}', { params: { path: { token_id: id } } }),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: DEVICES_KEY }),
  })
}

export function useRenameMe() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (name: string) =>
      runWrite('saved', () => api.PATCH('/api/auth/me', { body: { name } })),
    onSuccess: async () => {
      // The roster shows the name too, and so does Home's greeting source.
      await queryClient.invalidateQueries({ queryKey: ME_KEY })
      await queryClient.invalidateQueries({ queryKey: HOUSEHOLD_KEY })
    },
  })
}

export function useChangePassword() {
  return useMutation({
    mutationFn: async (input: { current: string; next: string }) => {
      const data = await runWriteData('changed', () =>
        api.POST('/api/auth/password', {
          body: { current_password: input.current, new_password: input.next },
        }),
      )
      // The engine's own sentence, verbatim: it says the session survived.
      return data?.detail ?? 'Your password is changed.'
    },
  })
}
