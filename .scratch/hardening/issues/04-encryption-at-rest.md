---
map: hardening
ticket: "04"
title: "Encryption at rest: decide it on the record"
type: grilling
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
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
