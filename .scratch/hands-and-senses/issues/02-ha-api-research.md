---
map: hands-and-senses
ticket: 02
title: "What does Home Assistant's local API actually offer a phone voice client?"
type: research
status: resolved
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# What does Home Assistant's local API actually offer a phone voice client?

## Question

Settled decision 1: LEGION fronts Home Assistant rather than rebuilding device integrations. Before
[Home control scope](03-home-control-scope.md) can be argued, surface the facts from HA's own docs
(developers.home-assistant.io, home-assistant.io):

1. **Auth.** Long-lived access tokens: lifetime, revocation, scope granularity. Is there anything
   narrower than full-API access for a token? What does HA recommend for third-party clients?
2. **API surface.** REST vs WebSocket: which supports calling services (`light.turn_on`,
   `lock.lock`), reading entity states, and subscribing to state changes? What does a minimal
   "get states + call service" client need? Does the WebSocket API require keepalive that fights
   Android Doze?
3. **Discovery and reachability.** mDNS discovery on the LAN; what happens off-LAN - is Nabu Casa
   remote access the only sanctioned path, does it change auth, what does it cost? (A Kevin-paid
   subscription for Kevin's own hub is BYO, not a backend - but say what it costs.)
4. **The conversation/Assist layer.** HA has its own Assist API (intent handling, exposed
   entities). Could LEGION hand a raw utterance to HA's conversation endpoint instead of building
   service-call tools - and what would that lose (LEGION's own context, confirm turns)?
5. **Entity exposure model.** How does HA scope what a voice assistant may touch ("exposed
   entities")? Does that machinery work for a custom client, or is it Assist-only?
6. **Hardware floor.** Cheapest sanctioned ways to run HA today (Green, Yellow, Pi, container) and
   their prices, from HA's own store/docs.

Write findings to `research/02-ha-api.md`, cite every claim to the owning URL, then append the
Answer here and set Status: resolved.

## Answer

Full findings with per-claim citations: [research/02-ha-api.md](../research/02-ha-api.md).
Researched 2026-08-16 against developers.home-assistant.io, home-assistant.io, nabucasa.com.

1. **Auth.** Long-lived access tokens: 10-year lifetime, created on the profile page, revoked
   there; no per-token scoping exists - a token carries its user's full access. Only narrowing
   lever is a non-admin user account (no config/system access). HA's recommended third-party flow
   is OAuth2+IndieAuth (client ID = a website you own); for a personal BYO app the pasted LLAT in
   Keystore is the simpler sanctioned path, same shape as the Gemini key.
2. **API surface.** REST does auth-header get-states + call-service + conversation but has NO
   subscriptions (polling only). WebSocket adds subscribe_events/subscribe_trigger behind an
   auth_required/auth/auth_ok handshake. Ping/pong is client-optional; no documented required
   interval or server timeout. A pull-based client needs no persistent socket, so no Doze fight:
   REST per call covers the minimal client entirely.
3. **Reachability.** Zeroconf discovery of HA on LAN is on by default (exact service record not
   printed in primary docs). Off-LAN: Nabu Casa Cloud is the recommended zero-port-forward path
   at 6.50 USD/mo or 65 USD/yr, but VPN (Tailscale), reverse proxy, and port-forward+TLS are all
   sanctioned. Docs describe no auth change remotely - same bearer token, different URL.
4. **Assist layer.** `POST /api/conversation/process` takes raw text, returns speech +
   action_done/query_answer/error + conversation_id for multi-turn. Handing LEGION utterances to
   it would lose LEGION's cross-aspect context, persona, and any confirm-before-execute turn
   (action_done means it already happened). Middle path exists: HA serves its Assist tools over
   MCP at `/api/mcp/assist` - tool-shaped, exposure-enforced.
5. **Exposure model.** Settings > Voice assistants > Expose gates Assist/Google/Alexa (and the
   Assist LLM/MCP API). It does NOT gate raw REST/WebSocket - a LLAT client bypasses it with full
   user rights. Inherit the curated safe-set via conversation/MCP, or build LEGION's own
   allow-list for direct service calls.
6. **Hardware floor.** Cheapest sanctioned: container/VM on existing hardware, $0 (no add-ons in
   container). Cheapest appliance: Green, $199/179 EUR. Yellow production ended 2025-10-15. HAOS
   is the recommended install type; Pi flash-it-yourself remains sanctioned.
