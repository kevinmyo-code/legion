# Home Assistant local API: facts for a phone voice client

Date: 2026-08-16. Ticket: `../issues/02-ha-api-research.md`.
Sources: developers.home-assistant.io, home-assistant.io, nabucasa.com only. Every claim cited.
Claims the primary docs do not state are marked **unverified** or **reasoned**.

## 1. Auth

- Long-lived access tokens (LLAT): valid **10 years**. Created from the profile page
  ("Long-Lived Access Tokens" section) or via WebSocket command `auth/long_lived_access_token`.
  Token string is shown once, never stored server-side.
  https://developers.home-assistant.io/docs/auth_api/
- Revocation: delete from the same profile-page section (docs imply, do not spell out the
  mechanism). Legacy `POST /auth/token` with `action=revoke` is "deprecated but still works".
  https://developers.home-assistant.io/docs/auth_api/
- Scope granularity: **none per token**. A token carries its user's full access. Docs describe no
  narrower-than-user token. https://developers.home-assistant.io/docs/auth_api/
- Only narrowing lever is the **user**: non-administrator accounts "can use Home Assistant and see
  their own dashboards, but cannot reach the configuration and system settings"; admin toggle per
  account; local-only accounts exist.
  https://www.home-assistant.io/docs/configuration/user-configuration/
- A finer policy system exists (entity_ids/device_ids/area_ids/domains with read/control/edit,
  attached to groups; owner bypasses everything) but "in the current implementation this is
  limited to just entities" and it is a programmatic framework - no UI wiring documented.
  https://developers.home-assistant.io/docs/auth_permissions/
- Recommended flow for third-party clients: **OAuth2 + IndieAuth extension** - client ID is the
  client's website URL, no pre-registration; refresh tokens fetched via `authorization_code`
  grant at `/auth/token`. https://developers.home-assistant.io/docs/auth_api/
  - Fit note (reasoned): IndieAuth needs the client to own a public website for its client ID.
    For a personal BYO app, an LLAT pasted once and held in Keystore is the simpler sanctioned
    path, same shape as the Gemini key.

## 2. API surface: REST vs WebSocket

| Capability | REST | WebSocket (`/api/websocket`) |
|---|---|---|
| Read all states | `GET /api/states` | `get_states` |
| Read one state | `GET /api/states/<entity_id>` | - (use get_states) |
| Call service | `POST /api/services/<domain>/<service>` (`?return_response` optional) | `call_service` (with `service_data`, `target`) |
| Subscribe to state changes | **No. Polling only.** | `subscribe_events` / `subscribe_trigger` |
| Conversation | `POST /api/conversation/process` | `conversation/process` |

- REST auth: `Authorization: Bearer TOKEN` header. https://developers.home-assistant.io/docs/api/rest/
- REST explicitly does not support subscriptions or real-time notifications.
  https://developers.home-assistant.io/docs/api/rest/
- WebSocket handshake: server sends `auth_required`, client sends `auth` with the access token,
  server replies `auth_ok` or `auth_invalid` + disconnect. Every post-auth message carries a
  unique integer `id`. https://developers.home-assistant.io/docs/api/websocket/
- Keepalive: client MAY send `ping`, server answers `pong`. Docs specify **no required ping
  interval and no server timeout**. https://developers.home-assistant.io/docs/api/websocket/
- Doze (reasoned, not from HA docs): nothing in the protocol demands a persistent socket. A
  pull-based client can open the WebSocket per interaction (or use REST per call) and hold no
  connection across Doze. Persistent subscribe-and-listen is the only mode that fights Doze, and
  it is optional.
- Minimal "get states + call service" client: one HTTPS endpoint + LLAT covers it entirely over
  REST; WebSocket needed only if live state-change push is wanted.

## 3. Discovery and reachability

- LAN discovery: the `zeroconf` integration (enabled by default via `default_config`) "will also
  make Home Assistant discoverable for other services in the network".
  https://www.home-assistant.io/integrations/zeroconf/
  - The docs do not print the service type on that page or on
    https://developers.home-assistant.io/docs/network_discovery/ (that page is about HA finding
    devices). The commonly used `_home-assistant._tcp.local.` record is **unverified against
    primary docs**.
- Off-LAN, four sanctioned paths, in HA's own order:
  1. **Home Assistant Cloud (Nabu Casa)** - recommended; "remote access to your Home Assistant
     from anywhere, without opening any ports", unique remote URL, encrypted.
  2. VPN (Tailscale / ZeroTier named).
  3. Reverse proxy (HA must trust it).
  4. Port forwarding to 8123 + Let's Encrypt TLS.
  https://www.home-assistant.io/docs/configuration/remote/
- Nabu Casa is not the only path, just the sanctioned zero-config one. Docs do not state any auth
  change for remote access; the client simply targets the external/Cloud URL (set under
  Settings > System > Network). Same bearer token (reasoned from silence in the docs; docs do not
  claim otherwise). https://www.home-assistant.io/docs/configuration/remote/
- Cost: **6.50 USD/month or 65 USD/year** (EU 7.50/75 EUR, UK 6.50/65 GBP, CA 8.70/87 CAD). Trial
  offered; duration not stated on the pricing page. Includes remote access + cloud TTS/STT +
  assistant integrations. https://www.nabucasa.com/pricing/
- Fit note: a Kevin-paid subscription attached to Kevin's own hub is BYO, not a hosted backend.
  Tailscale is the zero-cost alternative on the same list.

## 4. Conversation / Assist layer

- Endpoint: `POST /api/conversation/process` or WebSocket `conversation/process`. Fields: `text`
  (required), `language`, `agent_id` (default `homeassistant`), `conversation_id`.
  https://developers.home-assistant.io/docs/intent_conversation_api/
- Response: `speech` (plain or SSML), `response_type` (`action_done` / `query_answer` / `error`),
  `data` (success/failed target lists), `conversation_id`; `continue_conversation` flag signals
  expected follow-up. Multi-turn works by echoing `conversation_id` back.
  https://developers.home-assistant.io/docs/intent_conversation_api/
- So LEGION *could* forward a raw utterance. What it would lose (reasoned):
  - LEGION's own cross-aspect context (car, ledger, pantry) - HA's agent knows only HA.
  - LEGION's confirm turns and persona - HA returns finished speech, `action_done` means the
    action already happened; no hook for a confirm-before-execute turn.
  - One-brain routing - two NLU layers means deciding per-utterance who parses it.
- The built-in agent is intent-scoped: "The Assist API is equivalent to the capabilities and
  exposed entities that are also accessible to the built-in conversation agent. No administrative
  tasks can be performed." https://developers.home-assistant.io/docs/core/llm/
- Alternative surface: HA exposes the Assist tools over **MCP** at `/api/mcp/assist`
  (admin token required per the LLM docs) - tool-shaped access with exposure enforced, without
  handing HA the whole utterance. https://developers.home-assistant.io/docs/core/llm/

## 5. Entity exposure model

- Setting lives at Settings > Voice assistants > Expose tab; per-entity, per-assistant (Assist,
  Google Assistant, Alexa). Deliberately restrictive so "sensitive devices, such as locks and
  garage doors" are not inadvertently voice-controllable.
  https://www.home-assistant.io/voice_control/voice_remote_expose_devices/
- Enforcement scope: exposure gates the **conversation/Assist path** (and the Assist LLM API /
  MCP endpoint, which follow the same exposed-entity set).
  https://developers.home-assistant.io/docs/core/llm/
- It does **not** gate raw REST/WebSocket calls: a LLAT client calling
  `POST /api/services/lock/lock` acts with the full rights of its user, exposure irrelevant
  (reasoned from the docs' framing; no doc claims exposure applies to the plain APIs). The only
  documented rights reduction for a raw client is a non-admin user account (§1).
- Consequence for LEGION: to inherit HA's curated safe-set, go through `conversation/process` or
  `/api/mcp/assist`; building own service-call tools means building own allow-list.

## 6. Hardware floor

| Option | Price | Status | Source |
|---|---|---|---|
| Home Assistant Green | **$199 / 179 EUR** MSRP (USD ex-tax) | Shipping; "easiest way to get started" | https://www.home-assistant.io/green/ |
| Home Assistant Yellow | - | **Production ended 2025-10-15**, remaining retailer stock only | https://www.home-assistant.io/blog/2025/10/15/yellow-end-of-life/ |
| Raspberry Pi (HAOS) | Pi at market price; HA charges nothing | Sanctioned flash-it-yourself | https://www.home-assistant.io/installation/ |
| ODROID / generic x86-64 (HAOS) | hardware at market price | Sanctioned | https://www.home-assistant.io/installation/ |
| Container (Docker) | free | Sanctioned but "don't have access to apps" (no add-ons), manual updates | https://www.home-assistant.io/installation/ |
| VM (macOS/Windows host) | free | Sanctioned | https://www.home-assistant.io/installation/ |

- HAOS "is the recommended installation type for most users".
  https://www.home-assistant.io/installation/
- Cheapest sanctioned zero-new-hardware floor: container/VM on an existing machine, $0. Cheapest
  appliance: Green at $199.

## Open / unverified

- mDNS service record name and TXT properties: not printed in primary docs.
- WebSocket server-side idle timeout: not documented.
- Nabu Casa trial length: not stated on the pricing page.
- Whether remote (Cloud) URLs accept LLATs identically to local: docs silent; no contrary claim.
