---
map: engine-mcp
ticket: "11"
title: "The phone bridges engine tools into its Live session"
type: build
status: built
status-detail: "Built and suite green 2026-10-02; owes an A25 run (ask an engine-only question, engine up and down) and LEGION_MCP=on on the deployed engine"
blockers: ["04", "10"]
blocked-by: ["[[04-how-the-phone-consumes-engine-tools]]", "[[10-the-engine-mcp-endpoint]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# The phone bridges engine tools into its Live session

## Build (shape fixed by 04)

**04 ruled 2026-10-02 (Kevin: *"go with your recs"*): one `ask_engine` tool on the phone.** One Gemini declaration, `ask_engine(question)`, bridged at runtime to `/mcp` (ticket 10): a sub-agent fetches `tools/list`, picks the engine tool, calls it on the phone's device token, and relays the result. The existing Room-backed tools in `LiveToolbox.kt` are untouched; only server-native questions go through it. Its declaration cost is constant however many engine tools exist.

These hold:

- Calls go out on the phone's existing device token (`EngineAuth`), through the existing transport
  in `backend/engine/`.
- **Engine unreachable is a sentence, not an empty result.** "The engine is unreachable, so nothing
  was read" - never "you have nothing due".
- **No mail-touched content crosses the bridge.** A turn that touched an `EPISODIC_EXCLUDED_TOOLS`
  tool does not forward that content as an MCP argument. Test it.
- The outcome-verb rule holds: a bridged write (when there are any) is reported done only on a
  successful result in that turn.
- **A hands path** for anything the bridge newly makes reachable by voice (ADR 0035), calling the
  same engine code.
- New tool names get voice-guide copy (`tools/voice_guide_copy.py`) or `voice_guide.py` fails.

## Verification

- Unit: the `ask_engine` declaration, tools/list to sub-agent translation, offline sentence, mail exclusion.
- On the A25: ask a question only the engine can answer, with the engine up and with it down.

## Built (2026-10-02)

Phone only. `service/AskEngine.kt` (bridge + `GeminiSchema` translation), `backend/engine/EngineMcpClient.kt` (JSON-RPC over `EngineHttp`, 5 minute `tools/list` cache), one declaration in `LiveToolbox.declarations()` plus a dispatch branch. `EngineHttp.post` gained an optional `extraHeaders` (Accept and the MCP routing headers); every other caller is unchanged.

- **Shape.** `ask_engine(question, intent)`: fetch `tools/list` from `<base>/mcp` on the device token, hand each engine tool to a one-shot `SubAgent.investigate` as an `AgentTool` (JSON Schema 2020-12 narrowed to Gemini's subset; a bare `object` param such as `fields` is sent as a JSON string and parsed back), relay the text.
- **Unreachable / 404 (MCP off) / 401-403 / not signed in** each answer a sentence beginning with what did not happen; the agent never starts. A transport failure on a WRITE says it "cannot be confirmed", never "was not written".
- **Writes.** The loop's `mutatingToolsCalled` counts a tool that ran even when it returned `isError`, so the bridge keeps its own tally. An `isError` write turns the whole answer into a failure carrying the server's sentence, over the model's prose; `intent=record` with nothing committed is a failure; a write tool called under `intent=ask` is refused before anything is sent.
- **Mail.** `touchedReadThroughToolThisTurn` refuses the whole call before ANY request (not even `tools/list`). Limit: the flag is set when a mail functionCall arrives, so mail called after `ask_engine` in one turn is not seen.
- **Token scope.** The phone's token comes from `LoginView` (`DeviceToken.issue(user, device_name)`, default scope) and migration `household/0004` defaults existing tokens to `write`, so the phone can write over MCP. Only a token minted with `manage.py issue_device_token` (default `read`) is read-only.
- **Hands path (ADR 0035).** Every table `read_records`/`write_record` reaches is one the app already shows (events and checklists, places, body, ledger, pantry, fleet, recordings) except `chassis_quirks` and `drive_reassignments`, which appear in no `ui/` file. The bridge removes both from the model's `table` enum and refuses a direct naming. No UI added.

**Verification, accounted for (L11):** unit tests for declaration, translation, offline sentences, isError write, mail exclusion, screenless refusal, cache: done (`AskEngineTest`). Voice-guide copy and `voice_guide.py`: done. Engine-only question on the A25 with the engine up and down: **deferred, owed**, needs the deployed engine on `LEGION_MCP=on` and a phone build. Whether Gemini accepts every translated schema and picks tools sensibly: **owed on-device**, not provable by a mock.
