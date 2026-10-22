---
map: ai-upgrade
ticket: "03"
title: "Build: engine MCP server on the 2026-07-28 spec with OAuth, connected to Claude and the Gemini app"
type: build
status: open
blockers: ["01"]
blocked-by: ["[[01-what-to-upgrade]]"]
open-blockers: 0
ready: true
tags: [ticket]
---

# Build: LEGION reachable from Claude and the Gemini app

Kevin, 2026-10-22: picked Claude and the Gemini app. He asked whether the Gemini app means *"the literal
gemini app on my phone? so can i pull up gemini live voice session and ask it questions about my legion
data"*.

## Rulings

1. Bring `server/engine_mcp/` up to the MCP 2026-07-28 revision: stateless (no initialize handshake,
   no session id), the required `Mcp-Method` / `Mcp-Name` headers, and JSON Schema 2020-12 tool
   schemas.
2. Add the auth that each client actually requires, verified against **current** docs, not memory:
   - Claude custom connectors (claude.ai and the desktop app).
   - Gemini app "custom apps".
   - Expected: OAuth 2.1 with Client ID Metadata Documents.
   - **Household tenancy (ADR 0045) holds under OAuth**: a token maps to one LEGION user, and every
     tool is scoped to that user's household at the existing choke point. The leak test covers the
     OAuth path.
3. The tool set is unchanged. Every write tool's result still says in words what was committed
   (§7 outcome verbs), and the client's own per-call approval prompt stays on.
4. **Find out and record** whether the Gemini app's custom apps work inside a **Gemini Live voice**
   session on Android, or only in typed chat, and the eligibility rules (region, age, Keep Activity).
   This answers Kevin's question in words, with a source.

## Verification

Server suite green, including the tenancy leak test over OAuth. Then Kevin adds the connector in
Claude and in the Gemini app and asks one question in each. That last step is his, and it is owed.
