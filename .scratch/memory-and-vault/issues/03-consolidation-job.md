---
map: memory-and-vault
ticket: "03"
title: "Consolidation job"
type: build
status: built
status-detail: "engine built 2026-11-05 on feat/memory-vault-engine, suite green; owed: engine deploy, then install_schedule.py for consolidate_memories (Kevin)"
blockers: ["01"]
blocked-by: ["[[01-member-memory-table-and-rest]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Consolidation job

## What

`manage.py consolidate_memories`, hourly at :40 in `deploy/crontab` (installed only when Kevin next
runs `deploy/cloudrun/install_schedule.py`).

- Input: `device_logs` rows with reason `conversation`, a household, a member (`user`), received
  at least 15 minutes ago and not yet consolidated (`memory_consolidations`, one row per log).
- Transcript: map decision D. Only the person's own final words, plus the assistant's words from
  turns that called no tool. A turn that called a read-through tool (`EPISODIC_EXCLUDED_TOOLS`,
  copied) is dropped whole, and so is any redacted line.
- One Gemini call (household key, `LEGION_GEMINI_KEY`, the engine's small model), JSON schema:
  0-5 items `{text, kind, importance, durable, supersedes}`. The member's live memories are sent
  with ids, so the model can name one a new fact contradicts.
- Written private to the member, source `consolidation`, `source_log` set. An exact duplicate of a
  live memory is skipped. A `supersedes` id not in the list sent is ignored. The sensitive filter
  drops vault identifiers.
- No key: says so and marks nothing, so the logs wait. A failed call is retried on the next run,
  three attempts, then set aside.

## Verification

- pytest with a fake transport: a mail turn never reaches the prompt, tool results never do, a
  supersede marks the old row, a second run does nothing, no key writes nothing, the crontab line
  parses with `install_schedule.py`'s pattern.
