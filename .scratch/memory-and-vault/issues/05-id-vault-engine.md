---
map: memory-and-vault
ticket: "05"
title: "ID vault on the engine"
type: build
status: built
status-detail: "engine built 2026-11-05 on feat/memory-vault-engine, suite green; owed: engine deploy (Kevin), then the phone tickets"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# ID vault on the engine

## What

New Django app `server/id_vault/`.

- `vault_items`: member, label (unique per member, case-insensitive), kind (`passport`, `nric`,
  `ssn`, `a_number`, `bank_account`, `other`), `ciphertext` (Fernet under `LEGION_VAULT_KEY`, the
  helper Plaid tokens already use), created, updated, last revealed.
- `vault_reveals`: one row per reveal, label and time, never the value.
- Endpoints (`/api/vault/items`): list labels (no values), create, update, delete (hard), and
  `POST /api/vault/items/<id>/reveal`.
- **Write-only for everything but the reveal.** Create and update answer with the label and
  metadata only, never the value. That is what lets ticket 15 (web entry, KIV) land with no engine
  change: a browser session may list labels, create and update; it may never reveal or delete.
- **Who reveals:** a member's own device token only. Refused: a browser session (never on the
  web), any `claude-session-*` token or token bound to a Claude machine (never readable by
  Claude, on every vault route), recruiters, and every other member (each item is one member's).
- **Reveal** needs the header `X-Legion-Unlocked: biometric`, which the phone sets after
  `BiometricPrompt` succeeds. **The server cannot verify a fingerprint**: the header says the phone
  says so, and the guarantee rests on the phone (ADR 0076). Rate limit 10 reveals an hour per
  member. `Cache-Control: no-store` on the reveal.
- Never in: tool results, logs, device logs, MCP, openapi examples, recruiter or worker scopes.
  No admin registration, no engine tool.

## Verification

- pytest: values absent from every captured log record at DEBUG, from openapi.yaml, from
  `tools/list`, from list/create/update responses; reveal without the header refused; reveal by a
  browser session, a worker token, a recruiter or another member refused; rate limit; audit row
  carries no value; no key refuses in words and stores nothing.
