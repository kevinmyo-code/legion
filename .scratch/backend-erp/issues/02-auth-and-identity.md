---
map: backend-erp
ticket: "02"
title: "Auth: two users now, more later, no roles ever"
type: grilling
status: resolved
status-detail: "Email+password, household RLS, dashboard-created accounts, personas bind to user, session fails closed"
blockers: ["01"]
blocked-by: ["[[01-what-the-backend-owns]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Auth: two users now, more later, no roles ever

## Question

Supabase Auth presumably. Decide: sign-in method (email magic link? Google OAuth - the app already
has Google sign-in plumbing for Drive); whether all users see all data (the household model -
recommend yes, it is the current two-adult trust model made explicit) or per-user rows exist (RLS
by household id, users join a household); how a new user joins (invite link?); what happens to
per-device identities (DeviceId) and CompanionProfile personas per user; key storage on Android
(Keystore, same posture as the Gemini key).

## Resolution (2026-08-25) - five rulings

**First, a correction to this ticket's own premise.** It said "the app already has Google sign-in
plumbing for Drive". **It does not.** `sync/DriveAuth.kt:6-11` uses the Google Identity
**Authorization** API (`auth.api.identity`), not `GoogleSignIn` and not Credential Manager. It
requests exactly one scope (`drive.appdata`, `:55`), stores **no token, no account, no email** -
the only persisted artefact is the boolean `sync_enabled` (`ai/CompanionProfile.kt:129`), and the
file says so outright at `:127-128`. "Sign-out" is that boolean flipped
(`ui/DriveSyncScreen.kt:290-291`); it does not revoke the grant. There is no notion anywhere of
WHO the user is. Google sign-in would therefore have been built from scratch, not reused - which
removes the main argument that was in favour of it.

1. **Sign-in is EMAIL + PASSWORD (Kevin, 2026-08-25).** Chosen over Google OAuth and magic link,
   both of which carry a documented trap and neither of which reuses anything.
   - Google OAuth needs **two** Google Cloud OAuth client IDs, one keyed to the app's SHA-1 signing
     cert (`research/06-supabase-feasibility.md` §7) - the same trap already open against Drive,
     where a cloner with their own cert gets `DEVELOPER_ERROR (10)` and can never sync
     (`DriveAuth.kt:38-51`, documented as observed on a real device).
   - Magic link depends on Supabase's built-in email service: **2 messages per hour**, "not meant
     for production use", "no SLA guarantee on message delivery or uptime" (§7b, fetched
     2026-08-25). Making it reliable means a THIRD BYO signup (Resend/SES).
   - Email+password touches neither. Clone-and-run needs only a Supabase URL and anon key, and the
     2/hour service is then only ever hit by a rare password reset, which fits inside it.
2. **Household visibility: ALL users see ALL rows (Kevin, 2026-08-25).** One `household_members`
   table; every data table gets RLS `using (auth.uid() in (select user_id from ...))` through a
   `security definer` helper in a private schema (the recursion fix Supabase documents itself,
   §4). **No roles, no tenancy, no approval workflows** - CLAUDE.md §1's trust model made explicit
   rather than extended. This is also the cheapest thing to build on what exists: **no table in
   the app has a human owner column today** (the only `owner`/`account` hits in `data/local/` are
   `FieldDef.ownerPluginId` and the *bank* `accountId`), so the household model requires adding
   none. Accepted cost: no privacy between the two of you; a note is a shared note.
3. **Both accounts are created in the SUPABASE DASHBOARD; no in-app signup, no invite flow
   (Kevin, 2026-08-25).** Two rows added to `household_members` by hand, once. Zero app code -
   no signup screen, no household code, no invite email (which would have re-imported the 2/hour
   limit ruling 1 just avoided). Accepted cost: adding a third person later is a dashboard job,
   not a feature. This is consistent with BYO-everything: standing up the project is already a
   manual setup step, and this is one more line in it.
4. **Personas and memories BIND TO THE USER ACCOUNT (Kevin, 2026-08-25).** Today a "profile" is a
   named assistant persona and nothing more - `companion_profiles` holds six persona fields and a
   clock (`data/local/CompanionProfileEntity.kt:41-52`), there is **no human-user entity anywhere
   in the app**, and which profile is active is a device-local pref deliberately kept out of sync
   (`ai/ActiveCompanionProfile.kt:27-46`). That changes: a profile gains a user, Alfred is Kevin's
   and Dorothy is his wife's, and the persona follows each of them to the laptop instead of being
   whatever that device last picked. **Memories gain a user tag** so the assistant knows who it is
   speaking to and who said what - required by the cross-interface-memory item already on the map,
   and required by CLAUDE.md §7: recalling one person's statement as the other's is exactly the
   unfalsifiable-memory failure the safety rule forbids. Note the visibility model is unchanged -
   ruling 2 still means both users can SEE both sets; tagging is about attribution, not access.
   **Install-scoped secrets stay install-scoped** (`CompanionProfileEntity.kt:29-34`): the Gemini
   key, Spotify tokens and the rest must never become user-scoped or ride along in a synced row.
5. **The Supabase session lives in KeyVault, but FAILS CLOSED (Kevin, 2026-08-25).** Reuse the
   existing AES/GCM-under-Keystore vault (`ai/KeyVault.kt`, alias `nightrunner_vault`), with one
   deliberate departure from every other secret slot: **no plaintext fallback for the session
   token.** `CompanionProfile.kt:345` currently stores raw plaintext when `KeyVault.encrypt`
   returns null, and `KeyVault.kt:18-20` gives the reason - cheap head units with flaky keymaster
   had to degrade rather than crash. **Phone-only is the premise now, so that reason is dead**,
   and a refresh token is not like a Gemini key: it is a standing credential to all household
   data. If Keystore fails, store nothing and make the user sign in again. The closest existing
   shape to copy is the Spotify triple (`saveSpotifyTokens`/`clearSpotifyTokens`,
   `CompanionProfile.kt:456-467`) - access token, refresh token, expiry, plus a clear for sign-out.
   **The Supabase URL and anon key are NOT secrets** and need no vault; they are BYO runtime
   config entered like the Gemini key, not baked into `BuildConfig` (a distributed APK must not
   carry Kevin's project).

## What this leaves for whoever builds it

- **No Supabase code exists anywhere in the repo** - zero hits for `supabase` across Kotlin, Gradle,
  TOML, XML and JSON. `supabase-kt` is a new dependency, and the nearest existing HTTP stacks are
  OkHttp and raw `HttpURLConnection`.
- **The existing Drive sync has no device identity at all**: one Google account = one
  `appDataFolder` = the sole principal, rows matched on a natural key or a random-UUID `syncId`,
  conflict by strictly-greater LWW with ties keeping local (`sync/SyncMerge.kt:44-49`). Supabase
  auth replaces the principal model outright. Ticket 04 owns what happens to that sync path.
- **`DeviceId` is untouched by all of this.** It has exactly one reader
  (`ui/widgets/WidgetPagerScreen.kt:94`, for `widget_instances`), plays no part in auth, sync, or
  ownership, and ticket 01 ruling 10 already keeps widget layouts phone-only. It stays as-is.
- **`records` is not in the sync registry today**, nor are `companion_memories` or
  `episodic_turns`. Whatever ticket 05 sequences, it is not modifying an existing sync path for
  those - it is creating the first one.
