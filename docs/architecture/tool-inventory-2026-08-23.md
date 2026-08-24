# Tool inventory, 2026-08-23 (ticket 17's owed L11 item from ticket 06)

Ticket 06 ("The meta-tool surface") deferred one of its own answer points with a named
follow-up: *"the inventory of the existing 97 tools - which die into meta-tools, which survive as
plugin verbs, which need a call - live in [ticket 17]. Not silently dropped; owed there."* This is
that inventory, produced before anything below is deleted (ticket 17's own instruction: **build
the nine meta-tools alongside the old tools, authorize deletion here, do not delete in this
pass**).

**Count correction.** The "97" figure in CLAUDE.md/ticket 06 was already stale by the time this
ran - a live scrape of `name = "..."` in `service/LiveToolbox.kt` on this date returns **104**
distinct tool names (the 97 the ticket remembered, plus the five `ask_*` domain dispatchers added
2026-08-18, which are themselves declarations, not dispatched-away names). All 104 are classified
below. None of the nine new `EngineToolbox.kt` meta-tools are in this table - this inventory is
about what already existed, not what ticket 17 added.

## The three buckets

- **Dies into meta-tools** - pure record CRUD (create/read/update/list a fact the user stated)
  that the nine meta-tools (`list_aspects`/`describe_aspect`/`query_records`/`create_record`/
  `update_record`/`delete_record`/`aspect_clerk`) already do generically, once that domain's data
  actually lives in the engine's `aspects`/`record_types`/`field_defs`/`records` tables instead of
  its own hand-rolled entity. **Deleting one of these tools today would be premature** - the
  underlying domain (fleet/ledger/pantry/body/goals/notes) has not been migrated onto the engine
  yet (CLAUDE.md §10's "migration-wave work", ticket 16's own brief: "do NOT rewire ledger/pantry
  onto it yet"). This bucket names what CAN eventually die, not what dies today.
- **Survives as a native verb** - not record CRUD at all: a live hardware read (OBD), a physical
  action (garage relay, a phone call), a read-through of something the app is explicitly forbidden
  to store (mail, calendar - CLAUDE.md §7's read-through-only rule), a domain-specialist dispatch
  (`ask_fleet` and its four siblings), or app/assistant control. No generic CRUD tool could ever
  replace these - they call a controller, a socket, or a specialist sub-agent, not a table.
- **Needs a call** - genuinely ambiguous: real CRUD in shape, but wrapped in domain-specific
  business logic a generic `create_record`/`update_record` cannot reproduce without either losing
  that logic or duplicating it - the ledger reconciliation gate (CLAUDE.md §4), OBD-derived
  maintenance-interval math, VIN decoding, computed due-dates. Whether that logic moves INTO the
  engine (as a capability-plugin hook, ticket 11's own unbuilt API) or stays outside it and the
  tool survives natively is a decision for whichever ticket runs that domain's migration wave, not
  this one. Listed here, not silently assumed either way.

## The table

| Tool | Bucket | Why |
|---|---|---|
| `remember` | dies into meta-tools | Free-text memory write - `create_record` on a "Memory" aspect once one exists. |
| `recall_memory` | dies into meta-tools | Free-text memory read - `query_records`. |
| `why_did_you_say_that` | survives as native verb | Reads the proactive-raise audit trail, not user data. |
| `tag_place` | dies into meta-tools | Named-place CRUD - `create_record` on a "Places" aspect. |
| `forget_place` | dies into meta-tools | `delete_record` on the same aspect. |
| `show_saved_places` | survives as native verb | UI-scoped (`dispatch` returns null; opens a screen), not a data operation. |
| `set_reminder` | needs a call | CRUD in shape, but time/geofence-trigger scheduling is OS-integrated (AlarmManager/geofence), not a plain field write. |
| `read_list` | dies into meta-tools | `query_records` on a "Lists" aspect. |
| `manage_item` | dies into meta-tools | Add/tick/edit on the same aspect - `create_record`/`update_record`. |
| `read_calendar` | survives as native verb | Read-through of Google Calendar (CLAUDE.md §7) - never persisted, so there is no record to CRUD. |
| `open_navigation` | survives as native verb | Hands off to an external maps app. |
| `get_current_location` | survives as native verb | Live device sensor read. |
| `get_current_time` | survives as native verb | Live wall clock read. |
| `area_info` | survives as native verb | Live external API read (NWS/USGS/NIFC/FEMA), grounded and non-persisted by design. |
| `get_reported_crime_history` | survives as native verb | Live external API read (FBI CDE). |
| `get_sitrep` | survives as native verb | Orchestrates several live reads into one briefing - not itself a record. |
| `show_app` | survives as native verb | UI navigation. |
| `end_conversation` / `finish_intro` | survives as native verb | Session/onboarding lifecycle control. |
| `get_codes` / `diagnose_codes` / `clear_codes` / `get_code_history` | survives as native verb | Live OBD trouble-code reads/writes over Bluetooth - not stored facts, the car's own ECU state. |
| `triage_symptom` / `check_readiness` / `check_cold_start` | survives as native verb | Live OBD reasoning/monitor state. |
| `get_vehicle_data` / `read_vehicle_sensor` / `get_health` / `get_mpg` / `get_trend` | survives as native verb | Live or derived OBD telemetry. |
| `get_specs` / `lookup_vin` / `check_recalls` | survives as native verb | External lookups (factory specs, VIN decode, NHTSA) - not user-stated facts to store. |
| `get_next_service` / `ask_maintenance` | needs a call | Reads service history AND runs due-date math against odometer/interval - the math is the part a plain `query_records` cannot reproduce without a computed-field hook the engine does not have wired to OBD data yet. |
| `log_service` / `log_past_service` | dies into meta-tools | A service record is a plain fact once maintenance math above is settled separately - `create_record`. |
| `set_maintenance_interval` | needs a call | Configures the due-date math `get_next_service` runs, not a standalone fact. |
| `set_odometer` | needs a call | Same reason - feeds the due-date math directly. |
| `log_build_entry` / `list_build_history` | dies into meta-tools | Free-text build log - `create_record`/`query_records`. |
| `register_car` / `register_vehicle` / `manage_vehicle` / `list_vehicles` | needs a call | Vehicle identity is read by nearly every other fleet tool (`VehicleResolver`, OBD MAC binding) - migrating it changes what every OBD-connected tool resolves against, a bigger decision than one CRUD swap. |
| `ask_fleet` / `ask_body` / `ask_goals` / `ask_pantry` / `ask_mail` | survives as native verb | Domain-dispatcher sub-agents (ticket 06 answer point 1 draws the same line: schema/record CRUD is generic, but a dispatcher choosing which of a domain's OWN tools to call is a different kind of thing). |
| `activate_garage` | survives as native verb | Physical relay action. |
| `control_volume` | survives as native verb | Device control. |
| `import_statement` / `get_balance` / `get_spend` / `get_monthly_spend` / `list_recent_transactions` | needs a call | Ledger's reconciliation gate (CLAUDE.md §4) is load-bearing on this data - a generic `create_record` has no reconciliation step, so moving ledger onto the engine means teaching the engine gate-aware writes (ticket 16's `ReconciliationGate` interface exists for exactly this, but nothing implements it yet). |
| `categorize_transactions` / `set_category` | needs a call | Same reconciliation-gated data. |
| `set_budget` / `list_budget_categories` | dies into meta-tools | Budgets are plain user-declared targets, not reconciled - `create_record`/`query_records` once ledger's schema exists in the engine at all. |
| `log_pending_transaction` / `list_pending_transactions` / `clear_pending_transaction` | dies into meta-tools | Explicitly NOT reconciled (voice-logged, pre-bank) - the closest existing tool to what `create_record`/`delete_record` already do. |
| `import_receipt` | needs a call | Pantry's LLM-vision extraction path (CLAUDE.md §4 point 1) is a capture flow (camera), not a field write - stays a distinct action even after a data migration. |
| `manage_grocery` / `list_recent_groceries` / `get_grocery_spend` | dies into meta-tools | Once receipts are ingested, the resulting line items are plain records - `create_record`/`update_record`/`query_records`. |
| `log_meal` / `list_recent_meals` / `get_meal_gap` / `set_meal_target` | dies into meta-tools | Plain user-stated facts and a target value - `create_record`/`query_records`. Macro estimates keep the estimate-labelling rule regardless of which tool writes them. |
| `log_workout_set` / `list_recent_workouts` / `get_workout_gap` | dies into meta-tools | Same shape as meals. |
| `create_workout_plan` | needs a call | Generates a multi-week structured plan from a goal - closer to `aspect_clerk`'s own multi-row generation shape than a single `create_record`, but with domain-specific plan logic `aspect_clerk` does not have. |
| `log_bodyweight` | dies into meta-tools | Plain fact - `create_record`. |
| `log_sleep` / `list_recent_sleep` / `get_sleep_gap` / `set_sleep_target` | dies into meta-tools | Same shape as meals/workouts. |
| `set_goal` / `list_goals` / `close_goal` | dies into meta-tools | Plain CRUD on a goal record. |
| `ask_advisor` / `accept_proposal` / `generate_goal_plan` / `accept_goal_plan` | needs a call | Advisor reasoning and multi-step plan proposals - business logic well beyond CRUD, same shape as `create_workout_plan`. |
| `undo_last_log` | needs a call | Currently a special "undo the last of four specific log types" action; the engine's own trash/restore (`RecordStore.restore`) is a cleaner replacement, but wiring it needs each log type to already be an `EngineRecord` first. |
| `play_music` / `control_music` / `get_music_queue` / `browse_my_music` | survives as native verb | Spotify App Remote / MediaSession live control, not stored data. |
| `search_mail` / `read_mail` / `track_package` / `flight_status` | survives as native verb | Read-through of Gmail (CLAUDE.md §7) - never persisted, so never a CRUD target. |
| `answer_call` / `decline_call` / `place_call` | survives as native verb | Live telephony actions. |
| `set_companion_name` / `set_personality` / `set_driver` | survives as native verb | Assistant identity/config, not user domain data. |

## Rollup

Counted directly off the table's own rows (one count per tool name, multi-tool rows split out),
reconciled against the 104 names `voice_guide.py`'s `declared_tools()` scrapes from
`LiveToolbox.kt` - CLAUDE.md §4 rule 6's "a check that passes when nothing parsed is not a gate"
applied to this document itself: the three bucket counts must sum to 104 exactly, or one of them is
wrong.

- **Dies into meta-tools (eventually, not today): 33.** `remember`, `recall_memory`, `tag_place`,
  `forget_place`, `read_list`, `manage_item`, `log_service`, `log_past_service`,
  `log_build_entry`, `list_build_history`, `set_budget`, `list_budget_categories`,
  `log_pending_transaction`, `list_pending_transactions`, `clear_pending_transaction`,
  `manage_grocery`, `list_recent_groceries`, `get_grocery_spend`, `log_meal`, `list_recent_meals`,
  `get_meal_gap`, `set_meal_target`, `log_workout_set`, `list_recent_workouts`, `get_workout_gap`,
  `log_bodyweight`, `log_sleep`, `list_recent_sleep`, `get_sleep_gap`, `set_sleep_target`,
  `set_goal`, `list_goals`, `close_goal`.
- **Survives as a native verb: 48** - every hardware read, physical action, read-through source,
  domain dispatcher, and assistant-control tool: `why_did_you_say_that`, `show_saved_places`,
  `read_calendar`, `open_navigation`, `get_current_location`, `get_current_time`, `area_info`,
  `get_reported_crime_history`, `get_sitrep`, `show_app`, `end_conversation`, `finish_intro`,
  `get_codes`, `diagnose_codes`, `clear_codes`, `get_code_history`, `triage_symptom`,
  `check_readiness`, `check_cold_start`, `get_vehicle_data`, `read_vehicle_sensor`, `get_health`,
  `get_mpg`, `get_trend`, `get_specs`, `lookup_vin`, `check_recalls`, `ask_fleet`, `ask_body`,
  `ask_goals`, `ask_pantry`, `ask_mail`, `activate_garage`, `control_volume`, `play_music`,
  `control_music`, `get_music_queue`, `browse_my_music`, `search_mail`, `read_mail`,
  `track_package`, `flight_status`, `answer_call`, `decline_call`, `place_call`,
  `set_companion_name`, `set_personality`, `set_driver`.
- **Needs a call: 23** - everything wrapped in domain business logic (reconciliation gate,
  maintenance-interval math, vehicle identity resolution, advisor/plan reasoning) that a future
  migration-wave ticket must resolve explicitly: `set_reminder`, `get_next_service`,
  `ask_maintenance`, `set_maintenance_interval`, `set_odometer`, `register_car`,
  `register_vehicle`, `manage_vehicle`, `list_vehicles`, `import_statement`, `get_balance`,
  `get_spend`, `get_monthly_spend`, `list_recent_transactions`, `categorize_transactions`,
  `set_category`, `import_receipt`, `create_workout_plan`, `ask_advisor`, `accept_proposal`,
  `generate_goal_plan`, `accept_goal_plan`, `undo_last_log`.

**33 + 48 + 23 = 104.** Reconciles exactly against the scraped tool count - `reasoned` by hand
count against the table above, not machine-enforced; a follow-up could script this reconciliation
the same way `voice_guide.py --check` already scripts the copy-drift check, so a future edit to
this table cannot silently stop summing to the real tool count.

## What this authorizes and what it does not

This inventory **authorizes** a future ticket to delete a "dies into meta-tools" tool once its
domain's data genuinely lives in the engine and every caller (voice descriptions, tests,
`voice_guide_copy.py`) has been repointed at the generic meta-tools. It does **not** authorize
deleting anything in this pass - ticket 17's own instruction is explicit that the nine new
meta-tools are wired ALONGSIDE the 104 old ones, not in place of them, and no tool listed above was
touched by this ticket's implementation.
