---
map: alfred-builds
ticket: "05"
title: "Undo the last ship"
type: build
status: built
status-detail: "built 2026-11-05 on feat/alfred-builds-worker; owed: worker restart, engine deploy, the run (ticket 09)"
blockers: ["03"]
blocked-by: ["[[03-ship-step]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Undo the last ship

## What
- "Alfred, undo the last update" creates a worker task with kind `undo`. It asks "Undo <last ship>?", and on yes:
  - **engine:** route Cloud Run traffic back to the previous revision (`gcloud run services update-traffic --to-revisions <prev>=100`), plus a revert commit on dev.
  - **phone:** build the previous ship's commit with a NEW versionCode and publish it ("undo of <x>"). Android never downgrades.
- Ship history in `work/ships.jsonl`: task, branch, commits, revision, release.
## Verification
- tools tests with fakes.
