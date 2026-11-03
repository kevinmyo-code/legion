# Call sequences today: car, money, morning, laptop (traced 2026-11-03, origin/dev 36ef92ef)

Read from the declarations and dispatch code, **not from a captured conversation**. Every count
below is `traced`, not `on-device`. Ticket "Baseline: measure round trips from recorded
conversations" replaces these with measured numbers.

## Phone (`app/src/main/java/com/kevin/legion/service/LiveToolbox.kt`)

How a tool reaches the Live model:

- `DISPATCHED` (2241-2290): domain -> read tools hidden from Live, reached through a router.
  fleet: `get_next_service`, `check_recalls`, `get_codes`, `list_vehicles`, `ask_maintenance`, 12 more.
  pantry: `list_recent_groceries`, `get_grocery_spend`.
- Router declarations: `ask_fleet` 2340, `ask_body` 2350, `ask_goals` 2362, `ask_pantry` 2376,
  `ask_mail` 2386, `ask_engine` 2398.
- `ask_fleet` & co: `SubAgent.route` (`ai/SubAgent.kt:452`): ONE router POST
  (`gemini-3.5-flash-lite`, `SubAgent.kt:946`), parallel fan-out of the picked tools, raw results
  back to Live. One round only: a dependent second lookup is impossible. Budget 42 s
  (`DISPATCH_ROUTE_BUDGET_MS`, 2511).
- `ask_engine` (`service/AskEngine.kt`): `tools/list` on `/mcp`, then `SubAgent.investigate`,
  `maxModelCalls = 4`, budget 40 s (6565-6572). Up to 4 model POSTs plus MCP calls.
- `NON_BLOCKING_READ_TOOLS` (3101) = `ask_fleet`, `ask_body`, `ask_goals`, `ask_pantry`. Everything
  else BLOCKING. `ask_engine` is excluded on purpose (it can write). ADR 0065.

| Ask | Path today | Model round trips | Data source |
|---|---|---|---|
| "How's the car / anything due" | Live -> `ask_fleet` (non-blocking) -> router picks among `get_next_service`, `check_recalls`, `get_codes`, `list_vehicles` -> Live speaks | 2 (router + Live reply); 3+ if router picks `ask_maintenance` | `VehicleController.nextService` (`vehicle/VehicleController.kt:1618`) on `FleetEngineStore`, a LOCAL mirror of engine fleet rows |
| "What did I spend this month / money" | Live calls `get_monthly_spend` (2959 -> 4680), `get_balance` (838 -> 2926), maybe `ask_pantry` -> `get_grocery_spend`, in sequence, all BLOCKING except the pantry router | 1 Live turn per tool chained, typically 2-3 | LOCAL Room: `LedgerController.budgetVsActual` (`ledger/LedgerController.kt:310`), `LedgerController.accountBalances`, `PantryController.totalSpendCentsByCurrency` |
| "Morning briefing / what's my day" | Live -> `get_sitrep` (1861, dispatch 2978), ONE tool by design ("ONE tool, never six", 1850) | 1 | Engine `/api/brief/latest` (`brief/BriefBackend.kt:54`), saved-copy fallback, plus phone calendar + weather |
| Engine-only reads (`next_service`, `monthly_spend`, `account_balances`, `grocery_spend`, `vehicle_recalls` on the engine) | Live -> `ask_engine` (BLOCKING) -> up to 4 sub-agent POSTs, each picking an MCP tool | 2-5 | Django `engine_mcp/tools.py` handlers (`_next_service` 908, `_account_balances` 1036, `_grocery_spend` 1127, `_vehicle_recalls` 1207, `_monthly_spend` 1475) |

**Two implementations of each money/car figure exist today.** The phone's voice tools compute them
from Room / `FleetEngineStore`; the engine's parity tools compute them in Django. CLAUDE.md §7
("a business rule lives in Django once, never also in Kotlin") is already strained here. A
procedure that runs on the engine makes the engine copy the one voice hears.

**`get_sitrep` is already a procedure** in everything but name: a fixed sequence of deterministic
sections composed by code (`server/brief/compose.py`, "Only News reaches an LLM"), one tool call,
same answer every time. The map's runner generalises that pattern; it does not replace the brief.

## Head unit (`headunit/`)

- One `ask_engine(question, intent)` replaced 16 hand-written engine tools on 2026-10-10
  (`headunit/CLAUDE.md:185`). Code: `headunit/app/src/main/java/com/kevin/midnightai/service/AskEngine.kt`
  (`ask` at 117), `backend/engine/EngineMcpClient.kt`. Same `/mcp` `tools/list` then `tools/call`.
- Declared at `LiveToolbox.kt:731`, dispatched 828, `maxModelCalls = 4` (1126-1129).
- No NON_BLOCKING on the head unit. Glance cards auto-dismiss after 5 s, most tools audio-only
  (`headunit/CLAUDE.md:169`). Persona "Uncle Moose", short blunt sentences (37). Runs on the
  driver's own Gemini key (56). No response-length rule found.
- So "anything due on the car" from the head unit = 1 Live call + up to 4 sub-agent POSTs + 1-2
  MCP calls, all blocking, while driving.

## Web / desk and recruiter (`server/assistant/`)

- Browser Live session calls the registry directly through `POST /api/assistant/tool`
  (`assistant/views.py` `AssistantToolView.post`, 423): `surface.refusal_for` first (435), then
  `engine_mcp.tools.run_tool` (447). No sub-agent hop: each tool is one Live round trip.
- "Car check" on web = Live calls `next_service`, then `vehicle_recalls`: 2 round trips.
  "Money" = `monthly_spend`, `account_balances`, maybe `grocery_spend`: 2-3.
- Every call is written to the conversation record (`devicelogs/web_conversation.py`
  `record_tool`, 233) with args, trimmed result and engine-measured latency.
- Recruiter: `RECRUITER_TOOLS` (`assistant/surface.py`) is a default-deny allowlist;
  `tests/test_recruiter.py` fails until every registry tool is classified in it or in
  `RECRUITER_EXCLUDED`.

## Laptop (Claude session)

- `.scratch/claude-session/` is parked (`kiv`, Kevin 2026-10-29: "dont build it yet"). ADR 0067
  lives on `feat/claude-session*` branches, **not on dev**. No worker, no `/api/claude/` routes, no
  `claude_session` tool on dev. Nothing to trace; the spec is `map.md:82-120` there.
