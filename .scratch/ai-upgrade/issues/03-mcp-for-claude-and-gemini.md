---
map: ai-upgrade
ticket: "03"
title: "Build: engine MCP server on the 2026-07-28 spec with OAuth, connected to Claude and the Gemini app"
type: build
status: kiv
status-detail: "Kevin 2026-10-23: stopped; Claude Code already uses the engine MCP. Unverified OAuth WIP parked on branch park/mcp-oauth-wip (ab93a9bc)"
blockers: ["01"]
blocked-by: ["[[01-what-to-upgrade]]"]
open-blockers: 0
ready: false
tags: [ticket]
---

# Build: LEGION reachable from Claude and the Gemini app

Kevin, 2026-10-22: picked Claude and the Gemini app. He asked whether the Gemini app means *"the literal
gemini app on my phone? so can i pull up gemini live voice session and ask it questions about my legion
data"*.

## Rulings

1. Bring `server/engine_mcp/` up to the MCP 2026-07-28 revision: stateless (no initialize handshake,
   no session id), the required `Mcp-Method` / `Mcp-Name` headers, and JSON Schema 2020-12 tool
   schemas.
2. Add the auth that each client actually requires, verified against **current** docs, not memory:
   - Claude custom connectors (claude.ai and the desktop app).
   - Gemini app "custom apps".
   - Expected: OAuth 2.1 with Client ID Metadata Documents.
   - **Household tenancy (ADR 0045) holds under OAuth**: a token maps to one LEGION user, and every
     tool is scoped to that user's household at the existing choke point. The leak test covers the
     OAuth path.
3. The tool set is unchanged. Every write tool's result still says in words what was committed
   (§7 outcome verbs), and the client's own per-call approval prompt stays on.
4. **Find out and record** whether the Gemini app's custom apps work inside a **Gemini Live voice**
   session on Android, or only in typed chat, and the eligibility rules (region, age, Keep Activity).
   This answers Kevin's question in words, with a source.

## Verification

Server suite green, including the tenancy leak test over OAuth. Then Kevin adds the connector in
Claude and in the Gemini app and asks one question in each. That last step is his, and it is owed.

## Research, 2026-10-22 (before any code)

Build stopped here: the findings open forks this ticket does not settle (see Open questions).
Sources were fetched 2026-10-22; "undated" means the page carries no date.

**Ruling 4, answered: Gemini Live does not run custom apps today.** The Gemini Live help page
(support.google.com/gemini/answer/15274899, undated) lists a closed set of Live apps (Calendar,
Keep, Tasks, Home, Spotify, YouTube and others) and never mentions custom apps or MCP. Custom apps
are typed chat only, invoked with `@`. They are connected in the Gemini WEB app and then appear in the
Android app (support.google.com/gemini/answer/17209137, undated: "You can only connect custom apps in
the Gemini web app. Once connected, they're available on both the Gemini mobile app and Gemini web
app."). Eligibility from the same page: 18 or over, in the US, personal Google Account (not work or
school), Keep Activity ON ("Custom apps are unavailable when this setting is off"), English only.
Whether a paid tier (AI Pro/Ultra) is required: Google's page names none, third-party guides say
paid; unverified.

**MCP 2026-07-28** (blog.modelcontextprotocol.io/posts/2026-07-28/, 2026-07-28; spec at
modelcontextprotocol.io/specification/2026-07-28/): `initialize` and `Mcp-Session-Id` retired;
protocol version and client capabilities ride in every request's `_meta`; `MCP-Protocol-Version`,
`Mcp-Method` and `Mcp-Name` headers required, mismatch is 400 with `-32020`; `server/discover`
required; `resultType`, `ttlMs`, `cacheScope` on results. A "dual-era" server may serve legacy
(`initialize`, 2025-11-25 and earlier) and modern clients on one endpoint, and legacy clients keep
working "for at least twelve months".

**Claude custom connectors** (claude.com/docs/connectors/building/authentication.md, undated):
OAuth with CIMD or DCR both "supported by default"; Claude picks CIMD only when the authorization
server metadata advertises `client_id_metadata_document_supported: true` AND `none` in
`token_endpoint_auth_methods_supported`, else DCR. Redirect URI `https://claude.ai/api/mcp/auth_callback`.
Claude's hosted client id (probed with curl, undocumented): `https://claude.ai/oauth/mcp-oauth-client-metadata`.
A static bearer header is a beta, org-gated, organisation-wide credential, not per user. Added on
the web, a connector works in the Android app (support.claude.com/en/articles/11176164). Voice mode
uses "the tools you've connected" (support.claude.com/en/articles/11101966, 2026-09-16), with no
explicit statement about custom connectors. Which MCP protocol era Claude's connector client speaks:
not stated anywhere; Anthropic's 2026-07-28 post says support is "rolling out"; treat as legacy
(`initialize`).

**Gemini custom apps auth** (answer/17209137): Dynamic Client Registration, else the user types a
client id and secret under Advanced features. No CIMD found in any Google source. Redirect URI not
documented by Google; third-party reports give a per-user
`https://oauth-redirect.googleusercontent.com/r/user_bound_custom-mcp-...` form (unverified).

## Open questions for Kevin (the build waits on these)

1. **Gemini is typed chat only.** The voice use you asked about ("pull up gemini live and ask about my
   legion data") is not possible today. Keep Gemini as a typed connector, or drop it and build for
   Claude only?
2. **DCR.** Gemini needs Dynamic Client Registration, which the 2026-07-28 spec deprecates in favour
   of CIMD, and its redirect URIs are per-user Google relay links that can only be allowlisted by
   prefix. Build DCR (only if Gemini stays), or CIMD only (Claude)?
3. **Who is the OAuth authorization server:** LEGION's own Django (new endpoints, login and consent
   page on the existing web sign-in, token store; likely a library such as django-oauth-toolkit,
   a new dependency) or an external IdP in front?
4. **Protocol era:** serve both legacy `initialize` and 2026-07-28 stateless on `/mcp` (needed while
   Claude rolls out), confirmed? Note: `/mcp` may already do this. `server/requirements.txt` pins the
   MCP SDK 2.2.0 as "spec 2026-07-28 plus legacy clients on one endpoint", and
   `tests/test_engine_mcp.py` already exercises a `2026-07-28` envelope and `server/discover`.
   Ruling 1 may then be mostly met, and the remaining work is auth. Not re-verified against the
   spec's header and error rules.

## Kevin's answers (2026-10-22), binding for the build

1. **Claude AND Gemini (typed chat).** Gemini Live voice cannot reach custom apps
   (support.google.com/gemini/answer/15274899, above); Gemini is a typed connector.
2. **LEGION's own Django is the authorization server**: new endpoints, login and consent on the
   existing web sign-in, a token store. django-oauth-toolkit is acceptable. No external IdP.
3. **Both CIMD and DCR.** CIMD for Claude (advertise `client_id_metadata_document_supported: true`
   and `none` in `token_endpoint_auth_methods_supported`); DCR for Gemini. DCR redirect URIs are
   allowlisted to `https://claude.ai/api/mcp/auth_callback` and the prefix
   `https://oauth-redirect.googleusercontent.com/r/`, the allowlist in settings. Every registered
   client is listed and revocable (admin page or a manage.py command).
4. **`/mcp` stays dual-era** (legacy `initialize` and 2026-07-28); device-token auth for the
   existing `legion-engine` MCP keeps working unchanged.
5. **Security:** PKCE S256 required; RFC 8707 `resource` with an audience check; RFC 9728
   protected-resource metadata; short-lived access tokens and rotating refresh tokens; one token is
   one LEGION user, scoped through the existing household choke point; a tenancy leak test over the
   OAuth path; a test that a DCR registration with a non-allowlisted redirect is refused; the
   consent page says in words what the app will be able to do.
