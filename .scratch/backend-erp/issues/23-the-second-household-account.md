---
type: build
status: open
blocked_by: []
map: backend-erp
---

# The second household account, and the iPhone that reaches it

**Opened 2026-08-28 with [[0040-pc-is-the-primary-surface-phone-is-voice-first]]'s amendment. The
household has always been two adults; only one of them has an account.**

## What already exists, and it is more than expected

Ticket 02 designed for exactly this and none of it needs revisiting:

- **Email + password**, chosen over Google OAuth (two client IDs, one keyed to the SHA-1 cert - the
  trap already open against Drive) and over magic link (Supabase's built-in email is 2 msg/hour,
  "not meant for production", no delivery SLA).
- **Both accounts are created in the dashboard.** No signup screen, no invite flow, zero app code.
- **Household RLS, all users see all rows, no roles ever.** She sees what he sees, by design.
- **The absence of insert/update/delete policies on `household_members` IS the enforcement** -
  membership can only be granted in the dashboard.

So the server side is a dashboard task, not a build.

## What is actually owed

1. **Create the account** in the Supabase dashboard and insert its `household_members` row.
2. **Sign in on the web app** and confirm the membership check passes - the same check `SupabaseAuth`
   already implements for the Android app, which returns a sealed
   signed-in-but-not-a-member result rather than a bare failure.
3. **Confirm she sees the same rows**, which is the actual test of household RLS. Reading her own
   empty set would look identical to a working sign-in.

## The part that needs care, and it is not the account

**A hosted web app makes RLS internet-reachable rather than theoretical.** The anon key is public by
design; RLS plus auth is the whole boundary. Before the app has a public URL:

- Re-read every policy. Confirm `authenticated` is granted and `anon` is REVOKED at the GRANT level
  on every table - both layers were demonstrated independently for the fleet tables on 2026-08-27
  (`has_table_privilege('anon', 'SELECT')` false, `pg_policy` count 1) and the same evidence should
  exist for all of them, queried rather than assumed.
- Decide where the app is hosted and whether the URL is guessable. This is a household of two; an
  unlisted URL is not security, but it is not nothing either, and the honest answer is that RLS is
  the only real control.

## Not owed, and worth saying so

**No per-user data separation.** Ticket 02 ruled all users see all rows, no roles, ever. The one
place identity matters is **memories**, which bind to the USER and gained a user tag for attribution
- because recalling her statement as his is the unfalsifiable-memory failure CLAUDE.md section 7
forbids. That tagging already exists; nothing new is owed for it, but it is the one thing a second
account makes live rather than hypothetical, and it should be checked once she is real.
