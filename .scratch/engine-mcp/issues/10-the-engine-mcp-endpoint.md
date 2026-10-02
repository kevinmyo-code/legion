---
map: engine-mcp
ticket: "10"
title: "The engine's MCP endpoint, read tools, and the tenancy leak test over it"
type: build
status: open
status-detail: ""
blockers: ["01", "03", "05", "06"]
blocked-by: ["[[01-where-the-mcp-server-lives]]", "[[03-one-source-for-tool-definitions]]", "[[05-auth-per-caller]]", "[[06-which-tools-and-the-honesty-contract]]"]
open-blockers: 4
ready: false
tags: [ticket]
---

# The engine's MCP endpoint

## Build (shape fixed by 01, 03, 05, 06)

- The MCP endpoint where 01 puts it, behind the switch 08 decides.
- The tool registry 03 chose, with the v1 read tools 06 approved. Each tool calls existing service
  code through `scoped()` / `household_of`; no tool queries a model directly.
- Auth as 05 rules; read-scope enforced if 05 adds scopes.
- The honesty contract from 06, enforced by a test over every tool's failure results.

## Verification

- **Tenancy leak test** (CLAUDE.md §7 checklist, ADR 0045): two households seeded, every tool called
  with a token from each, no row of the other household in any result. Extends `tests/test_tenancy.py`
  rather than living beside it.
- No tool in the registry is a write while 06 says v1 is read-only; a test asserts it.
- Gated tables remain unwritable through MCP (405-equivalent, in words).
- A real client (Claude Code) lists and calls the tools against a local compose stack and against
  Cloud Run.
- One server test DB at a time; `--no-reuse-db`.
