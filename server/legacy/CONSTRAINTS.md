# CHECK constraints on the 41 legacy tables

`inspectdb` reports nothing for CHECK constraints - it only sees columns,
types and foreign keys. This file is the list ticket 02 asks for: every
CHECK Postgres itself enforces on `public`, read from `pg_constraint`
against the live schema (`legion_reader`, 2026-09-05), so the API layer
knows exactly what a write can be refused for before it ever reaches
Postgres. `legacy` models are `managed = False` and reproduce none of this
as Django validation; this table is the source of truth until a real
writer (ticket 03 onward) needs to explain a refusal in words.

Two Postgres ENUM types also bind columns typed `provenance` or
`ingest_state` everywhere in this schema except `voice_notes.provenance`
(see below); their allowed values are in `legacy/enums.py`
(`Provenance`, `IngestState`) and are not repeated here.

## bodyweight_logs
- `weight_value > 0`
- `trust_tier IN ('PROVEN', 'REPORTED')`
- `weight_unit IN ('lbs', 'kg')`

## budget_targets
- `currency IN ('SGD', 'USD')`

## build_entries
- `length(trim(title)) > 0`
- `length(trim(sync_id)) > 0`
- `cost_cents IS NULL OR cost_cents >= 0`
- `mileage IS NULL OR mileage >= 0`

## chassis_quirks
- `length(trim(quirk_id)) > 0`
- `mileage_low IS NULL OR mileage_low >= 0`
- `mileage_high IS NULL OR mileage_high >= 0`
- `cost_low_cents IS NULL OR cost_low_cents >= 0`
- `cost_high_cents IS NULL OR cost_high_cents >= 0`
- `severity IN ('MONITOR', 'SERVICE_SOON', 'CRITICAL')`

## code_clear_events
- `length(trim(sync_id)) > 0`
- `mileage IS NULL OR mileage >= 0`
- `jsonb_typeof(codes_before) = 'array'`
- `codes_after IS NULL OR jsonb_typeof(codes_after) = 'array'`
- `freeze_frame IS NULL OR jsonb_typeof(freeze_frame) = 'object'`
- `outcome IN ('CLEARED', 'RETURNED', 'UNVERIFIED')`
- `(outcome = 'UNVERIFIED') = (codes_after IS NULL)` - unverified means no
  after-codes were read, and vice versa

## code_events
- `length(trim(sync_id)) > 0`
- `mileage IS NULL OR mileage >= 0`
- `jsonb_typeof(codes) = 'array'`
- `freeze_frame IS NULL OR jsonb_typeof(freeze_frame) = 'object'`

## companion_memories
- `length(trim(text)) > 0`
- `importance BETWEEN 1 AND 10`
- `source IN ('consolidated', 'reflection', 'stated')`
- `category IN ('car_anchored', 'driver', 'relationship')`

## conversation_audit
- `length(trim(device_id)) > 0`
- `kind IN ('user', 'companion', 'tool_result')`

## drive_reassignments
- `length(trim(sync_id)) > 0`
- `to_at >= from_at`

## drives
- `length(trim(sync_id)) > 0`
- `miles >= 0`
- `gallons IS NULL OR gallons >= 0`
- `ended_at >= started_at`

## events
- `length(trim(title)) > 0`
- `kind IN ('reminder', 'event', 'task')`
- `source IN ('legion', 'google')`
- `repeat_kind IS NULL OR repeat_kind IN ('DAILY', 'WEEKLY', 'MONTHLY_ON_DATE', 'YEARLY')`
- `repeat_every IS NULL OR repeat_every > 0`
- `repeat_day IS NULL OR repeat_day BETWEEN 1 AND 31`
- `repeat_month IS NULL OR repeat_month BETWEEN 1 AND 12`
- `repeat_end_kind IS NULL OR repeat_end_kind IN ('NEVER', 'ON_DATE', 'AFTER_COUNT')`
- `repeat_end_count IS NULL OR repeat_end_count > 0`
- `repeat_kind IS NULL OR done = false` - a recurring event/task is never
  itself marked done
- `repeat_end_kind IS NULL OR repeat_kind IS NOT NULL` - an end rule needs a
  repeat rule to end

## ledger_transactions
- `(provenance = 'UNRECONCILED' AND statement_id IS NULL) OR (provenance <> 'UNRECONCILED' AND statement_id IS NOT NULL)` -
  a provisional row has no statement to point at; every other row must
- `reversal_of IS NULL OR provenance <> 'UNRECONCILED'` - a reversal can
  never itself be provisional
- `account_last4 ~ '^[0-9]{4}$'`
- `currency IN ('SGD', 'USD')`

## maintenance_schedules
- `interval_months IS NULL OR interval_months > 0`
- `interval_miles IS NULL OR interval_miles > 0`
- `interval_miles IS NOT NULL OR interval_months IS NOT NULL` - needs at
  least one interval

## meal_logs
- `trust_tier IN ('PROVEN', 'REPORTED')`
- `length(trim(description)) > 0`

## meal_targets
- `carbs_g >= 0`
- `fat_g >= 0`
- `protein_g >= 0`
- `calories_kcal > 0`

## memories
- `length(trim(text)) > 0`

## memory_audit
- `store IN ('memories', 'companion_memories', 'speech')`
- `event IN ('written', 'deleted', 'recall', 'recalled', 'spoken')`

## obd_samples
- `length(trim(pid)) > 0`

## oil_analyses
- `tbn IS NULL OR tbn >= 0`
- `viscosity_cst IS NULL OR viscosity_cst >= 0`
- every metal column (`boron`, `iron`, `copper`, `lead`, `tin`, `aluminum`,
  `chromium`, `nickel`, `sodium`, `potassium`, `silicon`, `magnesium`):
  `IS NULL OR >= 0`
- `length(trim(sync_id)) > 0`
- `mileage IS NULL OR mileage >= 0`
- `drain_interval_miles IS NULL OR drain_interval_miles > 0`
- `fuel_percent IS NULL OR fuel_percent >= 0`
- `water_percent IS NULL OR water_percent >= 0`

## places
- `latitude BETWEEN -90 AND 90`
- `length(trim(label)) > 0`
- `longitude BETWEEN -180 AND 180`

## receipt_line_items
- `quantity > 0`

## receipts
- `currency IN ('SGD', 'USD')`
- `provenance <> 'UNRECONCILED' OR unaccounted_cents IS NOT NULL` - a
  provisional receipt must state its unaccounted amount
- `unaccounted_cents IS NULL OR (unaccounted_cents <> 0 AND provenance = 'UNRECONCILED')` -
  the column may only be non-null, non-zero, and provisional together;
  see CLAUDE.md section 4 rule 7's amendment for why this exists and is
  never derived as `total - sum(lines)`

## service_history
- `kind IN ('OBSERVED', 'ASSERTED')`
- `mileage IS NULL OR mileage >= 0`

## sleep_logs
- `quality IS NULL OR quality BETWEEN 1 AND 5`
- `duration_minutes BETWEEN 0 AND 1440`
- `trust_tier IN ('PROVEN', 'REPORTED')`

## sleep_targets
- `target_minutes BETWEEN 0 AND 1440`

## statements
- `account_last4 ~ '^[0-9]{4}$'`
- `currency IN ('SGD', 'USD')`
- `provenance <> 'UNRECONCILED'` - a statement itself may never be
  provisional (only the transactions inside one can be, per
  `ledger_transactions`'s check above)
- `stated_total_cents IS NOT NULL OR provenance = 'DETERMINISTIC'` -
  section 4 rule 7: only a deterministically-extracted statement may omit
  the printed total
- `length(trim(account_nickname)) > 0`
- `period_end >= period_start`

## vehicle_specs
- `displacement_l IS NULL OR displacement_l > 0`
- `engine_hp IS NULL OR engine_hp > 0`
- `doors IS NULL OR doors > 0`
- `engine_cylinders IS NULL OR engine_cylinders > 0`

## vehicles
- `year BETWEEN 1885 AND 2200`
- `(odometer_baseline IS NULL) = (odometer_baseline_at IS NULL)` - both or
  neither
- `odometer_baseline IS NULL OR odometer_baseline >= 0`

## voice_notes
- `provenance = 'LLM_DERIVED'` - the only column in this schema typed
  `text` with a CHECK pinning it to one literal, rather than the shared
  `provenance` enum type. See the hand-correction note in
  `legacy/models/notes.py`.
- `kind IN ('SOLO', 'MEETING')`
- `summary IS NULL OR transcript IS NOT NULL` - never a summary without the
  transcript it was built from

## workout_plan_items
- `length(trim(exercise)) > 0`
- `target_sets_per_week > 0`

## workout_plans
- `sessions_per_week >= 0`

## workout_set_logs
- `length(trim(exercise)) > 0`
- `sets > 0`
- `trust_tier IN ('PROVEN', 'REPORTED')`
- `weight_unit IS NULL OR weight_unit IN ('lbs', 'kg')`
- `weight_value IS NULL OR weight_value >= 0`
- `reps IS NULL OR reps > 0`

## Tables with no CHECK constraints at all

`categories`, `category_rules`, `event_skips`, `goals`, `grocery_staples`,
`household_members`, `ingested_files`, `item_lists`, `list_items` - nine of
the 41. These rely only on `NOT NULL`, their unique indexes, and their
foreign keys.
