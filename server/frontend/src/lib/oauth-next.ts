/**
 * Where to go after signing in, when the sign-in was asked for by the engine's
 * OAuth consent page (ai-upgrade ticket 03): Django sends a signed-out browser
 * to `/login?next=/oauth/authorize/?...`, and after the session login the
 * browser must land back on that page, which Django renders.
 *
 * Only that page. Anything else in `next` is ignored, so the sign-in form can
 * never be used to bounce someone to another site or another path.
 */
export const OAUTH_AUTHORIZE_PATH = '/oauth/authorize/'

export function oauthNext(search: string): string | null {
  const next = new URLSearchParams(search).get('next')
  if (!next || !next.startsWith(OAUTH_AUTHORIZE_PATH)) return null
  if (next.includes('\\')) return null
  return next
}
