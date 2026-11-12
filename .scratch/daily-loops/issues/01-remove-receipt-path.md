---
map: daily-loops
ticket: "01"
title: "Remove the receipt-photo path"
type: build
status: open
status-detail: ""
blocked-by: []
tags: [ticket]
---

# Remove the receipt-photo path (ADR 0089)

Kevin picked remove over hide. Remove the phone's receipt capture (`pantry/PantryReceiptAgent.kt`,
the receipt branch of `ui/CameraCapture.kt` and `ui/PantryScreen.kt`, the Matrix pantry entry), the
receipt voice tool and its copy in `tools/voice_guide_copy.py`, and the engine's receipt upload
endpoints. Keep every read of stored receipt rows working, with their labels.

## Verification

- `grep -ri receipt` over `app/src/main` and `server` leaves only reads of stored rows.
- `python tools/voice_guide.py --check` and `python tools/screen_map.py --check` pass.
- Compile, unit suite, server suite green. On the phone: Pantry opens, no receipt button, old
  receipts still listed.
