---
map: procedures
ticket: "06"
title: "What a run trace stores, and how long"
type: grilling
status: resolved
status-detail: "resolved 2026-11-03, Kevin: approve all 9 as recommended"
blockers: ["01"]
blocked-by: ["[[01-procedure-format-and-storage]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# What a run trace stores, and how long

## Question

Two postures exist on dev:

- `engine_mcp.models.McpCall` stores **no arguments and no results** by design (its docstring:
  an audit table must not become a second copy of the household's data).
- `devicelogs/web_conversation.py` stores **tool args and trimmed results** with latency, in the
  conversation record, 60 days (`devicelogs/models.py:67`), because Kevin wants to see "what tool
  calls etc went wrong".

Which does a procedure run follow?

## Recommendation

- **The conversation-record posture**: each step row stores kind, tool name, args, a trimmed
  result text (same trim as `web_conversation.record_tool`), outcome (`ok` / `refused` / `failed` /
  `skipped`), branch taken, `chosen_by` (`code` / `model` / `user`), started_at, ms.
- **Members: 60 days**, pruned with the same rule as `devicelogs.views.prune`, but by a cron line
  in `deploy/crontab` (prune on upload does not fit a table nobody uploads to).
- **Recruiter visit runs: FK to the visit household with CASCADE**, so
  `demo/visit_cleanup.py` (`deletion_order`, 60) deletes them with the visit; a 30-day cap
  (`VISIT_RETENTION_DAYS`) covers a visit that never ends.
- **Never store a step that read third-party content** (CLAUDE.md §7, read-through only). No
  registry tool reads mail today (`tests/test_engine_mcp.py` enforces it); the runner refuses a
  tool in a deny set mirroring `LiveToolbox.EPISODIC_EXCLUDED_TOOLS` so a future one cannot.
- **Link to the conversation**: the run carries `conversation_id` (the `batch_id` of the
  `DeviceLog` row) when the caller sent one, and the conversation record gets one
  `procedure_run <id> <name> v<version> <outcome>` line, so `manage.py device_logs --render` and
  `tools/voice_audit.py` show it in the timeline.

## Answer

**Accepted as recommended.** Kevin, 2026-11-03: *"approve all 9 as recommended"*.

- Run traces follow the conversation-record posture, not `McpCall`'s: per step kind, tool, args, trimmed result (same trim as `web_conversation.record_tool`), outcome, branch, `chosen_by` (`code` / `model` / `user`), started_at, ms.
- Members: 60 days, pruned by a `deploy/crontab` line. Recruiter visit runs: FK to the visit household with CASCADE, deleted with the visit; 30-day cap.
- Never store a step that read third-party content; the runner refuses tools in a deny set mirroring `EPISODIC_EXCLUDED_TOOLS`.
- A run carries `conversation_id` when sent, and the conversation record gets one `procedure_run ...` line.
- ADR 0070. Build: tickets 11 (the deny set at load), 13.
