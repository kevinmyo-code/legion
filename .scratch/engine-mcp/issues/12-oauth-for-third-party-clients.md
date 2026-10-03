---
map: engine-mcp
ticket: "12"
title: "OAuth for third-party clients"
type: build
status: open
status-detail: ""
blockers: ["05", "08", "10"]
blocked-by: ["[[05-auth-per-caller]]", "[[08-public-exposure-and-clone-and-run]]", "[[10-the-engine-mcp-endpoint]]"]
open-blockers: 2
ready: false
tags: [ticket]
---

# OAuth for third-party clients

## Build (only if 05 picks Django as authorization server and 08 makes `/mcp` public)

- Protected Resource Metadata at `/.well-known/oauth-protected-resource`, pointing at the engine's
  own authorization server.
- Client registration by Client ID Metadata Documents; DCR only if 02 shows a target client needs
  it (deprecated in spec 2026-07-28, web-sourced).
- Consent screen in the web app naming the household being granted and the scope (read by default).
- Issued tokens resolve to a user and so to one household, and are revocable alone, like device
  tokens.
- Validate `iss` per RFC 9207 (spec 2026-07-28 minor change 7).

## Verification

- claude.ai (or another 02-listed client) connects, consents, lists and calls a read tool against
  Kevin's engine.
- A token revoked in the web app stops working on the next call.
- The tenancy leak test from 10 runs with OAuth-issued tokens too.
- A stranger's compose stack with no public HTTPS still runs; OAuth simply is not reachable, and the
  docs say so.
