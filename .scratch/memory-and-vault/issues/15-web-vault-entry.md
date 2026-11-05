---
map: memory-and-vault
ticket: "15"
title: "Vault entry from the web desk (write-only)"
type: build
status: kiv
status-detail: "parked by Kevin 2026-11-05: later, not important now"
blockers: ["05"]
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Vault entry from the web desk (write-only)

Kevin, 2026-11-05: *"can i enter it on the pc man, but later. not important now"*.

## What

- The web desk can ADD or UPDATE a vault item: label, kind, value.
- **Write-only.** The web never reads a value back. After saving it shows only the label and
  "saved".
- Revealing stays phone-only behind the fingerprint, exactly as ruled (ruling 5). Delete stays on
  the phone.
- **No engine change needed** (ticket 05): a browser session may already list labels, create and
  update; create and update never return the value; reveal and delete refuse a browser session.

## Verification

- Frontend test: the form clears the value after save; no response the page reads carries one.
