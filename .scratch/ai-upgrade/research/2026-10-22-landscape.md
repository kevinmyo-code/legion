# AI landscape, 2026-10-22: OpenAI switch, decision models, agent platforms

Web research (subagent, 2026-10-22) plus a code survey of every Gemini call in the app. Every claim
had a source URL when gathered; the key ones are kept here. **openai.com posts returned 403**, so
OpenAI facts come from developers.openai.com docs, Microsoft Learn, the OpenAI forum and press.

## What LEGION calls today (code survey, traced)

- **Voice:** `ai/GeminiLiveSession.kt`, `gemini-3.8-live` over WebSocket, about 173 tool branches in
  `LiveToolbox`. Gemini-specific pieces: built-in `googleSearch`, server VAD, context compression,
  resumption handles, input/output transcription, prebuilt voices, `behavior:"BLOCKING"` stamping.
- **One-shot:** everything goes through `ai/SubAgent.kt` (`generateContent`, default
  `gemini-3.5-flash-lite`), with 28 construction sites. There is no provider interface.
- **Server:** `server/ingest/statements.py` (PDF to CSV) and `server/assistant/live.py`, which mints
  the PWA's ephemeral Live token.
- **About 15 tests pin Gemini wire formats** (SubAgent*, LiveSetup*, MarcusPayloadTest, server
  test_assistant / test_drive_statements).
- **Pure boolean or enum LLM calls: almost none.** The key validator answers only "is the key valid?".
  `ledger/CategoryAgent` picks one of N categories for unknown merchants. Everything that must be
  right is already deterministic: the §4 gate, `CrisisDetector`, and the matchers.

## Names Kevin used, checked

| He said | Real thing | Verdict |
|---|---|---|
| GPT Live | `gpt-live-1` (2026-09-10): full duplex, voice only; reasoning and tools delegated to a backend model (`gpt-6-luna` / `gpt-6-sol`). $0.05/min flat plus backend. Also `gpt-realtime-2.1` (turn-based, tools in-model; 60 min sessions) | Exists. LEGION's shape (tools inside the voice model) maps to Realtime, not GPT-Live |
| GPT 6 sol | Tier name: Astra > Sol > Luna. `gpt-6-sol` / `gpt-6.1-sol` at $2 / $10 per 1M; `gpt-6-luna` at $0.10 / $0.50 | Exists |
| Decisions API | `POST /v1/decisions`, `gpt-6-luna` only, GA 2026-10-22. Predicate (probability), choice, score. $0.10/1M input, output free, 130-280 ms in customer reports. No audio input | Exists. **Not deterministic** by its own docs. Community tests: predicates well calibrated; choice overconfident and order-sensitive; score unreliable |
| JEV | TypeSafe AI's **Jev** ("System One"), out of stealth 2026-09-15. Decision-only outputs, $0.042/1M input, 70-500 ms (vendor) | Outputs decisions only: yes. **Deterministic: no.** Signups paused 2026-09-22. Not Meta's JEPA |
| grokbot | xAI **Grok Bot**: always-on agents on one shared cloud computer per account. From $30/mo X subscription | Consumer product, **no API**; all bots share cookies and credentials |
| dots | OpenAI **Dots**: persistent ChatGPT agents on GPT-6 Astra, own sandbox, 4,000+ connectors, scheduled. Pro / Business Premium | Consumer product, **no API**. The developer equivalent is the Agents API (beta) |

## Voice cost, per minute

| Model | In | Out | Note |
|---|---|---|---|
| gemini-3.8-live (current) | $0.005 | $0.018 | Free tier; no shutdown date |
| gpt-realtime-2.1 | ~$0.019 | ~$0.077 | Arithmetic on a secondary tokens-per-minute assumption |
| gpt-realtime-2.1-mini | ~$0.006 | ~$0.024 | Same assumption |
| gpt-live-1 | $0.05 flat | | Plus backend model and tools |

## Agent platforms that could matter

- **MCP spec 2026-07-28:** stateless (no initialize, no session id), required Mcp-Method/Mcp-Name
  headers, JSON Schema 2020-12, Client ID Metadata Documents for OAuth. LEGION's engine MCP server
  predates it.
- **Consumer apps that call a remote MCP:** the Gemini app "custom apps" (US, 18+, Keep Activity on;
  third-party guides only) and ChatGPT developer-mode connectors. Claude connectors were not researched.
- **Scheduled hosted agents:**
  - Claude Managed Agents: $0.08 per running hour plus tokens; cron deployments, memory, vaults.
  - Gemini Managed Agents (Interactions API, `background: true`, remote MCP): preview, pricing not found.
- **Android AppFunctions:** the app acts as an on-device MCP server that Gemini calls. Android 16+,
  Gemini side is a private preview. Whether the A25 runs Android 16 is unknown.
- **On-device Gemini Nano (ML Kit GenAI):** foreground only (`BACKGROUND_USE_BLOCKED` from a
  service), and the A25 is not on the device list.
- **Memory products (Mem0, Zep, Letta):** duplicate LEGION's own records and cut against the
  anchored-memory rule.
- **xAI API:** remote MCP inside speech-to-speech (`grok-voice-3.0`). Per-minute price not found.

## Unverified

GPT-Live session limits and GA status; the official Android SDKs (none found); flash-lite
`text/x.enum` support; Gemini Live remote-MCP support; pricing for Gemini Managed Agents, the OpenAI
Agents API sandbox and Grok Voice; Gemini app custom-MCP eligibility (third-party only).
