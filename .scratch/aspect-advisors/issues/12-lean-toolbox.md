# Research: lean toolbox - tool discovery for the live session

Type: research
Status: resolved

## Question

Kevin, mid-grilling on the advisor contract: "we have context bloat... instead of many tools
riding a live session, can we add something that the orchestrator can use to discover the right
tools so we keep context lean?" LEGION declares ~69 tools (`service/LiveToolbox.kt`, ~3,900
lines) on every Gemini Live session, on Kevin's key.

Establish the facts, then sketch the design space:

1. Does the Gemini Live API allow changing/adding tool declarations mid-session, or are they
   fixed at session setup? (Primary docs; note API version.)
2. Does a dispatch pattern work on the Live API: a small core toolset plus
   `discover_tools(domain)` returning tool descriptions as a function RESPONSE, and a generic
   `call_tool(name, args_json)` executor? What does it cost in reliability (the model calling
   tools it only saw in a response, argument-schema drift)?
3. What do prompt tokens actually cost today: measure or estimate the token weight of the
   current 69 declarations vs a core-plus-discovery set.
4. Prior art: how others thin large toolsets for realtime/voice models (routing by domain,
   per-mode toolsets, tool RAG).

Deliverable: findings in `research/lean-toolbox.md` with a recommended shape and its risks.
The decision itself (whether/how to adopt) is a follow-up grilling, likely folded into the
token-budget ticket's ceiling.

## Answer

Findings in [research/lean-toolbox.md](../research/lean-toolbox.md).

Gist: the Live API (v1beta BidiGenerateContent, the endpoint GeminiLiveSession actually
uses) fixes tool declarations at session setup - "You cannot update the configuration
while the connection is open" - so mid-session tool swapping is off the table (OpenAI's
Realtime API allows it; Gemini's does not). Session resumption is the only reconfigure
path and is unverified for tool changes. The toolbox is now 71 tools (not 69), estimated
~10-11k prompt tokens (chars/4 over the declarations() source minus comments), riding
every socket including prewarms. A core set of 12-15 tools plus discover_tools(domain) +
call_tool(name, args_json) estimates ~2.2-2.7k tokens, saving ~8k (~75-80%). The
discover+dispatch pattern needs no API support (schemas ride a function response; this is
MCP's tools/list -> tools/call contract, shipped natively by Anthropic as tool search),
with known risks: one extra model turn of voice latency on first domain use, undeclared-
tool hallucination, lost server-side arg validation, and no published reliability data on
flash-live audio models. Recommendation: core + discover/dispatch with 6-8 static aspect
buckets (no RAG), a validating call_tool that returns corrective errors, spike one domain
before migrating; keep the mail episodic-exclusion keyed on the inner tool name.

Assumptions ledger:
- Tools fixed at setup, no mid-session update: **researched** (ai.google.dev/api/live;
  live-api/tools).
- OpenAI session.update contrast: **researched** (platform.openai.com Realtime reference).
- Resumption allows a changed toolset: **reasoned** (docs silent; forum-implied; needs spike).
- 71 tools; registration at 3 LiveSessionController sites incl. prewarm; onboarding has a
  separate 5-tool set; episodic mail-exclusion keys on tool name: **traced**.
- ~10-11k tokens current / ~2.2-2.7k core+discovery / ~8k saved: **estimated** (chars/4 on
  measured 43,829 non-comment source chars; could run 20-30% hot; verify with countTokens).
- Discover+dispatch works on flash-live audio specifically: **reasoned** (prior art is
  text-model and MCP; no realtime-voice published data).
