import { useDemoSession } from '@/api/demo'
import { useMe } from '@/api/queries'

/**
 * What a recruiter's demo visit sees of the normal app (recruiter-demo ticket 13,
 * Kevin's ruling 1, ADR 0069). The visitor has its own throwaway copy of the
 * demo household, so it uses the same screens a member does: Home, Calendar,
 * Lists, Bought, Money, Pantry, Fleet, Places, Notes.
 *
 * **The engine is the fence, this is courtesy.** Every route behind these
 * screens is classified on the server (`household/recruiter_routes.py`) and a
 * denied one refuses in words whatever the browser does. This list only keeps
 * a visitor from walking into a screen that would show nothing but refusals.
 */

/** Screens a visit never shows, and why, in the words the page shows instead. */
export const RECRUITER_DENIED_SCREENS: readonly { prefix: string; reason: string }[] = [
  { prefix: '/body', reason: 'Health, food, sleep and workouts are not part of the demo.' },
  { prefix: '/settings', reason: "The household's settings, members, devices and bank link are not part of the demo." },
  { prefix: '/games', reason: 'Games are played between household members, so they are not part of the demo.' },
  { prefix: '/books', reason: "Books live in a folder on a member's laptop, so they are not part of the demo." },
  { prefix: '/claude', reason: "The Claude session runs on a member's own laptop, so it is not part of the demo." },
]

function matches(prefix: string, pathname: string): boolean {
  return pathname === prefix || pathname.startsWith(`${prefix}/`)
}

/** Why `pathname` is not shown on a demo visit, or null when it is. */
export function recruiterRefusal(pathname: string): string | null {
  return RECRUITER_DENIED_SCREENS.find((s) => matches(s.prefix, pathname))?.reason ?? null
}

/** Whether a nav item pointing at `to` is shown on a demo visit. */
export function shownToRecruiter(to: string): boolean {
  return recruiterRefusal(to) === null
}

/** True when the signed-in user is a recruiter's demo visit (`auth/me` role). */
export function useIsRecruiter(): boolean {
  const me = useMe()
  return me.data?.signedIn === true && me.data.me.role === 'recruiter'
}

/** "3:40 PM" in the browser's own zone, for the banner. */
export function visitEndsAt(expiresAt: string): string {
  return new Date(expiresAt).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' })
}

/** The visit's end time, once the demo door has said it. */
export function useVisitEnd(enabled: boolean): string | null {
  const session = useDemoSession(enabled)
  const expires = session.data?.expires_at
  return enabled && expires ? visitEndsAt(expires) : null
}
