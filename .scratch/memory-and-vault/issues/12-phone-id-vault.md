---
map: memory-and-vault
ticket: "12"
title: "Phone: ID vault screen behind fingerprint"
type: build
status: open
status-detail: ""
blockers: ["05"]
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Phone: ID vault screen behind fingerprint

## What

Ruling 5: phone only, behind fingerprint, never spoken, never sent to a model, never logged.

- A Vault screen: list labels (`GET /api/vault/items`), add, edit, delete.
- Reveal: `BiometricPrompt` (BIOMETRIC_STRONG), then `POST /api/vault/items/<id>/reveal` with
  `X-Legion-Unlocked: biometric`. The value shows on screen with `FLAG_SECURE`, a copy button
  that clears the clipboard after 60 s, and is held only in memory.
- Voice tool `show_vault_item {label}`: opens the screen on that item and returns only "opened on
  the phone". It never returns the value, and the value never reaches the model, TTS, logcat,
  LIVE_AUDIT or the conversation record.
- Values are typed, never dictated (a spoken value would be in the conversation record).
- `tools/voice_guide_copy.py` copy.

## Verification

- JVM: the tool result never carries the value; a grep test over the vault code finds no `Log.`
  call with the value.
- On the A25: fingerprint opens, a cancelled prompt does not.
