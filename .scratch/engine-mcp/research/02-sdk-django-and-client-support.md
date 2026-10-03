# What the SDK, Django and the target clients support at spec 2026-07-28

Research for `.scratch/engine-mcp/issues/02-sdk-django-and-client-support.md`.
Researched 2026-10-02. Facts, not a recommendation.

Every claim is tagged:

- `documented` - read on the owner's own page, URL given, fetched 2026-10-02
- `tested` - run in a throwaway venv on this machine (Windows, Python 3.13), 2026-10-02
- `traced` - read in the installed package source (`mcp` 2.2.0, `django-mcp-server` 0.5.7)
- `reasoned` - inferred from the above, not observed
- `secondhand` - a third-party write-up; no primary source found

The probe scripts are in the session scratchpad, not the repo. The probe that matters is
reproduced as a sketch in section 2.

## Headline

1. **Spec 2026-07-28 is stateless by design.** No `initialize`, no `Mcp-Session-Id`, no GET stream.
   Each request is a POST that carries its own version and capabilities in `_meta`. `server/discover`
   is a MUST. The server may answer with a single `application/json` object. `documented`
2. **SDK 2.2.0 serves both eras on one endpoint** with nothing configured. It is ASGI-only on
   paper, **but a Django WSGI view can drive it**: one request through `asyncio.run`, with a fresh
   session manager each time, answered `server/discover`, `tools/list`, `tools/call` and a legacy
   `initialize`, all as `200 application/json`, 2 to 24 ms each. `tested`
3. **`django-mcp-server` is broken against SDK 2.x today.** It pins `mcp>=1.8.0` with no upper
   bound, so a fresh install pulls 2.2.0, and then `from mcp.server import FastMCP` raises
   `ImportError` at import time. Last release 0.5.7 (2025-10-10), last commit 2026-03-10. Its auth
   docs cite spec 2025-03-26. `tested`
4. **Every OAuth-capable client still accepts DCR. CIMD support is uneven.** Claude (all surfaces)
   and ChatGPT do both. Gemini CLI documents DCR and pre-registered client IDs only. The Gemini app
   documents DCR and a manual-credentials fallback. **So an engine that offers only CIMD locks out
   Gemini, and one that offers only DCR works everywhere today.** `documented` for each client,
   `reasoned` for the conclusion
5. **Static `Authorization` header (no OAuth):** Claude Code yes, Gemini CLI yes, Gemini
   Interactions API yes. claude.ai is beta for a limited set of organizations. ChatGPT says no
   ("cannot present custom API keys"). The Gemini app documents no header option. `documented`
6. **Gemini Live does not speak MCP on the wire.** The Live tool table lists Search and function
   calling only. The Python `google-genai` Live client turns an MCP `ClientSession` into function
   declarations on the client side before it sends `setup`. A raw-WebSocket client, which is what
   the phone is, gets nothing from that. The Gemini API's own server-side remote MCP exists, but in
   the **Interactions API**, not Live. `documented` + `traced`
7. **Cloud Run will not get in the way of a JSON response.** 32 MiB per HTTP/1 request and per
   non-streamed response, 300 s default request timeout. The binding limit is gunicorn's
   `--timeout 60`, which is below claude.ai's 240 s tool-call timeout. `documented`

---

## 1. What spec 2026-07-28 requires of a server

Source: changelog https://modelcontextprotocol.io/specification/2026-07-28/changelog, transport
https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http,
versioning https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning. All
`documented`.

### Transport (Streamable HTTP)

- One MCP endpoint that accepts POST. Each JSON-RPC request is its own POST.
- On a request, the server "**MUST** return either `Content-Type: application/json` (a single JSON
  object) or `Content-Type: text/event-stream`". The client must support both. **Plain JSON is fully
  conformant.**
- No GET stream and no sessions. A server that speaks only this revision **SHOULD** answer
  GET/DELETE with 405, ignore `Mcp-Session-Id`, and ignore `Last-Event-ID`.
- Required request headers: `MCP-Protocol-Version` (must equal `_meta`'s
  `io.modelcontextprotocol/protocolVersion`), `Mcp-Method` on every request, and `Mcp-Name` on
  `tools/call`, `resources/read` and `prompts/get`. A mismatch or a missing header is
  `400` + `-32020 HeaderMismatch`.
- The server **MUST** validate `Origin` and answer 403 if it is invalid.
- A long-lived stream exists only for `subscriptions/listen`, which is opt-in for change
  notifications. The server-to-client requests (sampling, elicitation, roots) are now "multi
  round-trip": an `InputRequiredResult` comes back and the client retries. There is no server push.
- `server/discover` is a **MUST**. It advertises versions, capabilities and identity.
- Every result carries `resultType`. List results carry `ttlMs` and `cacheScope`.
- Tasks moved to the `io.modelcontextprotocol/tasks` extension. Roots, Sampling and Logging are
  deprecated.

### Back-compat (ticket question 4)

- Supporting legacy clients is **MAY**: "A server that wishes to support both legacy clients ...
  and modern clients ... **MAY** implement both behaviors." A modern-only server **SHOULD** name its
  supported versions in any error it returns to `initialize`.
- Compatibility matrix: Legacy client against Modern server **fails** ("Legacy clients have no
  fall-forward mechanism"). Legacy client against a Dual-era server works.
- **So it is not required, but any client still on 2025-11-25 cannot reach a modern-only server.**
  claude.ai documents its OAuth against 2025-03-26 through 2025-11-25 and says nothing of 2026-07-28
  (section 4), so being dual-era is the safe default. `reasoned`
- **The SDK does it for free** (section 2).

### Auth

Source: https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization and
`.../authorization/client-registration`. `documented`.

- "Authorization is **OPTIONAL**." For stdio the client "**SHOULD NOT** follow this specification,
  and instead retrieve credentials from the environment."
- When an HTTP server does do auth, it is an OAuth 2.1 **resource server**. It:
  - **MUST** implement Protected Resource Metadata (RFC 9728, `/.well-known/oauth-protected-resource`);
  - answers 401 with `WWW-Authenticate: Bearer resource_metadata="..."` (and SHOULD add `scope=`);
  - **MUST** validate that the token's audience is this server (RFC 8707);
  - **MUST NOT** accept or pass through any other token.
- The authorization server can live on the same host or a different one. It **MUST** publish RFC
  8414 or OIDC discovery and implement OAuth 2.1 with PKCE.
- Client registration has three ways in. Clients **SHOULD** try them in this order:
  pre-registered, then CIMD (if the AS advertises `client_id_metadata_document_supported: true`),
  then DCR (if `registration_endpoint` exists), then ask the user.
- **DCR is Deprecated**, "retained for backwards compatibility". The spec's new deprecation policy
  sets a minimum 12-month window before removal. Authorization servers "**SHOULD** support" CIMD and
  "**MAY**" support DCR.
- Under CIMD the AS fetches the client's metadata URL. That means **the engine would make outbound
  HTTPS fetches to `claude.ai`, `chatgpt.com` and the like** and has to validate `redirect_uris`
  against what it gets back. `reasoned` from the spec text.

## 2. The official `mcp` Python SDK 2.x (ticket question 1)

- **Version:** 2.2.0, released 2026-09-07. Earlier releases: 2.0.0 (2026-07-28), 2.0.1, 2.1.0,
  2.1.1. It claims 2026-07-28 support. https://pypi.org/project/mcp/ `documented`
- **v2 breaks v1 imports.** `FastMCP` is now `MCPServer` (`mcp.server.mcpserver`), and
  `mcp.server.fastmcp` raises on import. `stateless_http` and `json_response` moved from the
  constructor to `run()` / `streamable_http_app()`. The types live in a separate `mcp-types`
  package. https://py.sdk.modelcontextprotocol.io/migration/ `documented`. **The repo's `tools/mcp/`
  servers pin `mcp>=1.2,<2` and are unaffected.**
- **The docs say ASGI only.** "`mcp.streamable_http_app()` returns a Starlette application." The
  host app's lifespan must enter `mcp.session_manager.run()`, or the request fails with "Task group
  is not initialized". No mention of WSGI or Django.
  https://py.sdk.modelcontextprotocol.io/run/asgi/ `documented`
- **Modern requests need no state:** "on 2026-07-28 you are already stateless, with nothing to
  configure." Only legacy clients get a session, which needs sticky routing unless
  `stateless_http=True`. https://py.sdk.modelcontextprotocol.io/run/deploy/ ,
  https://py.sdk.modelcontextprotocol.io/run/legacy-clients/ `documented`
- **Era routing is decided by a header** (`traced`, `mcp/server/streamable_http_manager.py`
  `_handle_request`). If `MCP-Protocol-Version` is present and is not one of the handshake versions,
  the request goes to `handle_modern_request`. Otherwise it takes the legacy stateless or stateful
  path. `run()` "can only be called once per instance".

### Can it run under the existing gunicorn WSGI Django? `tested`

Probe: build one `MCPServer`. Then, **per request, from synchronous code**: call
`streamable_http_app(json_response=True, stateless_http=True)`, enter `session_manager.run()`, and
hand the ASGI app a hand-built scope, all inside `asyncio.run(...)`. This is the same shape
`django-mcp-server` uses with `async_to_sync`.

| Request | Result |
|---|---|
| `server/discover` (2026-07-28) | `200 application/json`, `supportedVersions: ["2026-07-28"]`, 24 ms (first call) |
| `tools/list` | `200 application/json`, 2 ms |
| `tools/call add(2,3)` | `200 application/json`, `structuredContent {"result":5}`, 4 ms |
| legacy `initialize` (2025-11-25, no version header) | `200 application/json`, **no** `Mcp-Session-Id` minted |
| request with `Host: <name>.a.run.app` | **`421 Invalid Host header`**. The default DNS-rebinding guard allows localhost only, so the engine must pass `transport_security` with its public host |
| request missing `Mcp-Method` | `400`, `-32020` "mcp-method header does not match" |

What the probe did not test, so none of this is claimed:

- **real Django** (only the SDK, driven synchronously);
- gunicorn;
- concurrency;
- the cost of building the Starlette app on every request;
- the DB connection behaviour.

Two consequences for whoever builds it. Both `reasoned`, from `traced` facts:

- **Sync tool functions run on a worker thread.** `MCPServer` calls them with
  `anyio.to_thread.run_sync` (`mcpserver/resolve.py`). So Django ORM calls inside a tool are legal
  (no `SynchronousOnlyOperation`), but they run on a different thread from the request. The DRF
  `request` that `household_of(request)` needs is **not** visible to the tool unless something
  passes it in (a contextvar, or a closure set up by the view).
- **Auth can happen in Django before the SDK runs.** A DRF-authenticated Django view can reject the
  request with 401 itself and only then hand over to the SDK. Device tokens therefore never need the
  SDK's `TokenVerifier`.

### `TokenVerifier` and Protected Resource Metadata

Source: https://py.sdk.modelcontextprotocol.io/run/authorization/ (`documented`) and
`mcp/server/auth/provider.py` (`traced`).

- `async def verify_token(self, token: str) -> AccessToken | None`. `AccessToken` carries `token`,
  `client_id`, `scopes`, `resource`, `subject`, `expires_at` and `claims`.
- `AuthSettings(issuer_url, resource_server_url, required_scopes, validate_token_resource=...)`.
  With `validate_token_resource`, the bearer middleware refuses a token whose `resource` is not
  `resource_server_url`. Without it, the audience check is the verifier's job.
- **Yes, the SDK serves PRM** at `/.well-known/oauth-protected-resource/<path>` (RFC 9728 path
  insertion, `auth/routes.py`) and sends 401 + `WWW-Authenticate`. **Both are routes inside its
  Starlette app.** Mounted behind Django, the `/.well-known/...` path has to be routed to it as well,
  or Django has to serve the JSON itself. `reasoned`
- The SDK can embed an authorization server (`auth_server_provider=`), but its docs say "New
  servers should not reach for it." **The SDK is a resource server. Issuing tokens is somebody
  else's job** (django-oauth-toolkit, or an external IdP). `documented`

## 3. `django-mcp-server` (ticket question 2)

| Fact | Value | Tag |
|---|---|---|
| Latest release | 0.5.7, 2025-10-10 (PyPI) | `documented` https://pypi.org/project/django-mcp-server/ |
| Last commit on main | 2026-03-10 ("Fix DRF Integration Body Processing...") | `documented` GitHub API |
| SDK dependency | `mcp>=1.8.0`, no upper bound | `documented` PyPI `requires_dist` |
| Works with SDK 2.x? | **No.** A fresh `pip install django-mcp-server` resolves `mcp 2.2.0`, then `import mcp_server.djangomcp` fails: `ImportError: cannot import name 'FastMCP' from 'mcp.server'` | `tested` |
| Spec revision | Auth docs point at 2025-03-26. Its stateful mode stores state in Django sessions keyed by `Mcp-Session-Id`, a header 2026-07-28 removed | `documented` + `traced` |
| WSGI | Yes. `async_to_sync` around the SDK's `StreamableHTTPSessionManager.handle_request`, with a new manager per request | `traced` (`mcp_server/djangomcp.py`) |
| DRF auth | `DJANGO_MCP_AUTHENTICATION_CLASSES` takes DRF authentication class paths, so `DeviceTokenAuthentication` would plug in unchanged **on SDK 1.x** | `documented`; unchanged-on-1.x is `reasoned` |

**Usable only pinned to `mcp<2`, which is a 2025-11-25-era SDK, so not a 2026-07-28 server.**
Its WSGI bridge shows the technique works, and section 2's probe reproduces the same technique
on 2.2.0. `reasoned`

Not checked: the 10 open issues and 9 open PRs, which may include an SDK 2 port.

## 4. Clients (ticket question 3)

"Spec speaks" is what the vendor states. Where a vendor states nothing, the cell says so.

| Client | Transports | Spec revision stated | Static `Authorization` header | OAuth registration |
|---|---|---|---|---|
| **Claude Code** (2.1.287 on this machine) | stdio, HTTP, SSE (deprecated), WebSocket | v1 runtime on TS SDK 1.x. v2 runtime on TS SDK 2.0 "with protocol revision 2026-07-28", chosen by `MCP_SDK_GENERATION` / feature flag from v2.1.232 | **Yes**: `--header "Authorization: ..."`, `${VAR}` expansion in `.mcp.json`, or a `headersHelper` script | DCR, CIMD (its own document at `https://claude.ai/oauth/claude-code-client-metadata`), pre-registered `--client-id/--client-secret`. Loopback redirect on any port |
| **claude.ai / Desktop / mobile** custom connector | Streamable HTTP. Legacy HTTP+SSE still accepted | OAuth follows "2025-03-26, 2025-06-18, and 2025-11-25". **2026-07-28 not stated** | **Beta, limited orgs only** ("Request headers", up to four). Not available alongside OAuth for `Authorization` | DCR (default), CIMD, or your own client ID. CIMD only if the AS advertises `client_id_metadata_document_supported: true` **and** `none` in `token_endpoint_auth_methods_supported`, otherwise it falls back to DCR. Redirect `https://claude.ai/api/mcp/auth_callback` |
| **ChatGPT** developer mode (Plus/Pro/Business/Enterprise/Edu, **web only**) | SSE, Streamable HTTP | Its auth guide cites the 2025-11-25 auth spec and 2026-07-28's response validation | **No.** "cannot present custom API keys or customer-provided mTLS certificates". Mixed mode allows unauthenticated tools | CIMD (preferred), DCR, or predefined static client. Redirect `https://chatgpt.com/connector_platform_oauth_redirect` (with RFC 9207 `iss`) or `.../connector/oauth/{callback_id}` |
| **Gemini app** (custom apps; US, 18+, personal account, Keep Activity on, English) | Remote MCP URL, HTTPS. Web and mobile | Not stated | Not documented | DCR. If no DCR, enter credentials manually under "Advanced features". CIMD not mentioned |
| **Gemini CLI** | stdio, SSE (`url`), Streamable HTTP (`httpUrl`) | Not stated | **Yes**, `headers` per server | DCR, pre-configured `oauth.clientId`, RFC 9207 `iss` check. CIMD not mentioned |
| **Gemini API, Interactions API** | Streamable HTTP only (SSE not supported). **Google's servers call the MCP server**, so it must be public | Not stated | **Yes**, a `headers` object on the `mcp_server` tool | n/a (headers only) |
| **Gemini API, `google-genai` Python SDK** (experimental) | Whatever the `mcp` client session uses. Client side | n/a | n/a | n/a |
| **Gemini Live API** | **No MCP.** Tools: Search, function calling (3.1 Flash Live: sync only) | n/a | n/a | n/a |

Sources, all `documented` 2026-10-02:

- Claude Code: https://code.claude.com/docs/en/mcp
- claude.ai auth: https://claude.com/docs/connectors/building/authentication.md
- claude.ai limits and spec: https://claude.com/docs/connectors/building/index.md
- claude.ai dialog: https://claude.com/docs/connectors/custom/add-unlisted.md
- ChatGPT: https://developers.openai.com/api/docs/guides/developer-mode.md and
  https://developers.openai.com/apps-sdk/build/auth
- Gemini app: https://support.google.com/gemini/answer/17209137
- Gemini CLI: https://geminicli.com/docs/tools/mcp-server/
- Interactions remote MCP: https://ai.google.dev/gemini-api/docs/function-calling (section "Remote
  MCP")
- Live tools: https://ai.google.dev/gemini-api/docs/live-api/tools
- SDK: https://github.com/googleapis/python-genai README "Model Context Protocol (MCP) support
  (experimental)"

Other limits worth knowing:

| Limit | claude.ai and Desktop | Claude Code |
|---|---|---|
| Tool result size | ~150,000 chars | 25,000 tokens (`MAX_MCP_OUTPUT_TOKENS`) |
| Tool call timeout | 240 s | `MCP_TOOL_TIMEOUT`. HTTP idle timeout 5 min |
| OAuth endpoints | discovery, registration and token must answer within 10 s; refresh within 30 s | same |
| Egress range | `160.79.104.0/21` | the user's machine |

Also, for claude.ai: it uses only the first entry of `authorization_servers`, needs a 401 (not a
200) to start sign-in, and does not support resource subscriptions or sampling.

**What each client means for the engine's auth** (`reasoned` from the table):

- Only Claude Code, Gemini CLI and the Interactions API can use today's device token
  (`Authorization: Token <key>`) unchanged.
- claude.ai can do so only if the org is in the header beta. ChatGPT and the Gemini app need OAuth.
- An authorization server that offers **DCR** reaches every OAuth client in the table. One that
  offers **CIMD only** reaches Claude and ChatGPT, but not the Gemini app or Gemini CLI as
  documented.
- DCR is deprecated but not removed, with at least a 12-month window.

## 5. Gemini Live and MCP (ticket question 5)

- The Live tools page lists Search and function calling as supported, and Maps, code execution and
  URL context as not supported. **MCP does not appear.** The Live API reference's `setup.tools[]` is
  the generic `Tool`. https://ai.google.dev/gemini-api/docs/live-api/tools ,
  https://ai.google.dev/api/live `documented`
- `google-genai`'s `live.py` (last changed 2026-09-09) checks for an `mcp.ClientSession` in `tools`
  and replaces it with converted function declarations before it sends `setup`. It also sets an
  MCP-usage header on the WebSocket. **The conversion and the tool calls happen in the client
  process. The wire carries plain function calling.** `traced`
- The server-side remote MCP (`"type": "mcp_server"` with `url` and `headers`) is documented for the
  **Interactions API**, not for Live. `documented`
- **Conclusion:** a Kotlin raw-WebSocket Live session can reach engine tools only by declaring them
  as functions and executing the calls itself. **Ticket 04's bridge is the only option for Live.**
  The Interactions API's remote MCP is a possible route for a non-Live, text Gemini call. `reasoned`
- Whether the Gemini app's custom apps work inside Gemini Live (the app's voice mode) is **not
  documented either way**.

## 6. Cloud Run and gunicorn (ticket question 6)

Sources: https://docs.cloud.google.com/run/quotas ,
https://docs.cloud.google.com/run/docs/configuring/request-timeout (`documented`).

- **Size:** 32 MiB per HTTP/1 request and per HTTP/1 response, the response limit applying when it
  is not chunked or streamed. No limit on HTTP/2. A JSON tool result is far below either, and
  claude.ai caps results at ~150k chars anyway.
- **Request timeout:** 300 s by default, 3600 s maximum. Google recommends retries above 15 min.
- **The binding limit is gunicorn `--timeout 60`.** A sync worker that is silent for 60 s is
  killed. So any tool call over 60 s dies there, which is below claude.ai's 240 s.
  `subscriptions/listen` (a long-lived SSE stream) would pin a sync worker and be killed at 60 s;
  with 2 workers, two open listens would starve the engine. `reasoned`
- With `json_response=True` and no `subscriptions/listen`, nothing in the spec needs a connection
  longer than one tool call. `reasoned` from section 1

## Assumptions ledger

| Claim | Tag |
|---|---|
| Spec 2026-07-28 requirements, back-compat MAY, DCR deprecated | `documented` |
| SDK 2.2.0 answers modern and legacy requests as JSON when driven synchronously per request | `tested` (SDK only; not Django, not gunicorn) |
| Default SDK host guard returns 421 for a non-localhost Host | `tested` |
| django-mcp-server 0.5.7 fails to import against mcp 2.2.0 | `tested` |
| Sync tools run on a worker thread, so `request` is not in scope | `traced`, consequence `reasoned` |
| SDK serves PRM and 401 inside its Starlette app | `documented` + `traced` |
| Client capability rows | `documented` on each vendor's page, 2026-10-02. Not exercised against a server |
| claude.ai hosted surfaces may still speak a pre-2026-07-28 revision | `reasoned` from the auth-spec list naming no 2026-07-28 |
| Gemini Live has no MCP on the wire | `documented` (absence from the tool list) + `traced` (SDK converts client side) |
| Gemini app custom apps in Gemini Live | unknown, not documented |
| Third-party "how to add MCP to Gemini" blogs | `secondhand`, not used for any row |
