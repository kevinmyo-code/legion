---
map: claude-session
ticket: "06"
title: "Build: interactive Claude Code sessions reporter hooks"
type: build
status: kiv
status-detail: "Parked with the map (Kevin 2026-10-29: dont build it yet). In v1 per ruling 11."
blockers: ["03"]
blocked-by: ["[[03-engine-app]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Build: sessions reporter

Kevin, 2026-10-28: *"ask it progress, whats it doing what projects, not just on this laptop but on
my work laptop as well."* Read-only.

- `tools/claude_session/session_hook.py` (stdlib): run by `SessionStart`, `UserPromptSubmit`,
  `Stop`, `SessionEnd` hooks in Kevin's interactive `~/.claude/settings.json` on each machine.
  POSTs `{machine, session_id, project: cwd basename, state, last_prompt_summary, at}` to
  `POST /api/claude/sessions/`. The summary is the first 120 characters of the prompt on the
  Alienware; the work laptop sends none (ruling 5). Fire and forget, 2 s timeout, never blocks or
  fails the session.
- `install.ps1` from ticket 04 offers to add the hooks, merging into existing settings without
  removing Kevin's own hooks.
- The worker's own main session is excluded (it reports through the worker).
- `check_claude` and the screen read these rows (ticket 05). A session with no event for 30 min
  shows as idle; `SessionEnd` removes it from the list.
