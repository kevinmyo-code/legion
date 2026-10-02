---
map: engine-mcp
ticket: "08"
title: "Public exposure and clone-and-run"
type: decision
status: open
status-detail: ""
blockers: ["01", "05"]
blocked-by: ["[[01-where-the-mcp-server-lives]]", "[[05-auth-per-caller]]"]
open-blockers: 2
ready: false
tags: [ticket]
---

# Public exposure and clone-and-run

## Question

Is `/mcp` on the public internet, on by default, and does a stranger's household get the same thing
from `docker compose up`?

## Facts (traced 2026-10-02)

- Kevin's engine is already public HTTPS on Cloud Run (`*.run.app`), so a `/mcp` route is
  internet-reachable the moment it is deployed.
- A stranger's compose stack serves plain HTTP on localhost. Claude Code can reach that; claude.ai,
  ChatGPT and the Gemini app cannot, since they connect from the vendor's cloud.
- Throttling exists only for `login` and `signup` (`ScopedRateThrottle`).
- min-instances 0 means a 5.4 s cold start; 02 should say whether target clients time out on it.
- `LEGION_OPEN_SIGNUP` is the precedent for an operator switch that defaults to closed.

## Sub-questions and recommendations

1. **Off by default?** Recommendation: yes, `LEGION_MCP=on` env switch, closed unless the operator
   chose it, like `LEGION_OPEN_SIGNUP`.
2. **Same service or separate?** Recommendation: same Cloud Run service, same image (01 option A).
3. **Rate limit.** Recommendation: its own throttle scope per token, so a looping model cannot spend
   the free tier or lock out the phone.
4. **Third-party for a stranger.** Requires their own public HTTPS (a domain, or a tunnel they run).
   Recommendation: document it in `deploy/`, do not provide it. Nothing Kevin-hosted.
5. **Audit.** Should each MCP call be logged per token (who asked what)? Recommendation: yes, the
   tool name and token, never the result body.

## Resolution

Kevin rules on 1-5.
