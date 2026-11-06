---
map: phone-verify
ticket: "02"
title: "The rails: vault, sends, system settings, artifacts"
type: build
status: resolved
status-detail: "proven on the phone 2026-11-05 20:31-20:34 CDT, ticket 06"
blockers: []
blocked-by: []
tags: [ticket]
---
# The rails: vault, sends, system settings, artifacts

`tools/phone/rails.py`, decision 5. Enforced in the CLI, not just written in the skill.

- **ID vault (ADR 0076), absolute.** With `VaultActivity` in front, the only tap is Back; `type` and
  `say` are refused; number-like text in a dump is masked. `vault-show`/`vault-copy` are refused
  anywhere. No command touches the engine's reveal endpoint (the CLI makes no HTTP calls).
- **System settings, absolute.** Taps with `com.android.settings` in front, and LEGION's "Phone
  settings", are refused.
- **Sends, purchases, deletes, confirmable.** Whole-word match on the control's own and its
  children's text, description and tag (tags split on `-`/`_`). `--confirmed-by-kevin` lifts it;
  the skill says only with Kevin's yes to that exact action in chat. Logged either way.
- **Another app in front, confirmable.** The CLI drives LEGION only.
- **`--xy` is checked** against the node under the point, so coordinates are not a way past.
- **No command for uninstall, `pm clear`, wipes, installs or a raw shell** (tested by listing the
  parser's commands). `guard_destructive.py` already blocks them in Bash.
- **Artifacts** (screenshots, dumps, logs, the action log) only under `.phone-artifacts/`
  (gitignored, line asserted by a test, `git check-ignore` too) or outside the repo.

## Verification

- [x] `tools/tests/test_phone_cli.py`: each refusal sends no `input` command to the fake phone.
- [ ] On the phone: ticket 06 (the vault list opens; its screenshot is blank).
