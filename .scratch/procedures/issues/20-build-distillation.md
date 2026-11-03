---
map: procedures
ticket: "20"
title: "Build: distillation proposals and approval in the inspector"
type: build
status: open
status-detail: ""
blockers: ["08", "13", "14"]
blocked-by: ["[[08-distillation-policy]]", "[[13-build-run-traces]]", "[[14-build-branch-report-and-inspector]]"]
open-blockers: 2
ready: false
tags: [ticket]
---
# Build: distillation proposals and approval in the inspector

## What

- `manage.py propose_procedures`: mines conversation records and runs per ticket "Distillation"
  rules, writes inactive `procedure_versions` rows with `source = distilled`, links the traces.
- Inspector: a Proposals tab (members, Kevin's household only), dry run on today's data, approve /
  edit / dismiss.
- Weekly cron line in `deploy/crontab`.

## Verify

- Fixture: 3 matching conversations on 2 days propose one; 2 do not; a mail-touching one never
  counts; recruiter visit records never read.
- Nothing becomes active without an approval row.
