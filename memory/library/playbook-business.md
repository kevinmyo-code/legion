# Business & Research Playbook

> **STATUS: FROZEN ARCHIVE (banner added 2026-08-01).** This shelf is Midnight AI history: a
> head-unit car launcher with a commercial model and a city-pop design language. All three of
> those premises died in the 2026-07-30/31 pivot to LEGION. Nothing below governs LEGION.
> Read it as reference for why something was built the way it was. **Do not act on its
> blockers, sprints, backlog items, or hardware notes.** Live rules are in CLAUDE.md; live
> state is in memory/MEMORY.md. See CLAUDE.md §11.


Accumulated, project-specific knowledge for the business agent. Maintained by the librarian: the
orchestrator relays SKILL: lines from business agent reports via a librarian FILE dispatch, which
appends them here.

Midnight AI (historical names in older notes: Moose, Aria, Nightrunner; same app).

## Spotify (distribution / control) — RESOLVED 2026-06-28

**Decision: stay on Android MediaSession. Web API and App Remote SDK are both dead ends for a
solo dev.** Do not build on Spotify OAuth for a public Play Store launch.
- Development Mode cap dropped from 25 -> 5 authenticated users (eff. 2026-02-11 new Client IDs /
  2026-03-09 existing); app owner must have Premium; one Dev-Mode Client ID per dev. Users still
  added manually by name+email in the dashboard; others get HTTP 403 / UserNotAuthorizedException.
- Extended Quota Mode (only unlimited-user path) requires a registered business, a launched
  service with 250k+ MAU, key-market availability, commercial viability, policy compliance;
  individuals cannot apply since 2025-05-15 (org email only); ~6-week review. Solo pre-launch dev
  is categorically ineligible.
- The quota/allowlist is a property of the Client ID, so the Android App Remote SDK does NOT
  bypass the cap, same wall as Web API.
- On-demand "play specific track / playFromSearch" requires the END USER to have Premium across
  all Spotify SDKs/APIs (Free = shuffle-only + limited skips).
- Nov 27 2024 deprecations removed Recommendations/Audio Features/Related Artists/Featured
  playlists/30s previews for new apps; transport + search survive (Search limit cut 50->10).
- Sources: developer.spotify.com/documentation/web-api/concepts/quota-modes ;
  /blog/2026-02-06-update-on-developer-access-and-platform-security ;
  spotify.github.io/android-sdk/app-remote-lib/ (all checked 2026-06-28).
- Implication for coding: invest in MediaSession reliability (transport is rock-solid;
  playFromSearch on active session + MediaBrowserService cold-start for specific songs). Bonus:
  MediaSession also controls YouTube Music etc., better for a head-unit companion anyway.

**2026-07-08 re-eval (strategy note, not yet built, see library/decisions.md):** reframed as an
optional add-on where each user registers their own dev app + pastes their own client ID,
sidestepping the 5-user Dev Mode cap. Gating and unresolved: whether Spotify's Developer Policy
actually permits this BYO-own-dev-app distribution pattern (their Feb 2026 "Platform Security"
update targets quota workarounds). Resolve with a policy-reading spike before any build.

## Naming / branding — RESOLVED 2026-07

Name is Midnight AI. (Direction was previously open between a retro/city-pop name, not "AiApp",
not necessarily "Moose"; now settled.) Play Store name-availability + rough trademark sanity still
worth a pass before store listing work.

## Product positioning (reference)

Enthusiast drivers, older cars, Android head units. Voice-first, reduce touch. Solo dev,
cost-sensitive. City-pop / Stardew-Valley-charm aesthetic.

## Mapbox — reclassified 2026-07-08 (strategy note, see library/decisions.md)

Cost objection invalidated under a BYO public token model (each user's own Mapbox account absorbs
their own usage inside the free tier: 100 MAU + 1,000 trips/mo per account). Reclassified from
"too expensive" to a roadmap candidate (large-scope aesthetic upgrade, not urgent). Google Nav SDK
and other embedded nav stay killed.
