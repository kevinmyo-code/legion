package com.kevin.legion.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * LEGION's Room database - fresh v1, ported from Midnight AI's v12 schema with
 * the retired tables dropped (billing, per-car companion identity, mixtape
 * library, music taste ledger). No migration chain: this is a new app with no
 * installed base, so there is nothing to migrate from. The v6-onward
 * additive-migration discipline (exportSchema + schemas/ + MigrationTest) that
 * governed Midnight AI resumes from here, starting at v1.
 *
 * v2: `ledger_transactions` (the ledger aspect's bank-statement ingestion,
 * `.claude/plans/wiggly-beaming-quasar.md`) - additive only, no destructive
 * fallback for the upgrade path even though there's no real installed base
 * yet, matching the discipline this project intends to keep from here on.
 *
 * v3: `pantry_receipts` + `pantry_line_items` (the pantry aspect's grocery-
 * receipt ingestion, same plan file).
 *
 * v4: `ingested_files` (the ledger Drive-ingestion scan's work-avoidance
 * record, `.scratch/ledger-drive-ingestion/issues/03-ingested-file-ledger.md`)
 * plus a nullable `LedgerTransaction.sourceFileId`. No `@ForeignKey` between
 * them - see [LedgerTransaction.sourceFileId]'s doc comment.
 *
 * v5: `companion_profiles` (named, synced assistant identities, Kevin
 * 2026-08-02: two people share one Google account and two phones, and each
 * wants a different assistant). Replaces the bespoke single-identity
 * `syncCompanion`/`companion-<vehicleId>.json` sync path retired in the same
 * change - see [CompanionProfileEntity]'s doc comment and
 * `ai/ActiveCompanionProfile.kt` for the device-local active selection that
 * sits beside it.
 *
 * v6: `categories` + `category_rules` + `budget_targets` (ledger categorisation and
 * budget-versus-actual, `.scratch/legion-shape/issues/06-budget-versus-actual.md` and
 * `07-categorisation.md`), plus nullable `LedgerTransaction.category`/`categoryPending`. Replaces
 * the P&L (`ProfitAndLoss`/`buildProfitAndLoss`, deleted - ticket 06's resolution names the
 * deletion explicitly) with budget-versus-actual - see [com.kevin.legion.ledger.BudgetVsActual].
 *
 * v7: the workouts and meals aspects (`.scratch/legion-shape/issues/08-workouts-domain.md`
 * D20-D24 and `09-meals-domain.md` D25-D28), both charted in the same ticket 05 plan-versus-
 * actual vocabulary as ledger's budget-versus-actual. Six tables: `workout_plans` +
 * `workout_plan_items` (the AI-written plan, D20/D21), `workout_set_logs` (per-set voice logging,
 * D22), `bodyweight_logs` (its own entity, D23), `meal_targets` (daily calorie/macro target, D26),
 * `meal_logs` (voice- or photo-logged meals with LLM macro estimates, D25/D28). See each entity's
 * own doc comment for its shape and reasoning.
 *
 * v8: nullable `ledger_transactions.pendingLoggedAt` - voice-logged pending transactions. Kevin's
 * bank nets still-processing card activity into an "available" balance that never appears in any
 * BofA export (the CSV parser requires a printed running balance on every row, so a pending charge
 * cannot even be represented there), so he logs them by voice instead. See
 * [LedgerTransaction.pendingLoggedAt]'s doc comment for why this is a new column rather than a new
 * [IngestMethod] constant, and CLAUDE.md §4 rule 7 for the REPORTED-tier discipline it follows.
 *
 * v9: the sleep aspect (Kevin, 2026-08-07: "i want to be able to log sleep too"), modelled
 * directly on v7's workouts/meals shape. `sleep_targets` (copy-forward nightly target) +
 * `sleep_logs` (one row per logged night, REPORTED tier, no reconciliation gate). See [SleepLog]'s
 * doc comment for the wake-date keying convention and why this domain never has an anchor to
 * verify against, and [MIGRATION_8_9]'s doc comment for the schema itself.
 *
 * v10: the notes/lists/calendar domain phase 1 (`.scratch/notes-lists-calendar/`, tickets 01/04).
 * `item_lists` + `list_items` + `list_item_skips` - one general list model absorbing [CarTask]
 * (copied into a list named "Car") and [PlaceReminder] (copied into a list named "Reminders"),
 * per ticket 01's answer. **`car_tasks` and `place_reminders` are NOT dropped** in this migration
 * - both stay in place, unread by any new code, for one more version; see [MIGRATION_9_10]'s doc
 * comment for why. See [ListItem]'s doc comment for the recurrence columns (ticket 04) and
 * [com.kevin.legion.notes.Recurrence] for the pure occurrence generator that reads them.
 *
 * v11: notes/lists/calendar phase 2a (`.scratch/notes-lists-calendar/issues/03-*`/`12-*`) - local
 * alarms (`notes/AlarmScheduler.kt`) and fired-reminder state. Four columns on `list_items`:
 * `exact`/`exactDowngraded` (ticket 03's exact-alarm opt-in and its stored downgrade notice) and
 * `missedAt`/`missedDismissedAt` (ticket 12's STORED missed-one-off report). See [ListItem]'s doc
 * comment and [MIGRATION_10_11]'s for the schema itself. This version also finishes the
 * place-reminder absorption phase 1 left split-brained: `location/ReminderController` now reads
 * and writes `list_items.triggerPlaceLabel` instead of the legacy `place_reminders` table (which
 * still isn't dropped - see [MIGRATION_9_10]'s doc comment, unchanged reasoning).
 *
 * v12: no schema change - adds `Pets` to `categories` (Kevin 2026-08-07). See [CategorySeed]'s doc
 * comment for the fresh-install seeding bug this closes: a NEW install used to get ZERO categories,
 * because this class had no [RoomDatabase.Callback] and Room does not replay migrations against a
 * freshly-created schema. [getDatabase] below now seeds [CategorySeed.starter] on `onCreate`, and
 * [MIGRATION_11_12] carries the one row Kevin's existing v11 install is missing.
 *
 * v16: `goals` + `advisor_advice` (`.scratch/aspect-advisors/issues/02-goal-store.md`, grilled
 * 2026-08-13, built by ticket 13). `goals` is the cross-aspect long-term-goal store with a
 * copy-forward revision trail, no [com.kevin.legion.plan.TrustTier] column (an intention, not a
 * claim), and a `metricKey` TEXT column with no CHECK constraint so widening the metric list is a
 * code change, never a migration. `advisor_advice` is the persisted advisor-exchange log the
 * advisor contract decided in the same grilling session - gist/full-text/proposal split so the
 * digest only ever carries the cheap half. See [Goal] and [AdvisorAdvice]'s own doc comments for
 * the full reasoning, and [MIGRATION_15_16]'s for the schema itself.
 *
 * v17: no schema change - closes the fresh-install-then-upgraded seeding hole [MIGRATION_11_12]
 * left open (Kevin 2026-08-13). [MIGRATION_11_12]'s `alreadySeededByMigration56` set assumed
 * [MIGRATION_5_6] had already run; a database created fresh at v11 or later, before this class had
 * a [RoomDatabase.Callback] (added at v12), never ran it, so that assumption was false and
 * `categories` could be missing every starter row but one. [MIGRATION_16_17] makes NO history
 * assumption - it `INSERT OR IGNORE`s every row of [CategorySeed.starter] unconditionally - and
 * repairs any `ledger_transactions` rows that were guessed against the resulting broken list
 * (`categoryPending = 1` reset to uncategorised), but ONLY when it actually inserted something
 * missing. See [MIGRATION_16_17]'s own doc comment for the full story, and
 * [com.kevin.legion.ledger.CategoryAgent.guessBatch]'s refusal to guess against a category list
 * under two entries for the other half of the fix.
 *
 * v18: no schema change - repairs the `CHECKCARD` bug (Kevin 2026-08-13, found on his own real
 * production data). [com.kevin.legion.ledger.extractMerchantKey] used to derive a merchant key by
 * splitting at the first 3+-digit run, which on a Bank of America card line
 * (`CHECKCARD  0429 TMOBILE PREPD BELLEVUE WA`) is the transaction's own MMDD posting date, not a
 * store number - every card purchase collapsed to the bank's own word "CHECKCARD" instead of the
 * real merchant, and a `category_rules` row on that exact substring had silently confirmed 48
 * unrelated transactions (Walmart, Panda Express, T-Mobile among them) into "Subscriptions".
 * [MIGRATION_17_18] deletes any `category_rules` row whose substring is bank-generated boilerplate
 * (`CHECKCARD`/`CHKCARD`/`PURCHASE`) and resets exactly the `ledger_transactions` rows that rule
 * could have caused - see [MIGRATION_17_18]'s own doc comment for the full story,
 * [com.kevin.legion.ledger.isBankNoiseKey] for the corrected extraction, and
 * [com.kevin.legion.ledger.LedgerController.setCategory]'s refusal to ever write such a rule again
 * for the other half of the fix.
 *
 * v19: no schema change - closes the transfer/category defect Kevin asked for directly
 * (`.scratch/car-probe-transfers/`, 2026-08-13): [com.kevin.legion.ledger.analyzeTransfers] correctly
 * flagged a transfer row but was never wired into the merchant-categorisation pipeline at all, so a
 * row that moved Kevin's own money between his own accounts could still be guessed a category and
 * still acquire a [CategoryRule]. [com.kevin.legion.ledger.LedgerController.uncategorizedMerchants]
 * now gates its candidate pool through the SAME [com.kevin.legion.ledger.analyzeTransfers]
 * classification (never a second detector), and [com.kevin.legion.ledger.isBankNoiseKey] now refuses
 * a transfer-shaped rule the same way it already refused a bank-noise-prefixed one.
 * [MIGRATION_18_19] is the data-only third leg, undoing whatever damage already landed on disk - see
 * that migration's own doc comment for the full story and why it is deliberately UNSCOPED to any one
 * rule's category, unlike [MIGRATION_17_18]'s category-matched repair.
 *
 * v20: the fleet-maintenance map's schema (`.scratch/fleet-maintenance/map.md`, "THE MIGRATION",
 * tickets 06/07/11/14, all resolved 2026-08-15). Three additive columns plus one deliberate
 * exception to the additive-only rule:
 * - `maintenance_items.intervalSource TEXT NOT NULL DEFAULT 'SEEDED'` (ticket 06) - provenance flag
 *   for a seeded-vs-confirmed maintenance interval. See [MaintenanceItem.intervalSource]'s doc
 *   comment for why this is plain TEXT and not [IngestMethod].
 * - `maintenance_items.deleted INTEGER NOT NULL DEFAULT 0` (ticket 07) - the soft-delete tombstone,
 *   reusing the pattern `car_tasks`/`places` have carried since B19. See
 *   [MaintenanceItem.deleted]'s doc comment.
 * - `vehicles.engine TEXT NOT NULL DEFAULT ''` (ticket 14) - driver-entered engine, for a factory
 *   schedule that can differ by engine on the same year/make/model/trim. See [Vehicle.engine]'s doc
 *   comment.
 * - `service_records.cost REAL` -> `service_records.costCents INTEGER` (ticket 11) - **the map's
 *   one stated exception to CLAUDE.md §5's additive-only rule.** SQLite cannot retype a column in
 *   place, so this is a create-new-table/copy/drop/rename, not an `ALTER TABLE ... ADD COLUMN`.
 *   Justified ONLY because the column was verified provably empty first - ticket 11's own text:
 *   "`SELECT COUNT(*) FROM service_records WHERE cost IS NOT NULL` must be 0" against a copy of
 *   Kevin's real database, confirmed 0 of 2 rows before this migration was written. The copy
 *   therefore inserts NULL for every row, never a `cost * 100` conversion - there is nothing to
 *   convert, and a conversion expression would imply data that never existed. See
 *   [MIGRATION_19_20]'s own doc comment for the table rebuild and [ServiceRecord.costCents]'s for
 *   why this is a `Long` (CLAUDE.md §4 rule 3) while [BuildEntry.cost] deliberately stays `Double`.
 *
 * v21: `service_records.deleted INTEGER NOT NULL DEFAULT 0` - the soft-delete tombstone ticket 11
 * §2 asks for (`.scratch/fleet-maintenance/issues/11-*`). Purely additive, one column. **Cannot
 * propagate across devices** - `service_records` syncs `Mode.UNION` on `syncId`
 * (`sync/SyncEngine.kt:175`), and UNION never updates a row a device already has, so this delete is
 * LOCAL ONLY by construction, unlike `maintenance_items.deleted`'s LWW tombstone. See
 * [ServiceRecord.deleted] and [MIGRATION_20_21]'s own doc comments for the full reasoning.
 *
 * v22: `code_clear_events` - fleet's first WRITE to the car
 * (`.scratch/hands-and-senses/issues/01-clear-dtc.md`, resolved 2026-08-16). One additive
 * `CREATE TABLE`, nothing existing touched. Records a `clear_codes` transaction's outcome (D2's
 * five-state `ClearOutcome`) - see [CodeClearEvent]'s own doc comment for why this cannot be a
 * column on [CodeEvent], and [MIGRATION_21_22]'s for the schema itself. Verbatim from the
 * generated `app/schemas/com.kevin.legion.data.local.CarDatabase/22.json` after a kapt run, per
 * the additive-migration discipline this project has kept from v1.
 *
 * v23: `drives` - a real drive-boundary object (`.scratch/drive-ui/issues/05-trip-content.md` Q14,
 * `09-mpg-scale-bug.md`'s "bigger finding"), and the fix for the link-loss defect that finding
 * exposed: [com.kevin.legion.vehicle.TelemetryRecorder.run]'s single `continue` guard used to treat
 * a dropped Bluetooth link identically to a busy voice turn, so a lost link never finalised the
 * drive in progress - `engineWasOn` stayed `true` forever and the next reconnect silently resumed
 * the SAME drive. One additive `CREATE TABLE`, nothing existing touched. See [Drive]'s own doc
 * comment for the full shape and [MIGRATION_22_23]'s for the schema itself, verbatim from the
 * generated `app/schemas/com.kevin.legion.data.local.CarDatabase/23.json` after a kapt run.
 *
 * v24: closes the `categoryPending` default drift
 * (`.scratch/ledger-drive-ingestion/issues/13-categorypending-default-drift.md`).
 * [LedgerTransaction.categoryPending] never carried `@ColumnInfo(defaultValue = ...)`, even though
 * [MIGRATION_5_6] (v5->v6, the migration that added the column) has always written it with
 * `INTEGER NOT NULL DEFAULT 0`. Every migrated device has therefore had `DEFAULT 0` on disk since
 * v6; a fresh install never did. **No schema change on a migrated device** - same "the bump exists
 * only because Room requires one to run anything at all" shape [MIGRATION_16_17]'s and
 * [MIGRATION_17_18]'s own doc comments already describe for a pure identity-hash bump - see
 * [MIGRATION_23_24]'s own doc comment for why its body is empty.
 *
 * v25: one `CREATE INDEX` on `obd_samples(vehicleId, pid, timestamp)` - Kevin's device, 2026-08-16.
 * `obd_samples` had 18,694 rows and zero indexes; every telemetry query was `SCAN obd_samples` plus
 * a temp b-tree sort, and the FAULTS drilldown ran that shape twice per visible code event. Purely
 * additive, one `CREATE INDEX IF NOT EXISTS`, nothing existing touched. See [OdbSample]'s own doc
 * comment for which of [OdbSampleDao]'s queries this serves, which it does not, and why a second
 * index was judged not worth the extra write cost on the app's single largest table.
 *
 * v26: `music_play_history` (LEGION's OWN observed-listening log, `browse_my_music`'s
 * `legion_history` source, `.scratch/drive-test-2026-08-18/issues/05-reading-kevins-spotify-library.md`).
 * One additive `CREATE TABLE`, nothing existing touched. See [MusicPlayHistoryEntry]'s own doc
 * comment for why this is a genuinely different table from Spotify's own recently-played read
 * ([com.kevin.legion.media.RecentlyPlayedTrack]) and not the retired music-taste ledger back
 * under a new name.
 *
 * v29: (v27 `memory_audit`, v28 `proactive_settings`/`proactive_raises`, both undocumented here -
 * see `data/local/Migrations.kt` for their real shape) `sitrep_modules` + `sitrep_schedule`
 * (ticket 22).
 *
 * v30: `conversation_audit` (ticket 23, hands-and-senses map, "an audit trail of every
 * conversation and every tool call"). One additive `CREATE TABLE`, nothing existing touched. See
 * [ConversationAudit]'s own doc comment for why this is a NEW table rather than an extension of
 * [MemoryAudit] (v27) despite both being flat trimmed audit logs.
 *
 * v31: `wellbeing_digest_schedule` (goal-plans ticket 05, "the Wellbeing switch finally gets
 * content" - `.scratch/goal-plans/issues/05-wellbeing-digest.md`). One additive `CREATE TABLE`,
 * nothing existing touched. See [WellbeingDigestSchedule]'s own doc comment for why this is a
 * sibling of [SitrepSchedule] rather than a widening of it.
 *
 * v32: two additive nullable columns (goal-plans ticket 08, "the checklist prescribes a day, and a
 * ticked day logs itself" - Kevin: "3 sets x 10 rep kettlebell swing etc. and i check it off. end
 * of day it checks what i ticked and logs it"). `workout_plan_items.repsPerSet` lets a NEW plan
 * carry reps without fabricating them for old rows - see [WorkoutPlanItem.repsPerSet]'s doc.
 * `list_items.loggedAt` is the end-of-day auto-log sweep's idempotence anchor - see
 * [ListItem.loggedAt]'s doc. Both ride in the same migration since both are bare `ALTER TABLE ...
 * ADD COLUMN` statements with no data touched, matching this file's existing practice of batching
 * unrelated additive columns into one version bump when they land in the same ticket.
 *
 * v33 (goal-plans ticket 09, "a ticked workout is one act, not two rows"): nullable
 * `workout_set_logs.sourceListItemId` (see [WorkoutSetLog.sourceListItemId]) - the swept log's
 * link back to the plan item that produced it, which is what makes an untick able to find and
 * delete that log ([com.kevin.legion.notes.NotesController.untick]) and closes the phantom-set
 * defect the ticket found.
 * Bare `ALTER TABLE ... ADD COLUMN`, additive only.
 *
 * v34: **the aspect engine core** (`.scratch/aspect-engine/issues/16-build-engine-core.md`, tickets
 * 03/04/11's locked spec). Five additive `CREATE TABLE`s, nothing existing touched:
 * `aspects` ([Aspect] - the widget-pager page list, archive-not-delete lifecycle),
 * `record_types` ([RecordType] - one row per user- or plugin-defined record shape),
 * `field_defs` ([FieldDef] - the v1 13-type field vocabulary, config JSON per type),
 * `records` ([EngineRecord] - the single generic record table every aspect's data will migrate
 * into, promoted columns plus JSON payload, trash tombstone), and
 * `widget_instances` ([WidgetInstance] - per-device pager layout, deliberately unsynced).
 * [com.kevin.legion.engine.RecordStore] is the only thing that writes `records` - see its own doc
 * comment for reference enforcement, delete-policy cascade/nullify, and computed-field
 * materialization. **Nothing existing migrates onto this schema yet** - fleet/ledger/pantry stay on
 * their own 48 typed entities until the migration-wave tickets (ticket 14's order) cut over one
 * aspect at a time; this version only builds the target the wave lands into.
 *
 * v35 (aspect-engine ticket 18, "build the widget pager and generated screens"): four columns on
 * `widget_instances` - `gridRow`/`gridCol`/`rowSpan`/`colSpan`, the [com.kevin.legion.ui.grid.GridItem]
 * geometry the production `DeckGrid` pager persists through. See [WidgetInstance]'s own v35 doc
 * comment and [MIGRATION_34_35] for the schema itself. No other table changes.
 *
 * v36 (aspect-engine ticket 19, "build the Dates aspect"): `muted_reminders` - a permanent,
 * per-record reminder mute, deliberately its own tiny table rather than a `records` column (see
 * [MutedReminder]'s own doc comment for why). See [MIGRATION_35_36] for the schema itself. No
 * other table changes.
 *
 * v37 (senior review of aspect-engine ticket 20, MUST-FIX 1): `records.guid` - the cross-device
 * identity column [EngineRecord]'s own v37 doc comment explains at length. One `ALTER TABLE`, one
 * backfill `UPDATE` (every pre-existing row gets a real, distinct UUID, never left `''`), one
 * unique index. See [MIGRATION_36_37] for the schema itself. No other table changes.
 *
 * v38 (backend-erp Phase 4, aspect 4 of 5 - Notes+Dates cut over together, carrying the merge,
 * `.scratch/backend-erp/issues/05-migration-path.md`): `events_replica` + `event_skips_replica` -
 * the Room replica of `public.events`/`public.event_skips`
 * (`supabase/migrations/20260825000400_aspect_dates_notes_merged.sql`). See [Event]'s own
 * doc comment for the field mapping and for why [Event.id] is a local surrogate rather than
 * the server's own uuid. Two additive `CREATE TABLE`s, nothing existing touched. See
 * [MIGRATION_37_38] for the schema itself.
 *
 * v45 (engine retirement step 4, `.scratch/backend-erp/issues/15-engine-retirement-sequence.md`,
 * "RULED 2026-08-27: notes gets ONE local table"): `events_replica`/`event_skips_replica` RENAMED
 * to `events`/`event_skips` - the table is now the single local store for both the configured and
 * unconfigured Notes+Dates path, so a name promising "replica" (implying a cache of a store that
 * lives elsewhere) would be a misdescription on an unconfigured install where it is the only store
 * there is. See [Event]'s own doc comment and [MIGRATION_44_45] for the rename mechanics (a real
 * `ALTER TABLE ... RENAME TO`, not a drop/recreate, so no data is lost by the migration itself).
 */
@Database(
    entities = [
        ServiceRecord::class, MemoryEntry::class, TaggedPlace::class, Vehicle::class,
        MaintenanceItem::class, PlaceReminder::class, CarTask::class, BuildEntry::class,
        VehicleSpec::class,
        OdbSample::class, CodeEvent::class, OilAnalysis::class,
        ChassisQuirk::class, ForesightNote::class, MonthlyRecap::class, DailyDriveLog::class,
        YearlyWrapped::class,
        DriveReassignment::class,
        EpisodicTurn::class, CompanionMemory::class,
        LedgerTransaction::class,
        PantryReceipt::class, PantryLineItem::class,
        IngestedFile::class,
        CompanionProfileEntity::class,
        Category::class, CategoryRule::class, BudgetTarget::class,
        WorkoutPlan::class, WorkoutPlanItem::class, WorkoutSetLog::class, BodyweightLog::class,
        MealTarget::class, MealLog::class,
        SleepTarget::class, SleepLog::class,
        ItemList::class, ListItem::class, ListItemSkip::class,
        GroceryItem::class, GroceryStaple::class,
        VehicleCapability::class,
        Goal::class, AdvisorAdvice::class,
        CodeClearEvent::class,
        Drive::class,
        MusicPlayHistoryEntry::class, MemoryAudit::class,
        ProactiveSetting::class, ProactiveRaiseRow::class,
        SitrepModuleSetting::class, SitrepSchedule::class,
        ConversationAudit::class,
        WellbeingDigestSchedule::class,
        Aspect::class, RecordType::class, FieldDef::class, EngineRecord::class, WidgetInstance::class,
        MutedReminder::class,
        Event::class, EventSkip::class,
        VehicleReplica::class, ServiceHistoryReplica::class,
        VehicleSidecar::class,
        VoiceNote::class,
    ],
    version = 56,
    exportSchema = true,
)
abstract class CarDatabase : RoomDatabase() {
    abstract fun serviceRecordDao(): ServiceRecordDao
    abstract fun memoryDao(): MemoryDao
    abstract fun placeDao(): PlaceDao
    abstract fun vehicleDao(): VehicleDao
    abstract fun maintenanceItemDao(): MaintenanceItemDao
    abstract fun placeReminderDao(): PlaceReminderDao
    abstract fun carTaskDao(): CarTaskDao
    abstract fun buildEntryDao(): BuildEntryDao
    abstract fun vehicleSpecDao(): VehicleSpecDao
    abstract fun odbSampleDao(): OdbSampleDao
    abstract fun codeEventDao(): CodeEventDao
    abstract fun groceryItemDao(): GroceryItemDao
    abstract fun groceryStapleDao(): GroceryStapleDao
    abstract fun vehicleCapabilityDao(): VehicleCapabilityDao
    abstract fun oilAnalysisDao(): OilAnalysisDao
    abstract fun chassisQuirkDao(): ChassisQuirkDao
    abstract fun foresightNoteDao(): ForesightNoteDao
    abstract fun monthlyRecapDao(): MonthlyRecapDao
    abstract fun dailyDriveLogDao(): DailyDriveLogDao
    abstract fun yearlyWrappedDao(): YearlyWrappedDao
    abstract fun driveReassignmentDao(): DriveReassignmentDao
    abstract fun episodicTurnDao(): EpisodicTurnDao
    abstract fun companionMemoryDao(): CompanionMemoryDao
    abstract fun ledgerTransactionDao(): LedgerTransactionDao
    abstract fun pantryReceiptDao(): PantryReceiptDao
    abstract fun pantryLineItemDao(): PantryLineItemDao
    abstract fun ingestedFileDao(): IngestedFileDao
    abstract fun companionProfileDao(): CompanionProfileDao
    abstract fun categoryDao(): CategoryDao
    abstract fun categoryRuleDao(): CategoryRuleDao
    abstract fun budgetTargetDao(): BudgetTargetDao
    abstract fun workoutPlanDao(): WorkoutPlanDao
    abstract fun workoutPlanItemDao(): WorkoutPlanItemDao
    abstract fun workoutSetLogDao(): WorkoutSetLogDao
    abstract fun bodyweightLogDao(): BodyweightLogDao
    abstract fun mealTargetDao(): MealTargetDao
    abstract fun mealLogDao(): MealLogDao
    abstract fun sleepTargetDao(): SleepTargetDao
    abstract fun sleepLogDao(): SleepLogDao
    abstract fun itemListDao(): ItemListDao
    abstract fun listItemDao(): ListItemDao
    abstract fun listItemSkipDao(): ListItemSkipDao
    abstract fun goalDao(): GoalDao
    abstract fun advisorAdviceDao(): AdvisorAdviceDao
    abstract fun codeClearEventDao(): CodeClearEventDao
    abstract fun driveDao(): DriveDao
    abstract fun musicPlayHistoryDao(): MusicPlayHistoryDao

    /** The memory audit trail (2026-08-20) - a record of what happened, never an input. */
    abstract fun memoryAuditDao(): MemoryAuditDao
    abstract fun proactiveSettingDao(): ProactiveSettingDao
    abstract fun proactiveRaiseDao(): ProactiveRaiseDao

    /** The sitrep module registry (ticket 22) - per-module on/off. */
    abstract fun sitrepModuleSettingDao(): SitrepModuleSettingDao

    /** The sitrep's schedule time and newsletter sender list - one row, see [SitrepSchedule]. */
    abstract fun sitrepScheduleDao(): SitrepScheduleDao

    /** The conversation-and-tool-call audit trail (ticket 23) - see [ConversationAudit]. */
    abstract fun conversationAuditDao(): ConversationAuditDao

    /** The wellbeing digest's schedule time - one row, see [WellbeingDigestSchedule]. */
    abstract fun wellbeingDigestScheduleDao(): WellbeingDigestScheduleDao

    /** The aspect engine core (v34, ticket 16) - see [com.kevin.legion.engine.RecordStore] for the
     * write door these five DAOs sit behind. */
    abstract fun aspectDao(): AspectDao
    abstract fun recordTypeDao(): RecordTypeDao
    abstract fun fieldDefDao(): FieldDefDao
    abstract fun engineRecordDao(): EngineRecordDao
    abstract fun widgetInstanceDao(): WidgetInstanceDao

    /** Per-record reminder mutes (v35, aspect-engine ticket 19, the Dates aspect build) - see
     * [MutedReminder]'s own doc comment for why this stays outside [com.kevin.legion.engine.RecordStore]'s door. */
    abstract fun mutedReminderDao(): MutedReminderDao

    /** The Notes+Dates merge's local store (v38, renamed from `events_replica`/`EventReplicaDao`
     * at v45 - engine retirement step 4, `.scratch/backend-erp/issues/15-engine-retirement-sequence.md`)
     * - see [Event]'s own doc comment. Populated by [com.kevin.legion.backend.EventsReconcile] and
     * the CONFIGURED write path in `notes/NotesController.kt`/`engine/dates/DatesAgenda.kt` when a
     * Supabase project is configured, and directly by the UNCONFIGURED write path in
     * `notes/NotesController.kt` (via [com.kevin.legion.engine.migration.EngineNotesRetirementCopy]'s
     * one-time reconcile, then ordinary CRUD) when it is not - one table serves both paths now,
     * same shape as [placeDao]/[pantryReceiptDao]'s own engine-retirement repoints. */
    abstract fun eventDao(): EventDao
    abstract fun eventSkipDao(): EventSkipDao

    /** The fleet aspect's Room replicas (v42, backend-erp fleet wave 2) - see [VehicleReplica]'s
     * own doc comment for why this pair exists, and why (unlike [eventDao]) neither carries
     * an id-preserving upsert. Populated only by [com.kevin.legion.backend.FleetReconcile]; no
     * CONFIGURED read path wired to either yet (repointing reads is a later wave). */
    abstract fun vehicleReplicaDao(): VehicleReplicaDao
    abstract fun serviceHistoryReplicaDao(): ServiceHistoryReplicaDao

    /** The vehicle co-owned-row sidecar (v50, backend-erp ticket 26) - see [VehicleSidecar]'s own
     * class doc for what it carries and why it exists alongside [vehicleReplicaDao]. */
    abstract fun vehicleSidecarDao(): VehicleSidecarDao

    /** The voice-notes local store (v56, `.scratch/voice-notes/issues/02-the-store.md`) - see
     * [VoiceNote]'s own doc comment. */
    abstract fun voiceNoteDao(): VoiceNoteDao

    companion object {
        @Volatile
        private var INSTANCE: CarDatabase? = null

        /**
         * The single monitor [getDatabase] and [closeAndClear] synchronize on, and the one
         * [withDatabaseLock] exposes to callers outside this file. A named field rather than
         * `synchronized(this)`/`synchronized(this@Companion)` so the SAME object is provably
         * held on both sides of a cross-file critical section - see [withDatabaseLock]'s doc
         * comment for why that matters (Ravi's review, 2026-08-13 BLOCKING finding 2).
         */
        private val LOCK = Any()

        /** The physical file name Room opens under [Context.getDatabasePath] - shared with
         * [com.kevin.legion.sync.DatabaseSnapshot], which needs the real on-disk file to back
         * up and restore, not just a DAO handle. Kept as a single named constant so the two
         * files cannot drift apart. */
        const val DATABASE_FILE_NAME = "legion_database"

        /** Mirrors `@Database(version = ...)` above, as a compile-time constant a Composable
         * can read without touching Room (opening `openHelper.readableDatabase` from inside
         * composition is the kind of I/O-on-the-UI-thread footgun this avoids). Used by
         * `ui/DriveSyncScreen.kt` to decide, per [com.kevin.legion.sync.DatabaseSnapshot.Generation],
         * whether a restore is even offered. **Must be bumped by hand alongside the annotation
         * above** - there is no reflection trick that reads an annotation value at Kotlin
         * compile time, so this is deliberately a second place version 15 is written, not a
         * derived one. [com.kevin.legion.sync.DatabaseSnapshot] itself never uses this constant
         * (it reads the live `PRAGMA user_version` instead, which can't drift), so a
         * forgotten bump here only ever makes the UI's restore button MORE conservative
         * (comparing against a stale, lower number), never less. */
        const val SCHEMA_VERSION = 56
        // 2026-08-28: bumped 47 -> 49, and this one was NOT a same-edit bump - it was a REPAIR.
        // Versions 48 and 49 (tickets 17 and 18) each moved `@Database(version=)` and left this
        // constant behind, and the doc comment above was wrong about the consequence. It says a
        // forgotten bump "only ever makes the UI's restore button MORE conservative... never
        // less", which is true and sounds harmless. It is not: `DriveBackupResolver.generationRows`
        // compares each backup's REAL recorded schema version against this constant, so at 47 it
        // read every v49 backup as "from a newer app version than this one" and DISABLED restore
        // on it. The running app was v49. The two newest Drive generations - the only ones that
        // actually matched the live schema - were the exact ones the UI refused, and that was
        // found on 2026-08-28 during the first real restore drill, with the phone mid-rollback.
        // `CarDatabaseSchemaVersionTest` now fails the build on the next drift, because "must be
        // bumped by hand" is a hope and this is the second time it was not.
        // 2026-08-27: bumped to 47 alongside `@Database(version=)` in the same edit again
        // (`service_records.kind`/`.updatedAt`, `service_records.mileage`/`.date` widened to
        // nullable - engine retirement step 3, ticket 16).
        // 2026-08-27: bumped to 46 alongside `@Database(version=)` in the same edit again
        // (`pantry_receipts.photoObjectPath`, ticket 09).
        // 2026-08-21: found at 26 while `@Database(version=)` was already 27, so the v27 bump was
        // forgotten - exactly the drift this constant's doc predicts and calls benign. Corrected to
        // 28 with the proactive-mode tables. The comment above is right that the drift only makes
        // the restore button more conservative, but "harmless when wrong" is not the same as
        // "checked", and nothing checks it.
        // 2026-08-21: bumped to 29 alongside `@Database(version=)` in the SAME edit this time
        // (`sitrep_modules`/`sitrep_schedule`, ticket 22) - the two constants staying in sync is
        // the whole point of this comment existing at all.
        // 2026-08-21: bumped to 30 alongside `@Database(version=)` in the same edit again
        // (`conversation_audit`, ticket 23).
        // 2026-08-22: bumped to 31 alongside `@Database(version=)` in the same edit again
        // (`wellbeing_digest_schedule`, goal-plans ticket 05).
        // 2026-08-22: bumped to 32 alongside `@Database(version=)` in the same edit again
        // (`workout_plan_items.repsPerSet` + `list_items.loggedAt`, goal-plans ticket 08).
        // 2026-08-22: bumped to 33 alongside `@Database(version=)` in the same edit again
        // (`workout_set_logs.sourceListItemId`, goal-plans ticket 09).
        // 2026-08-23: bumped to 34 alongside `@Database(version=)` in the same edit again
        // (the aspect engine core - `aspects`/`record_types`/`field_defs`/`records`/
        // `widget_instances`, ticket 16).
        // 2026-08-23: bumped to 35 alongside `@Database(version=)` in the same edit again
        // (`widget_instances.gridRow`/`gridCol`/`rowSpan`/`colSpan`, ticket 18).
        // 2026-08-23: bumped to 36 alongside `@Database(version=)` in the same edit again
        // (`muted_reminders`, aspect-engine ticket 19 - the Dates aspect build).
        // 2026-08-26: bumped to 38 alongside `@Database(version=)` in the same edit again
        // (`events_replica`/`event_skips_replica`, backend-erp Phase 4 - Notes+Dates merge). Note
        // the jump from 36 straight to 38 in THIS constant's own comment trail - v37
        // (`records.guid`) never got an entry here either, the exact drift this constant's doc
        // comment predicts and calls harmless; `@Database(version=)` itself is the number that
        // actually governs Room and it went 36 -> 37 -> 38 in order.
        // 2026-08-26: bumped to 40 alongside `@Database(version=)` in the same edit again
        // (`events_replica.startsAt` widened to nullable, backend-erp ticket 07's "RULED
        // 2026-08-26: option 1" - a genuinely dateless Notes `Item` gets a null start, never a
        // guessed one). Note the jump from 38 straight to 40 here too - v39
        // (`pantry_receipts.provenance`/`.unaccountedCents`, ticket 08) never got an entry in
        // THIS constant's own comment trail either, same benign drift the doc above predicts.
        // 2026-08-27: bumped to 45 alongside `@Database(version=)` in the same edit again
        // (`events_replica`/`event_skips_replica` renamed to `events`/`event_skips`, engine
        // retirement step 4 - ticket 15's "RULED 2026-08-27: notes gets ONE local table"). Note
        // the jump from 40 straight to 45 here too - v41/42/43/44 never got an entry in THIS
        // constant's own comment trail either, same benign drift the doc above predicts;
        // `@Database(version=)` itself went 40 -> 41 -> 42 -> 43 -> 44 -> 45 in order.
        // 2026-08-28: bumped to 48 alongside `@Database(version=)` in the same edit again
        // (`events.structuredMeta`, backend-erp ticket 17's "RULED 2026-08-28" - Dates repoints
        // onto `events` and `CalendarImportController` needs somewhere to put the Google
        // `LEGION::v1` block now that it no longer goes through the engine at all). Note the jump
        // from 45 straight to 48 here too - v46 (fleet's `service_records.kind`/`.updatedAt`,
        // ticket 16) and v47 never got an entry in THIS constant's own comment trail either, same
        // benign drift the doc above predicts; `@Database(version=)` itself went 45 -> 46 -> 47 ->
        // 48 in order.
        // 2026-08-28: bumped to 49 alongside `@Database(version=)` in the same edit again
        // (`events.guid`, coordinator follow-up on ticket 17 the same day - EventsReconcile's
        // Dates branch was repointed onto `events` and needed a stable, immutable identity column
        // that survives EventsReconcile's own wholesale refill, which `serverId` does not).

        fun getDatabase(context: Context): CarDatabase {
            return INSTANCE ?: synchronized(LOCK) {
                // Re-check inside the lock (standard double-checked-locking shape, unchanged
                // from before this edit) - but ALSO the reason a concurrent caller landing
                // here while DatabaseSnapshot.restore holds LOCK (via [withDatabaseLock])
                // blocks instead of racing it: see [withDatabaseLock]'s doc comment.
                INSTANCE?.let { return@synchronized it }
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    CarDatabase::class.java,
                    DATABASE_FILE_NAME,
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                        MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
                        MIGRATION_11_12, MIGRATION_12_13,
                        MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
                        MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21,
                        MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25,
                        MIGRATION_25_26,
                        MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30,
                        MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34,
                        MIGRATION_34_35, MIGRATION_35_36, MIGRATION_36_37, MIGRATION_37_38,
                        MIGRATION_38_39, MIGRATION_39_40, MIGRATION_40_41, MIGRATION_41_42,
                        MIGRATION_42_43, MIGRATION_43_44, MIGRATION_44_45, MIGRATION_45_46,
                        MIGRATION_46_47, MIGRATION_47_48, MIGRATION_48_49, MIGRATION_49_50,
                        MIGRATION_50_51, MIGRATION_51_52, MIGRATION_52_53, MIGRATION_53_54,
                        MIGRATION_54_55, MIGRATION_55_56,
                    )
                    // NO destructive downgrade fallback. This deliberately has no
                    // `.fallbackToDestructiveMigrationOnDowngrade(...)`, removed 2026-08-12 after it
                    // destroyed a live database.
                    //
                    // What happened: an older APK was installed over this schema (v15). Room saw a
                    // downgrade and, because that call was present with `dropAllTables = true`,
                    // dropped every one of the 42 tables without a prompt, a log line, or a crash.
                    // Everything in the 23 tables Drive sync does not cover was gone permanently.
                    //
                    // Without it, opening a newer database with an older build throws instead. That
                    // is the correct failure: loud, immediate, and non-destructive - the data is
                    // still on disk and a correct build reads it back. A silent wipe is the one
                    // outcome that cannot be undone.
                    //
                    // It bought nothing in return. Clone-and-run (CLAUDE.md §2) is a FRESH install,
                    // which has no older database to downgrade from, so a stranger cloning the repo
                    // never reaches this path. The only people who ever hit it are the two of us
                    // sideloading builds - precisely the case where losing the database is worst
                    // and crashing is cheapest.
                    //
                    // Fresh-install seeding fix (Kevin 2026-08-07, CLAUDE.md §2 clone-and-run) - a
                    // brand-new database is built straight from the @Entity set, never through
                    // MIGRATION_5_6, so nothing seeded `categories` at all until this callback
                    // existed. See CategorySeed's doc comment. onCreate hands back the SAME
                    // SupportSQLiteDatabase a Migration would get, so this is a raw execSQL insert,
                    // not a DAO call - no CarDatabase instance exists yet to hand a DAO from.
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            super.onCreate(db)
                            for ((name, isFood) in CategorySeed.starter) {
                                db.execSQL(
                                    "INSERT OR IGNORE INTO `categories` (`name`, `isFoodCategory`) VALUES (?, ?)",
                                    arrayOf<Any>(name, if (isFood) 1 else 0),
                                )
                            }
                        }
                    })
                    .build()
                INSTANCE = instance
                instance
            }
        }

        /**
         * Closes the live Room connection pool and clears the singleton, so the NEXT
         * [getDatabase] call opens a brand-new connection against whatever file is on disk
         * at that point rather than reusing stale connections/caches against a file that has
         * since been replaced out from under them.
         *
         * The one caller is [com.kevin.legion.sync.DatabaseSnapshot.restore] - a restore
         * physically overwrites the on-disk `legion_database`(-wal/-shm) files, and Room's
         * connection pool, prepared-statement cache, and in-memory invalidation tracker are
         * all keyed to the file it originally opened. Leaving them open across that swap is
         * exactly the "half-applied restore" the ticket brief calls out: reads would keep
         * serving pre-restore data (or worse, a WAL file from the OLD generation could
         * checkpoint itself onto the newly-restored file on next write). Closing first, then
         * replacing the files, then letting the next [getDatabase] call open fresh is the
         * ordering that keeps the restore atomic from the app's own point of view - see
         * [com.kevin.legion.sync.DatabaseSnapshot.restore]'s own doc comment for the full
         * sequence including why a full app restart is still required afterward.
         *
         * Test code has its own reflection-based equivalent
         * ([com.kevin.legion.testutil.RoomTestReset]) because Robolectric's shadow layer resets
         * per test method regardless of what production code does; this is the real one used
         * at runtime.
         *
         * Synchronizes on [LOCK] - the same monitor [getDatabase] uses - so a concurrent
         * [getDatabase] call cannot observe [INSTANCE] mid-null-out. On its own this does NOT
         * close the full race [withDatabaseLock] exists for (the monitor is released the
         * instant this function returns, before the caller has actually replaced the on-disk
         * files) - see [withDatabaseLock]'s doc comment; [com.kevin.legion.sync.DatabaseSnapshot.restore]
         * must hold [withDatabaseLock] across this call AND the file swap that follows it.
         */
        fun closeAndClear() {
            synchronized(LOCK) {
                INSTANCE?.let { runCatching { it.close() } }
                INSTANCE = null
            }
        }

        /**
         * Holds the SAME monitor [getDatabase] synchronizes on for the duration of [block] -
         * exposed so [com.kevin.legion.sync.DatabaseSnapshot.restore] can keep it held across
         * its ENTIRE close-then-replace-file window, not just the instant [closeAndClear] nulls
         * [INSTANCE].
         *
         * **Ravi's review, 2026-08-13 (BLOCKING finding 2).** Without this, the gap between
         * [closeAndClear] returning and the restore's file rename actually landing was
         * unguarded: `TelemetryRecorder` calls [getDatabase] roughly every 30 seconds,
         * independently of anything the driver is doing, and a call landing in that gap would
         * see [INSTANCE] `== null` (already nulled), take [getDatabase]'s normal
         * `synchronized(LOCK)` path, and build a BRAND NEW Room database against whatever is -
         * or, mid-restore, is NOT - at [DATABASE_FILE_NAME] at that exact instant. Room cannot
         * tell "this path is momentarily empty because a restore is mid-flight" from "this is a
         * genuine fresh install" - it is the identical code path the fresh-install seeding
         * callback above exists for. The restored file's subsequent rename into place would
         * then land UNDER that concurrently-open connection, orphaning its writes or leaving
         * two live instances pointed at two different files.
         *
         * Restore is manual, driver-initiated, and takes at most a few seconds - the correct
         * fix is for a concurrent [getDatabase] caller to BLOCK for that window, not to degrade
         * or retry. Reentrant by construction (`synchronized` on the same JVM monitor from the
         * same thread does not deadlock), so [restore] can safely call [closeAndClear] from
         * inside its own [withDatabaseLock] block.
         *
         * **This has NOT been proven under real concurrency, and that limit is named here on
         * purpose (senior-dev review, 2026-08-12).** The unit suite is single-threaded, so no
         * test spins up a second thread, has it call [getDatabase] while another holds this
         * lock, and asserts it actually blocks. What the review DID establish by tracing: the
         * `() -> T` signature is deliberately NOT `suspend`, which structurally forbids a
         * suspension point inside the critical section - that is what would otherwise let a
         * coroutine resume on a different thread mid-block and turn `synchronized`'s
         * per-thread reentrancy into a lie. The guarantee being leaned on is a plain
         * `java.lang.Object` monitor, not logic invented here, which is why shipping without
         * that test was judged acceptable for THIS caller.
         *
         * **Before reusing this lock for anything other than restore, write that two-thread
         * test first.** A second caller with different timing - something long-running, or
         * something reachable from the main thread on a hot path - does not inherit the "manual
         * and rare" argument that makes the untested gap tolerable here.
         */
        fun <T> withDatabaseLock(block: () -> T): T = synchronized(LOCK) { block() }
    }
}
