---
map: clerk
ticket: "06"
title: "Build: Claude Code sessions reporter (layer 1) and ask_claude"
type: build
status: kiv
status-detail: "Charted 2026-10-29 from Kevin's 2026-10-28 ask ('control claude code sessions from there too, like ask it progress, what projects'). Layer 1 is read-only. Designed, not yet specified; the clerk heartbeat already answers whether the laptop is on."
blockers: ["02"]
blocked-by: ["[[02-engine-clerk-app]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Build: sessions reporter

Hooks in each machine's `~/.claude/settings.json` (`SessionStart`, `UserPromptSubmit`, `Stop`,
`SessionEnd`) run a stdlib Python script that POSTs `{machine, session_id, project (cwd basename),
state, last_prompt_summary, at}` to `POST /api/clerk/sessions/`. The work laptop sends project
name and state only, never prompt text (`summary_only` in its config). Alfred's `ask_claude` reads
the rows: "two sessions on the home laptop, one on LEGION active ten minutes ago, one idle; the work
laptop has one session, last seen an hour ago". To be specified after ticket 02 lands so it reuses
the `clerk` app and the device token.
