---
map: engine-mcp
ticket: "04"
title: "How the phone's Live session consumes engine tools"
type: decision
status: open
status-detail: ""
blockers: ["01", "03"]
blocked-by: ["[[01-where-the-mcp-server-lives]]", "[[03-one-source-for-tool-definitions]]"]
open-blockers: 0
ready: true
tags: [ticket]
---

# How the phone's Live session consumes engine tools

## Question

Kevin: *"we could let the voice model ... query it directly in the app."* Gemini Live speaks a raw
WebSocket from the phone and (per 02, to confirm) has no MCP client of its own. So how does an engine
tool reach the Live session?

## What constrains it (traced 2026-10-02)

- **Reads must survive the engine being down** (ADR 0044 rule 4). Every current Live tool reads Room.
  A tool that only exists on the server fails offline, and must say so in words.
- **Token budget.** `LiveToolbox.declarations()` already hides ~79 tools behind five `ask_*`
  dispatchers because Live re-bills the whole declaration block every turn.
- **Many tools can never be on the server**: OBD, Spotify, wake word, Gmail, the camera.
- **Mail read-through.** `EPISODIC_EXCLUDED_TOOLS` is applied on the phone. An engine tool result is
  first-party data and episodic-safe, but the bridge must not let a mail-touched turn's content leave
  the phone as an MCP argument.
- **Every voice capability has a hands path, calling the same controller** (ADR 0035).

## Options

| | Shape | For | Against |
|---|---|---|---|
| A | **Runtime bridge**: phone is an MCP client, fetches `tools/list` at session start, translates to Gemini declarations, forwards calls to `/mcp` | New engine tools reach voice with no app release | Offline the tools vanish or fail; declaration cost grows with every server tool; translation edge cases (JSON Schema 2020-12 vs Gemini's subset) |
| B | **Build-time generation**: the 03 registry emits Kotlin declarations; the phone calls REST as now | Declarations are reviewed and compiled; works with existing offline/Room paths | A release per new tool; it is codegen, not "query it directly" |
| C | **One dispatcher**, `ask_engine(question)`, bridged at runtime to the MCP surface (a sub-agent picks the tool), like `ask_fleet` | Constant declaration cost; new tools appear with no release; offline is one sentence in one place | An extra model hop of latency; the sub-agent must carry the outcome-verb rule |
| D | **Status quo**: the phone does not consume MCP; MCP is for Claude Code and third parties | Nothing to build | Does not do what Kevin asked |

## Recommendation

**C**, leaving existing Room-backed tools exactly where they are. It matches the pattern the
toolbox already uses for token cost, keeps offline failure in one place ("the engine is unreachable,
nothing was read"), and only server-native capability (cross-aspect queries, anything Room does not
hold) goes through it. Revisit A only if 02 finds Live accepts MCP servers natively.

## Resolution

Kevin picks; 11 builds it.
