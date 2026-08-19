---
status: locked
decided: 2026-07-30
decided-by: Kevin
source: "CLAUDE.md §2"
tags: [adr]
---

# 10. Drive appDataFolder is the only store

## Standing

LOCKED. Not reopenable without Kevin.

## Context

Two phones, one shared Google account, and a hard requirement that nobody runs a server. See [[0002-no-hosted-backend]].

## Decision

State syncs through the driver's own Drive `appDataFolder` on the `drive.appdata` scope. There is no other store and no other sync path.

## Consequences

- Do not confuse this with the ledger's statement folder, which is a SAF tree grant over a local folder. Two different mechanisms, both loosely called 'Drive'. See [[0012-saf-folder-access]].
- The app never sees the user's wider Drive. `drive.appdata` is a private per-app area.
- `sync/` has never actually executed in LEGION. Every claim about it is traced from source, none is tested.
