---
map: worker-workflows
ticket: "09"
title: "Workflow: log-audit"
type: build
status: open
status-detail: "Deferred 2026-11-10: a private-folder workflow that reads prod device logs and conversation records (personal data); waits for 08 and Kevin. The format, engine and worker it needs are built."
size: M
blockers: ["05", "08"]
blocked-by: ["[[05-worker]]", "[[08-device-logs-read]]"]
open-blockers: 2
ready: false
tags: [ticket]
---
# Workflow: log-audit

"Have Claude audit my logs" / "audit my logs from this morning" / "log audit for yesterday's drive".

## workflow.json (shape; the real one lives in the private folder)

`name: log-audit`, `says: "Audits the phone's recent logs and lists numbered findings."`, aliases
"audit my logs", "log audit", "check my logs"; param `window` (default "the last 24 hours"); no
asks; outputs `summary`, `report`; `every: null` (on demand first; a nightly run is one line later
if Kevin wants it).

## SKILL.md steps

1. Read `AGENT-RULES.md`. Fresh worktree off `origin/dev` in the work folder (legion-chores'
   rules: never touch the parked checkout or another worktree).
2. Turn the window into `--since/--until` (UTC, said back in the report in local words).
3. `phone_audit_engine.py --engine --since .. --until ..` (ticket 08) plus conversation records
   (`GET /api/device-logs/?reason=conversation`, as fix-alfred reads them). Head unit and web
   records the same way when the window has them.
4. Write the report: numbered findings, each with what Kevin would have seen or heard, evidence
   (record id and time, never quoted third-party text), likely file, severity, and "fix-finding N"
   as the next step. Compare against the previous report in the same folder: new, still open,
   gone.
5. Finish: `--status done`, summary "Four findings, worst first: ...", `--found` the report.

## Rules

- Read only, everywhere. No gcloud (the hook holds it). No mail content: a conversation that used
  a mail tool is reported by tool name and outcome only (CLAUDE.md §7).
- Logs are already redacted on the phone; the report still carries no tokens, numbers from the
  ID vault, or addresses.

## Verification

- [ ] Dry run on the Alienware against a synthetic window on the fake engine, then one real
      on-demand run (ticket 16).
