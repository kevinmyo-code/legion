# Research: lean toolbox - tool discovery for the live session

Ticket: [12-lean-toolbox](../issues/12-lean-toolbox.md)
Researched: 2026-08-13

## 1. API facts

LEGION's endpoint (`service/GeminiLiveSession.kt`): **v1beta
`BidiGenerateContent` WebSocket**, `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent`,
model `models/gemini-3.1-flash-live-preview`, BYO key as `?key=` param. Tools go in the
one-time `setup` message (`buildSetup`, line ~552): `tools[]` alongside `googleSearch`.

- **Tool declarations are fixed at session setup. No mid-session update.** The Live API
  reference states tools are part of the session configuration sent in the initial
  `BidiGenerateContentSetup`, and "You cannot update the configuration while the connection
  is open." (https://ai.google.dev/api/live). The tools guide says the same: "You can define
  function declarations as part of the session configuration"; no update message exists
  (https://ai.google.dev/gemini-api/docs/live-api/tools). The only tool-related client
  message after setup is `BidiGenerateContentToolResponse` (function RESULTS, not
  declarations).
- **Contrast: OpenAI's Realtime API allows it.** `session.update` "may be sent at any time
  to update any field except for voice and model", explicitly including `tools`
  (https://platform.openai.com/docs/api-reference/realtime-client-events/session-update).
  So the capability gap is Gemini-specific, not inherent to realtime voice.
- **Session resumption is the only path to a new toolset without losing context.** The
  server sends `SessionResumptionUpdate` handles (LEGION currently ignores them - see
  `handleServerMessage`: "goAway / sessionResumptionUpdate are ignored in this version");
  a new connection passing the handle resumes the conversation. Handles live 2h; a single
  connection lives ~10 min, audio-only session cap 15 min without context compression
  (https://ai.google.dev/gemini-api/docs/live-session). The docs do NOT explicitly say
  the resumed connection's setup may declare different tools; a forum thread and the
  general "setup rides every new connection" shape imply it (reasoned, not verified -
  would need a spike).
- **Context ceiling the declarations ride against:** 128k tokens for native-audio models,
  32k for other Live models (https://ai.google.dev/gemini-api/docs/live-api/capabilities).

## 2. What the toolbox costs today (measured/estimated)

Registration path (traced): `LiveSessionController` passes `LiveToolbox.declarations()` at
all three `session.start(...)` sites - prewarm (line 145) and both cold-start paths (346,
366) - so the full block rides **every socket, including prewarmed warm sockets that may
never carry a conversation**, on Kevin's key. Onboarding is the existing counterexample:
`onboardingDeclarations()` is a separate 5-tool set, proof that per-context toolsets
already work in this codebase.

Numbers (method stated):

| Quantity | Value | Method |
|---|---|---|
| Tools in `declarations()` | **71** | grep `fns.put(fn(` in lines 100-1171 of LiveToolbox.kt (ticket said ~69; two added since) |
| Onboarding-only tools | 5 | same grep, `onboardingDeclarations()` |
| Source chars, declarations() body | 52,051 trimmed; **43,829 excluding `//` comments** | PowerShell sum of trimmed line lengths, lines 100-1171 |
| Serialized JSON estimate | ~40-45k chars | comment lines removed; Kotlin call scaffolding (`fns.put(fn(name=...`, `schema(...)`) assumed to roughly net out against JSON scaffolding (`{"name":...,"parameters":{"type":"OBJECT",...}}`) |
| **Token estimate, current block** | **~10,000-11,000 tokens** | chars/4 |
| Avg per tool | ~617 chars ≈ **~155 tokens** | 43,829 / 71 |
| Core set of 12-15 tools | ~1.9-2.3k tokens | 12-15 x 155 |
| discover_tools + call_tool declarations | ~300-400 tokens | two tools, one with a domain enum, sized like current avg x2 |
| **Token estimate, core+discovery** | **~2.2-2.7k tokens** | sum |
| **Saving per session** | **~8k prompt tokens (~75-80%)** | difference |

Perspective: ~11k tokens is a third of a 32k Live context window before the persona prompt
or a word of conversation. On a 128k native-audio window it is less acute but still paid
into billed prompt context on every session, and the advisor effort is about to add
`ask_advisor` + goal tools on top.

Estimate caveat: chars/4 is crude for JSON (punctuation-heavy text tokenizes worse than
prose). Real number could be 20-30% higher. Verify with `countTokens` on the actual
serialized `declarations()` output before setting a budget ceiling.

## 3. Design space

| Shape | Works on this API? | Notes |
|---|---|---|
| **Per-mode toolsets at session start** | Yes, today | Already done for onboarding. But the main tap-to-talk session has no known domain at connect time, and prewarm happens before any utterance. Small wins only (e.g. drop mail/calendar tools when accounts unlinked). |
| **Domain routing at connect** | No | The domain is unknowable before the driver speaks; sessions are prewarmed precisely so the first utterance is instant. |
| **Discover + dispatch** (core set + `discover_tools(domain)` returning declarations in a function response + `call_tool(name, args_json)`) | Yes - needs no API support at all | The discovered schemas live in conversation context, not in the session's declared toolset. Models demonstrably can call through a generic executor from schemas seen in-context: this is exactly MCP's `tools/list` -> `tools/call` contract, and Anthropic ships it natively as the Tool Search Tool / deferred tools (Claude Code's own harness runs this pattern). Academic prior art on tool retrieval: MemTool (arxiv 2507.21428), Graph RAG-Tool Fusion (arxiv 2502.07223), Instruction-Tool Retrieval (arxiv 2602.17046) - consistent finding that selection reliability degrades as declared toolsets grow, and retrieval-based exposure restores it. Realtime-voice-specific published evidence is thin; the pattern's reliability on a Flash-class audio model is unproven (reasoned). |
| **Session restart / resumption with a new toolset** | Probably (unverified) | Resumption handle + fresh setup on a new connection. Costs a reconnect mid-conversation (awkward on voice), and LEGION currently ignores `sessionResumptionUpdate` entirely. Better held as the answer to the 10-min connection cap than as a tool-swapping mechanism. |

## 4. Recommended shape

**Core + discover/dispatch, with the domains pre-carved, not RAG'd.**

1. Keep **~12-15 fully declared core tools**: the high-frequency, latency-sensitive ones
   (live OBD reads, music transport, time, reminders, `ask_advisor` when it lands) plus
   `discover_tools(domain)` and `call_tool(name, args_json)`.
2. `discover_tools` takes a **closed enum of domains** (fleet-history, ledger, pantry,
   mail, calendar, places, ...) and returns that domain's full declarations - the same
   JSON `fn(...)` objects - as its function response. No embedding retrieval; ~6-8 static
   buckets already carved by aspect. LiveToolbox already groups by category in source
   order, so the split is mechanical.
3. `call_tool` validates `name` against the real registry and parses `args_json`; on any
   mismatch it returns a corrective error naming the valid tools/args so the model can
   retry in-turn rather than fail silently. Dispatch itself reuses the existing
   `LiveToolbox.dispatch(context, name, args)` unchanged.
4. Keep the episodic-exclusion check (`isEpisodicExcludedTool`) keyed on the INNER name
   `call_tool` carries, not on `call_tool` itself - otherwise the mail read-through rule
   silently stops matching (this is the one existing mechanism a generic executor would
   break; see GeminiLiveSession lines ~833-848).

**Risks, honestly:**

- **Extra round trip on voice latency.** First use of a non-core domain costs one
  discover cycle (client dispatch is local and instant, but the model must emit the call,
  receive the response, then emit the real call - roughly one additional model turn,
  order ~0.5-1.5s). Mitigation: core set covers the hot paths; discovery only taxes the
  long tail.
- **Undeclared-tool hallucination.** The model may call a remembered tool name directly
  (not via call_tool) that is no longer declared; the API will not dispatch it and the
  turn can dead-end. Mitigation: system-instruction line stating the contract; core
  descriptions that point at discover_tools.
- **Argument-schema drift.** `args_json` is an unvalidated string; the server-side schema
  enforcement that declared tools get is lost. Mitigation: validator in step 3; the
  schemas are simple (mostly strings + a few enums).
- **Context eviction.** Discovered schemas live in conversation context; on a long session
  with context-window compression they can slide out and the model re-calls discover or
  drifts. Sessions here are short (10s idle timeout), so low.
- **Unproven on this exact model.** No published reliability data for discover+dispatch on
  gemini-flash-live audio models. A spike (declare the pair, move ONE domain behind it,
  drive it by voice) is the honest next step before migrating 50+ tools.

**Not recommended:** resumption-based toolset swapping (mid-conversation reconnect on a
voice surface, on an unverified doc claim), and embedding/RAG tool retrieval (69 tools in
6-8 known buckets does not need a retriever).

Decision itself deferred to the follow-up grilling, per the ticket - likely folded into
the token-budget ceiling.
