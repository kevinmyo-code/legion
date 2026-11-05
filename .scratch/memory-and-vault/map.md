---
map: memory-and-vault
title: "Centralized memory (memory v2) and the ID vault"
charted: 2026-11-05
charted-by: "Kevin + Opus"
effort: "`.scratch/memory-and-vault/`"
tickets: 15
open: 15
status: open
tags: [map]
---

# Centralized memory (memory v2) and the ID vault

**Kevin, 2026-11-05:** *"alfred's memory should stay on a centralized place, like the engine no? i
thought we did memories that way"*, then *"yeah lets do it. centralized memory. and the id vault"*.

Two efforts, one map, because they share one boundary: what Alfred may keep about a person, and
where. Memory moves to the engine so the phone, the web and the Claude worker all read one store.
The ID vault is the opposite: the few identifiers that must never reach a model live on the
engine encrypted, and open only on the phone behind a fingerprint.

## Kevin's rulings (2026-11-05, verbatim where quoted)

1. **Centralized.** *"alfred's memory should stay on a centralized place, like the engine no? i
   thought we did memories that way"*; *"yeah lets do it. centralized memory. and the id vault"*.
2. **Privacy.** A memory is **private to the member it is about**. A shared household fact
   (address, car) can be marked shared. ADR 0052's row ownership and
   `household.tenancy.visible()` enforce it.
3. **Claude worker.** The Claude worker on the Alienware may **read AND write** memories:
   *"claude can write too."* The work laptop never touches memory, by ADR 0067's scope rules.
4. **Saving.** Automatic from conversations, **visible** on a Memory screen, and **forgettable**.
   "Forget that" or a swipe deletes it for real: a hard delete, not hidden.
5. **ID vault.** Phone only, behind fingerprint. Never on the web, never readable by Claude, never
   spoken aloud, never sent to the AI model, never logged. Passport, NRIC, SSN, A-number and bank
   account numbers go there. Low-sensitivity identifiers (plate, VIN, insurance policy number)
   are ordinary data, not vault.
   **Later (Kevin, 2026-11-05):** *"can i enter it on the pc man, but later. not important
   now"*. Ticket 15, KIV: the web desk may ADD or UPDATE an item, write-only. Revealing stays
   phone-only. The engine API is write-only already (create and update never answer with the
   value), so that ticket needs no engine change.
6. **Vehicle plate.** A `plate` field on the engine's vehicle record, additive. Kevin's value is
   set through the API after deploy, never in code or tests.

## What already exists (checked 2026-11-05, dev `0b26ff92`)

- **The phone's memory already replicates to the engine**, just not as the source of truth.
  `companion_memories`, the flat `memories` table (what `remember` writes) and `memory_audit` are
  legacy tables served at `/api/memory/<table>` (`server/api/memory.py`), pushed and pulled by
  `DjangoMemoryBackend.kt` / `MemoryOutbox.kt` / `MemorySync.kt`. They are household-wide: no
  member column, so privacy cannot be enforced on them. They stay as they are; v2 is a new table.
- **Phone recall** (`ai/AriaBrain.kt:452-537`): scan 200 rows, lexical relevance (matched terms /
  terms, stopwords and words under 3 letters dropped, a hard gate at relevance > 0), score
  `0.99^hours_since_access + importance/10 + relevance`, top 6, rehearsal bumps
  `lastAccessedAt` locally only (never reaches the engine). Embeddings never populated.
- **Phone consolidation** (`ai/MemoryConsolidator.kt`): every 5 min, one Gemini call per finished
  session over `episodic_turns`, 0-5 items, **no dedup and no supersede**. Turns that called a
  read-through tool are never captured (`LiveToolbox.EPISODIC_EXCLUDED_TOOLS` = `search_mail,
  read_mail, ask_mail, get_sitrep, track_package, flight_status, browser`).
- **Phone tools:** `remember {text}` and `recall_memory {query}`. No forget tool; delete is
  screen-only (`ui/companions/MemoryScreen.kt`, one tap, soft delete).
- **Conversation records on the engine:** `device_logs` rows with reason `conversation`, from the
  phone and the web, carrying `LIVE_AUDIT` lines and the uploading member (`DeviceLog.user`).
  Mail is redacted on the device before upload.
- **The engine excluded memory from its tools** (`server/engine_mcp/tools.py:21-22`, engine-mcp
  ticket 06 sub-question 2, Kevin 2026-10-02). **Ruling 1 and 3 reverse that for v2**, by Kevin's
  ruling today; ADR 0075 records the amendment. The three legacy tables stay out of the generic
  record tools.
- **pgvector:** the test cluster (Postgres 17.6, Windows) has no `vector` extension
  (`pg_available_extensions` lists only `pg_trgm`). Supabase Postgres offers it. See decision A.
- **The vault key:** `LEGION_VAULT_KEY`, Fernet, `server/ingest/vault.py` `seal()`/`unseal()`,
  already sealing Plaid access tokens (`PlaidItem.access_token_ciphertext`).
- **The Claude worker** holds an ordinary household device token named `claude-session-<Name>`
  (`tools/claude_session/install.ps1`) and reports its scope (`full` / `listed`) in each
  heartbeat. Its headless helper has no engine MCP configured today.

## Decisions (made in this map; Kevin may overturn any)

A. **Retrieval: lexical v1, embeddings next.** v1 ports the phone's score and adds decay classes:
   `episodic` decays 0.99/hour (the phone's rate, half-life ~69 h), `durable` 0.999/hour
   (half-life ~29 days, an estimate from the research notes), so "Jeep needs an LS swap
   eventually" outlives "had tacos Tuesday". Why not pgvector now: the suite cannot exercise it
   (no extension on the test cluster), each recall would add a Gemini embedding round trip, and
   one member's few hundred rows score in Python in well under a millisecond. Ticket 07 adds
   embeddings with a `real[]` fallback when `vector` is absent.
B. **Shared is an explicit act.** Consolidation always writes private memories. A memory becomes
   shared only when a member says so (`remember` with `shared`, or the Memory screen). Default
   private is the safe side of ruling 2.
C. **Supersede, then forget whole.** A newer fact that contradicts an older one points at it
   (`supersedes`); the old row gets `superseded_at` and drops out of recall and the default list.
   Forgetting a memory hard-deletes it AND every older version it superseded, so "forget that"
   leaves nothing of the fact behind.
D. **Consolidation reads the person's own words.** Only `user final=true` lines and the
   assistant's words from turns that called no tool. Never a tool result, never a redacted line,
   never a turn that called a read-through tool. A stricter rule than the phone's, and it needs no
   list kept in step with new tools (the phone's list is still applied as a second fence).
E. **Vault identifiers never become memories.** One deterministic filter
   (`member_memory/sensitive.py`) refuses SSN, NRIC, A-number, passport and bank-account shapes
   in `remember`, in REST writes, in the import and in consolidation output. Plates and VINs pass.
F. **The phone becomes a cache.** After the one-time upload the phone reads the engine and keeps a
   whole-list cache. Hard delete leaves no tombstone, so the cache refreshes from the full list,
   never from a `?since=` feed.
G. **Work laptop fence.** On heartbeat the engine binds the worker's token to its machine.
   Memory routes and tools refuse a token bound to a `listed` machine, and any
   `claude-session-*` token not bound to a `full` machine. The vault refuses every
   `claude-session-*` token and every machine-bound token, on every route. **Honest limit:** the scope is what the
   worker reports and the token name is a convention; a member's token on any device is a
   member. ADR 0067's follow-up (worker endpoints on machine tokens) closes it.

## Tickets

| # | Ticket | Side | Type | Blocked by |
|---|---|---|---|---|
| 01 | [Member memory: table and REST](issues/01-member-memory-table-and-rest.md) | engine | build | rulings |
| 02 | [Memory tools for members and the worker](issues/02-memory-tools.md) | engine | build | 01 |
| 03 | [Consolidation job](issues/03-consolidation-job.md) | engine | build | 01 |
| 04 | [One-time import of the phone's memories](issues/04-one-time-import.md) | engine | build | 01 |
| 05 | [ID vault on the engine](issues/05-id-vault-engine.md) | engine | build | rulings |
| 06 | [Vehicle plate](issues/06-vehicle-plate.md) | engine | build | rulings |
| 07 | [Hybrid recall: embeddings](issues/07-hybrid-recall-embeddings.md) | engine | build | 01 |
| 08 | [Worker: memory tools on the Alienware only](issues/08-worker-memory-tools.md) | tools | build | 02 |
| 09 | [Phone: memory tools move to the engine](issues/09-phone-memory-on-engine.md) | phone | build | 02 |
| 10 | [Phone: upload once, retire the local consolidator](issues/10-phone-upload-and-retire.md) | phone | build | 03, 04, 09 |
| 11 | [Phone: Memory screen v2](issues/11-phone-memory-screen.md) | phone | build | 09 |
| 12 | [Phone: ID vault screen behind fingerprint](issues/12-phone-id-vault.md) | phone | build | 05 |
| 13 | [Phone: plate on the vehicle](issues/13-phone-vehicle-plate.md) | phone | build | 06 |
| 14 | [Acceptance run](issues/14-acceptance-run.md) | both | test (Kevin, on device) | 08-13 |
| 15 | [Vault entry from the web desk (write-only)](issues/15-web-vault-entry.md) | web | build, KIV | 05 |

Order: 01-06 are the engine half (branch `feat/memory-vault-engine`). 07 and 08 follow. The phone
tickets (09-13) are one agent's work, in that order. Kevin runs 14.

## Rules this map keeps

- CLAUDE.md §7: mail and other read-through content never becomes a memory, not even a summary.
- Memory stays anchored to facts the person stated. No opinions about the assistant, no
  guesses, no profile of moods (research: memory profiles raise sycophancy).
- Pull-based: recall is a tool. No full profile pre-injected (the phone's 3-line continuity note
  stays the only exception).
- Every voice capability has a hands path: the Memory screen and the vault screen.
- Additive migrations only; the legacy memory tables are untouched.
- Deploys stay Kevin's.
