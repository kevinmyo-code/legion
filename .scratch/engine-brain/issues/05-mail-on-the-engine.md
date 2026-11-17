---
map: engine-brain
ticket: "05"
title: "Mail on the engine: read, draft, send with the safeguards"
type: build
status: open
size: M
blockers: ["04"]
blocked-by: ["[[04-limb-contract-relay-and-switch]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Mail on the engine: read, draft, send with the safeguards

Stage 4 (ADR 0099). Needs Kevin once, at a keyboard, for Google consent.

## Build
- A `mail` specialist with `search_mail`, `read_thread`, `draft_mail` and `send_draft`, plus `open_drafts` relayed.
- A Gmail web OAuth client with `gmail.readonly` and `gmail.compose`. The refresh token goes in Secret Manager; a `GoogleGrant` row tracks scopes, status and last success.
- Redaction:
  - mail content never enters turn state, checkpoints, traces, eval candidates or memory;
  - a trace records only that a mail tool ran.
- Mail reads taint the turn.
- The send gate from ADR 0086, ported.
- Refused for scheduled, `/mcp` and recruiter callers.
- Replaces the old assertion that no engine tool reads mail with tests of these safeguards.
- The phone's mail tools leave its manifest.

## Verification (gates)
- [ ] The `mail` suite runs on a fake Gmail with invented threads:
  - no content in any stored row;
  - no action in a read turn;
  - send only after a read-back and a later "send";
  - refusals for each forbidden caller.
- [ ] A lapsed grant (Testing status, 7 days) is said in words, and the web page re-grants.
- [ ] Kevin grants consent (owed by Kevin).
