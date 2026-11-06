---
map: phone-verify
ticket: "04"
title: "Test tags visible to uiautomator"
type: build
status: built
status-detail: "built 2026-11-05 on feat/phone-verify; owed: a build with it on the phone, then `tools/phone dump` shows vault-message / chat-button as resource ids"
blockers: []
blocked-by: []
tags: [ticket]
---
# Test tags visible to uiautomator

`Modifier.exposeTestTags()` (`ui/common/TestTagsAsIds.kt`) sets `testTagsAsResourceId` at a window
root, so a Compose `testTag` shows as `resource-id` in `uiautomator dump`. One semantics flag: no
pixels change, so Roborazzi's goldens are unaffected.

Applied at the three window roots agents need: the shell (`LegionShell`'s outer Box), the vault
(`VaultContent`'s root, the vault being its own activity) and the chat sheet (a bottom sheet is its
own window). New tags, kept to what a rail or a dynamic label needs: `vault-show`, `vault-copy`
(so the rail can refuse the reveal path by tag as well as by word), `chat-button`, `chat-input`,
`chat-send` (the chat button's label carries the companion's name).

## Verification

- [x] `compileDebugKotlin -Pnokey`, `testDebugUnitTest` (totals in the report).
- [ ] On the phone, once a build with this is installed (no install in this ticket): `tools/phone
      dump` on Data and privacy and on the vault shows `vault-message` as a `tag`.
