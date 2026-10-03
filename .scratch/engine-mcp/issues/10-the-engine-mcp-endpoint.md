---
map: engine-mcp
ticket: "10"
title: "The engine's MCP endpoint, read and write tools, and the tenancy leak test over it"
type: build
status: built
status-detail: "Built and suite green 2026-10-02; owes LEGION_MCP=on on Cloud Run and a real Claude Code query against it"
blockers: ["01", "03", "05", "06"]
blocked-by: ["[[01-where-the-mcp-server-lives]]", "[[03-one-source-for-tool-definitions]]", "[[05-auth-per-caller]]", "[[06-which-tools-and-the-honesty-contract]]"]
open-blockers: 0
ready: false
tags: [ticket]
---

# The engine's MCP endpoint

## Build (shape fixed by 01, 03, 05, 06)

- The MCP endpoint where 01 puts it, behind the switch 08 decides.
- The tool registry 03 chose, with the v1 read and write tools 06 approved (Kevin, 2026-10-02: read AND write). Each tool calls existing service
  code through `scoped()` / `household_of`; no tool queries a model directly.
- Device-token auth (05), with a read/write scope on the token. A write tool refuses a read-scoped token in words.
- Writes go only through the REST views' own serializers; `destructiveHint` on deletes; no memory tables; no gated table writable.
- The honesty contract from 06, enforced by a test over every tool's failure results.

## Verification

- **Tenancy leak test** (CLAUDE.md §7 checklist, ADR 0045): two households seeded, every tool called
  with a token from each, no row of the other household in any result. Extends `tests/test_tenancy.py`
  rather than living beside it.
- Every write tool refuses a read-scoped token, and a refused or failed write says nothing was written; a test asserts both.
- Gated tables remain unwritable through MCP (405-equivalent, in words).
- A real client (Claude Code) lists and calls the tools against a local compose stack and against
  Cloud Run.
- One server test DB at a time; `--no-reuse-db`.

## Built (2026-10-02)

Server only, `server/engine_mcp/`. Shape as ruled by 01, 03, 05, 06, 07 and 08.

- **`/mcp` inside Django** (`views.py`), official SDK `mcp==2.2.0` pinned in `server/requirements.txt`, low-level `Server` driven once per request (`server.py`): `json_response`, `stateless_http`, no session id. The ORM runs on the request thread (`async_to_sync` out, `sync_to_async(thread_sensitive=True)` back), so a tool uses the request's own DB connection. Host checking is Django's `ALLOWED_HOSTS`; the SDK's 421 guard is off.
- **Off by default** (08): `LEGION_MCP=on` in the environment, else 404 in words. Documented in `deploy/.env.example`.
- **Device tokens only** (05), scope from migration `household/0004`. Session auth is not accepted. A write tool refuses a `read` token in words before running; `IsHouseholdMember` refuses it again inside the dispatched REST view.
- **Registry** (`tools.py`, 03): 11 tools. Reads: `list_tables`, `read_records`, `list_events`, `list_checklists`. Writes: `write_record`, `delete_record` (destructive), `add_event`, `update_event`, `delete_event` (destructive), `add_checklist_item`, `tick_checklist_item`.
- **Writes call the routed REST view** (`dispatch.py`: `django.urls.resolve` plus DRF forced auth), so serializers, tenancy and refusals are the REST ones. No second write path.
- **Gated tables unwritable**: no write tool lists them, and naming one returns the gate's own 405 sentence. **Memory tables excluded** (06.2). **No ingestion tool** (07). No mail (test).
- **Honesty**: every failure is `isError` with words; reads say provenance in words per row (`_engine_says`, UNRECONCILED reads UNVERIFIED), estimates labelled, empty distinguished from unreadable. Repeating events are listed, not expanded, and the result says so.
- **Throttle** per token (`McpTokenThrottle`, `LEGION_MCP_RATE`, default 60/min; per-process cache, so up to 2x with two workers). **Audit**: `public.mcp_calls`, one row per authenticated request (token, scope, method, tool, outcome), never arguments or results. In `TENANT_TABLES`.
- **Tests**: `tests/test_engine_mcp.py`; the leak test `test_every_mcp_tool_is_scoped_by_household` extends `tests/test_tenancy.py` and asserts every registry tool is covered.

**Verification steps, accounted for (L11):**

- Tenancy leak test: done.
- Write tools refuse a read token, and a refused or failed write says nothing was written: done.
- Gated tables unwritable in words: done.
- A real client lists and calls the tools against local compose and Cloud Run: **deferred**, owed. Needs `LEGION_MCP=on` on the service and a deploy, which this build did not do (no deploy by instruction). Then: `manage.py issue_device_token <email> --name "Claude Code"`, put URL and token in `.claude/mcp.env`, add an HTTP entry for `<url>/mcp` with `Authorization: Token ...`, list and call. The stdio adapter (09) stays the dev path until then.
- One test DB at a time, `--no-reuse-db`: done. Note: on the Supavisor pooler a finished run leaves an idle pooled session on `test_postgres` for 2-3 minutes, which blocks the next run's create; wait it out.

**Left out on purpose:** `maintenance_schedules` (two-part identity) and `voice_notes` (server-minted id) are readable but not writable over MCP; `obd_samples` is not exposed; `goals` has no route. Protected Resource Metadata and OAuth are ticket 12.

