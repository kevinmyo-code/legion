---
map: memory-and-vault
ticket: "11"
title: "Phone: Memory screen v2"
type: build
status: open
status-detail: ""
blockers: ["09"]
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Phone: Memory screen v2

## What

The hands path for ruling 4 (ADR 0035).

- `ui/companions/MemoryScreen.kt` reads the engine list: text, kind, date, source, shared or
  private.
- Swipe or delete button: hard delete through the same controller as `forget_memory`, with the
  words "Deleted for good".
- A shared/private toggle (only on the member's own memories); add by hand (source `manual`).
- A memory distilled from a conversation shows "from a conversation on <date>".

## Verification

- Compose UI test for delete and the toggle; screenshot on the A25.
