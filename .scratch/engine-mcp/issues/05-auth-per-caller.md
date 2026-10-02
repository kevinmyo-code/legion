---
map: engine-mcp
ticket: "05"
title: "Auth per caller, and what a token is allowed to do"
type: decision
status: open
status-detail: ""
blockers: ["02"]
blocked-by: ["[[02-sdk-django-and-client-support]]"]
open-blockers: 0
ready: true
tags: [ticket]
---

# Auth per caller, and what a token is allowed to do

## Question

How does each of the three callers authenticate to `/mcp`, how does that resolve to exactly one
household, and can a token be narrower than "everything this member can do"?

## What exists (traced 2026-10-02)

`DeviceTokenAuthentication`: `Authorization: Token <key>`, one token per device, revocable alone,
resolving to a `User`, whose household `household_of` returns. No notion of scope: every token can
do everything its user can. No OAuth anywhere.

## Sub-questions

1. **Phone.** Reuse its device token. Recommendation: yes; nothing new.
2. **Claude Code.** A device token issued for it, in an env var, never committed (the `.mcp.json`
   `launch.py --require` pattern already does this for `LEGION_PG_URL`). **Against which data?**
   Kevin's live household on Cloud Run, or a local compose stack with seeded rows? Reading the live
   one means a development session reads his real ledger, receipts and memories. Recommendation:
   live, read-only, because "query it while we're building" is about real data - but this is a
   privacy call and it is Kevin's.
3. **Token scope.** Add a `scope` to `DeviceToken` (`read` / `write`)? Recommendation: yes, before
   any write tool ships. A dev token and a third-party token should be read-only by default; a
   leaked read token is bad, a leaked write token is worse.
4. **Third-party clients.** Spec 2026-07-28 expects OAuth 2.1 with Protected Resource Metadata;
   DCR is deprecated for Client ID Metadata Documents (web-sourced). Options:
   - (a) none: device tokens only, third parties wait;
   - (b) Django becomes the authorization server (`django-oauth-toolkit` or similar), consent screen
     in the web app, tokens bound to user and so to household;
   - (c) an external IdP.
   (c) is a Kevin-hosted or vendor dependency for every household, which §7 and clone-and-run
   argue against. Recommendation: (a) now, (b) as ticket 12 once 08 says the surface is public.
5. **Household with more than one family** (ADR 0045): an OAuth consent must show which household
   the client is being granted. A member of one household only, so it is one name, but it must be
   said.

## Resolution

Kevin rules on 2, 3 and 4.
