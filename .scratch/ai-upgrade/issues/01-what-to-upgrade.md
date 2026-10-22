---
map: ai-upgrade
ticket: "01"
title: "Decision: provider, decision models, and which agent-platform upgrades to build"
type: decision
status: resolved
status-detail: "Kevin 2026-10-22: stay on Gemini Live, reasoning unchanged, build daily brief (02) and MCP update + Claude/Gemini app (03)"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# Decision: what to upgrade

Evidence: `research/2026-10-22-landscape.md`.

## Proposed (Opus, 2026-10-22). Nothing here is ruled yet.

1. **Keep voice on Gemini 3.8 Live.**
   - OpenAI's turn-based Realtime 2.1 costs about 4x Gemini per output minute.
   - `gpt-live-1` would mean re-architecting the tool loop, because tools move out of the voice model into a delegated backend.
   - Gemini has a free tier and no shutdown date.
   - Optional: trial `gemini-3.8-live-extended-thinking` at the same price. It needs `interaction_status` handling.
2. **No decision model for now.**
   - Neither the Decisions API nor Jev is deterministic.
   - LEGION has almost no boolean LLM calls; the ones that must be right are already rules.
   - The only candidate is `CategoryAgent`, which is pick-one-of-N, exactly where the Decisions "choice" format tests badly.
   - Revisit if a real routing or triage call appears.
   - Never let one stand in for a §4 gate check (rule 6).
3. **An `LlmClient` seam in front of `SubAgent`.** This is cheap insurance, and it is what makes `gpt-6-sol` for hard reasoning (schema drafts, goal plans) a per-call choice rather than a migration. Build it only if item 3b is wanted:
   - 3a. The seam, Gemini only.
   - 3b. An OpenAI implementation for reasoning calls, on a second BYO key. **Clone-and-run cost:** a stranger would need two keys for full function, unless it stays optional.
4. **Bring the engine MCP server to the 2026-07-28 spec with OAuth, then connect it to the Gemini app, ChatGPT or Claude.** This gives desktop and hands-free access to all of LEGION with no new UI. Household tenancy must hold under OAuth (ADR 0045).
5. **A scheduled server-side agent over the MCP server**, read-only. Examples: a weekly digest across Canvas, ledger and fleet, or "what changed in my courses this week".
   - Choose Gemini Managed Agents (same key, preview, price unknown) or Claude Managed Agents ($0.08 per running hour plus tokens).
   - It must never write ledger rows (§4).
6. **Wait on AppFunctions** (Android 16+, private preview) and **MCP Events**. Grok Bot and Dots have no API, and memory products cut against §7's anchored memory, so all three are out.

## Rulings (Kevin, 2026-10-22)

1. **Voice stays on Gemini 3.8 Live.** No OpenAI voice.
2. **Reasoning calls are left as they are.** No `LlmClient` seam and no GPT-6 Sol.
3. **No decision model** (proposal item 2 stands).
4. **Build the MCP update and connect it to Claude and the Gemini app.** That is ticket 03.
5. **Build a daily brief rather than a general agent platform.** That is ticket 02.
   - Kevin: *"i want a personal daily digest delivered to my phone. canvas, money, car, ai news + topics im interested in, a morning brief basically. it should be a button, pressing it opens a modal i can dismiss. so i can bring it back up anytime. updates daily."*
   - It runs as a server job on the existing Gemini key and Cloud Scheduler. No hosted agent runtime (Claude or Gemini Managed Agents) is used: the job's shape is one grounded call per day, and a new vendor would add cost and send bank data further out for no gain.

## Questions asked (answered above)

- Voice: stay on Gemini (proposed) or switch anyway?
- Item 3: seam only, seam plus OpenAI reasoning, or neither?
- Items 4 and 5: build, and which consumer app or agent runtime?
