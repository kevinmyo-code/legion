---
map: hardening
ticket: "04"
title: "Encryption at rest: decide it on the record"
type: grilling
status: resolved
status-detail: "Resolved 2026-08-24 (Kevin). Platform encryption accepted; ADR owed recording the threat model."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Encryption at rest: decide it on the record

## Question

The DB is unencrypted; Android FDE/file-based encryption covers the powered-off case; Keystore
already guards the API keys. Decide deliberately: accept platform encryption as sufficient for
the two-adult threat model (cheapest, likely right), or adopt SQLCipher (real cost: every
Room/wal tooling path, the pulled-DB debugging workflow, performance). The answer is probably
"accept and record why" - but it becomes an ADR either way so it is a decision, not an
oversight.

## Answer

Kevin, 2026-08-24: **accept platform encryption.** Android FBE covers the powered-off case,
Keystore guards keys, threat model is two adults; SQLCipher's cost (tooling, the pulled-DB
verification workflow every wave relied on, performance) buys nothing against that model.
Owed: a short ADR stating this acceptance and its premises, so it is a decision on the record.
