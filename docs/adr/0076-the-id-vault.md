---
status: accepted
decided: 2026-11-05
decided-by: Kevin
supersedes: []
source: "[[decisions#2026-11-05 - Centralized memory and the ID vault (Kevin)]]"
tags: [adr]
---

# 76. The ID vault: phone only, behind the fingerprint

## Standing

ACCEPTED 2026-11-05. Engine half built on `feat/memory-vault-engine` (`.scratch/memory-and-vault/`
ticket 05, not deployed); the phone screen is ticket 12; web entry is ticket 15 (KIV).

## Context

Some identifiers must be at hand and must never reach a model: a passport number at a counter, a
bank account number on a form. Memory ([[0075-memory-lives-on-the-engine]]) is read back to a
model on every recall, so they cannot live there. Kevin, 2026-11-05: *"centralized memory. and
the id vault"*; later the same day, *"can i enter it on the pc man, but later. not important
now"*.

## Decision

- **What goes in:** passport, NRIC, SSN, A-number and bank account numbers. Plates, VINs and
  insurance policy numbers are ordinary data and stay out (the plate is a vehicle field).
- **Phone only, behind fingerprint. Never on the web, never readable by Claude, never spoken
  aloud, never sent to the AI model, never logged.**
- **Stored sealed** in `public.vault_items` with Fernet under `LEGION_VAULT_KEY`, the helper that
  seals Plaid tokens (`server/ingest/vault.py`). Each item is one member's; another member gets a
  404.
- **Write-only everywhere but the reveal.** List, create and update never answer with a value. A
  browser may list labels, create and update (ticket 15); it may never reveal or delete.
- **Reveal** (`POST /api/vault/items/<id>/reveal`) needs the member's device token and the header
  `X-Legion-Unlocked: biometric`, which the phone sets only after `BiometricPrompt` succeeds. Rate
  limited (10 an hour per member), audited in `public.vault_reveals` (label, time, device name,
  never the value), `Cache-Control: no-store`.
- **Refused on every route:** any token the engine ties to a Claude worker (a `claude-session-*`
  name or a machine binding), and recruiters.
- **No engine tool, no admin entry.** The phone's voice tool `show_vault_item(label)` opens the
  screen and returns only "opened on the phone".

## Consequences

- **The server cannot verify a fingerprint.** The header is the phone's word. A member's device
  token that sends it gets the value, so the guarantee rests on the phone and on the token never
  leaving it. This is stated rather than implied.
- "Never readable by Claude" holds for the worker's tokens as installed. A member token copied by
  hand to a laptop is not recognised (same limit as ADR 0075's work-laptop fence).
- A value the person speaks aloud lands in the conversation record like any speech; the phone
  screen takes values typed, never dictated.
- The nightly backup holds ciphertext; losing `LEGION_VAULT_KEY` loses the vault.
