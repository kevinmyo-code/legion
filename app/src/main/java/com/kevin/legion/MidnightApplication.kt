package com.kevin.legion

import android.app.Application
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.ai.CompanionProfileStore
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.data.MidnightImport
import com.kevin.legion.engine.mirror.MirrorFolderPreferences
import com.kevin.legion.engine.mirror.MirrorLifecycleBinder
import com.kevin.legion.ledger.LedgerAccountMappingPreferences
import com.kevin.legion.ledger.LedgerFolderPreferences
import com.kevin.legion.ledger.LedgerNominatedAccountPreferences
import com.kevin.legion.service.ProactivePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application subclass registered in the manifest via android:name=".MidnightApplication".
 *
 * Firebase (Crashlytics) is NOT wired up yet - no `google-services.json`, no
 * Firebase dependency (see README.md). [MidnightEvents] logs via `Log.d` until a
 * fresh Firebase project exists for this app. GenerationMeter (billing-tier image
 * quota tracking) was retired with the rest of billing/ in the 2026-07-31 pivot.
 */
class MidnightApplication : Application() {
    /**
     * Process-lifetime scope for start-up work that touches disk or Room and so
     * must not block `onCreate`. Owned by the Application because that is what
     * the work's lifetime actually is; nothing cancels it because nothing should.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Process-wide caches that are seeded from disk exactly once.
        //
        // These used to be seeded in AriaForegroundService.onCreate, which was
        // fine while that service started on its own. Ticket 07 made the
        // assistant an explicit user toggle that is OFF by default, so on a
        // normal launch that service never runs - and every one of these
        // silently stayed empty while its backing value sat on disk. Verified
        // on the A17K 2026-08-02: the ledger spend gate reported "no Gemini
        // key" for a key that was saved and present, and the connected
        // statements folder was forgotten on every process start.
        //
        // Application.onCreate is the correct home precisely because it does
        // not depend on any feature being switched on. Each init is a cheap
        // SharedPreferences read. AriaForegroundService still calls the first
        // two, which is harmless - they are idempotent - and is left alone so
        // the assistant path does not depend on this ordering.
        GeminiKeyProvider.init(this)
        ProactivePreferences.init(this)
        LedgerFolderPreferences.init(this)
        // Per-account subfolder mapping (checking/, credit/, ...) - same L12
        // reasoning as the three above, added for the per-account-subfolder
        // + CSV ingestion ticket.
        LedgerAccountMappingPreferences.init(this)
        // HOME's CRED tile balance line (2026-08-18) - same L12 reasoning as the three caches
        // above: must be live before the first Today-tab composition, not just after AriaForegroundService starts.
        LedgerNominatedAccountPreferences.init(this)
        // The mirror/sync folder connection (aspect-engine ticket 20) - same L12 reasoning as the
        // caches above: MirrorLifecycleBinder's foreground/background triggers below read this
        // StateFlow on every app-start/stop, so it must be live before the first one can fire, not
        // seeded lazily by whoever happens to open MirrorSyncActivity first.
        MirrorFolderPreferences.init(this)

        // Named companion profiles (multi-companion, 2026-08-02): seed one
        // profile from a pre-existing single identity if this install predates
        // the feature, then materialise whichever profile is active on THIS
        // device into CompanionProfile's flat keys so every reader of it
        // (AriaBrain, LiveSessionController, GeminiLiveSession, ...) sees the
        // right identity from the very first Live session, not just after the
        // next sync pass or profile switch. Same L12 reasoning as the three
        // caches above: this must run unconditionally on process start, not
        // from a service that might not be running. Both steps touch Room, so
        // they run on a process-scoped IO coroutine rather than blocking
        // onCreate; a session that starts before this completes still reads
        // CompanionProfile's PREVIOUS on-disk values (unchanged from last run),
        // never a blank or half-written state.
        // appScope, not a scope built inline here: constructing a CoroutineScope
        // inside a function body is the anti-pattern the repo's vendored
        // kotlin-coroutines-structured-concurrency skill names, and we removed
        // exactly that from SyncEngine's foreground trigger two commits ago.
        // Application IS the process-lifetime owner, so the scope belongs to it.
        //
        // GATED OFF UNDER ROBOLECTRIC (see isRunningUnderRobolectric's doc comment) - all three
        // blocks below touch Room, appScope has no lifecycle hook a JUnit @Before/@After can
        // cancel, and MidnightApplication is this app's declared manifest Application, so every
        // Robolectric test in this module runs onCreate() and would otherwise race real writes
        // (Kevin's own imported fleet, via MidnightImport) into whatever CarDatabase instance a
        // concurrently-running test happens to be asserting against. Found 2026-08-07: adding the
        // third block (AlarmScheduler.rescheduleAll) shifted IO-dispatcher timing enough to flip a
        // previously-losing race into a winning one, leaking extra vehicle rows into
        // VehicleResolverTest/LiveToolboxVehicleScopingTest - see MEMORY.md/lessons.md for the
        // full incident. Gating the whole block, not just the new call, because all three are
        // equally exposed to the identical race and a narrower gate would leave the landmine live
        // for the next addition here.
        if (!isRunningUnderRobolectric()) {
            // Every block below is wrapped (audit fix, 2026-08-07). appScope is a
            // SupervisorJob with NO CoroutineExceptionHandler, so an uncaught throw
            // from a root coroutine here reaches the thread's default handler and
            // kills the process - at cold start, before any UI, on every launch,
            // for as long as the underlying condition persists. With no Crashlytics
            // (MidnightEvents is Log.d only) the driver sees "app won't open" and
            // nothing anywhere records why.
            //
            // SyncEngine already reached this conclusion independently and wraps
            // CompanionProfileStore.materializeActive in runCatching for exactly
            // this reason, so the risk was known - it just had not been applied
            // here. MidnightImport was already hardened internally; the other two
            // were not. The Robolectric gate above means no test can catch a
            // regression in any of this, which makes belt-and-braces the right
            // posture rather than an over-reaction.
            appScope.launch {
                runCatching {
                    CompanionProfileStore.ensureSeeded(this@MidnightApplication)
                    CompanionProfileStore.materializeActive(this@MidnightApplication)
                }.onFailure { MidnightEvents.appStartWorkFailed("companion_seed", it) }
            }

            // One-time Midnight AI fleet-history import (see MidnightImport's class
            // doc). Process-start work, same L12 reasoning as the caches above: it
            // must run unconditionally, not from a service that might not be
            // running. It is its own launch{}, not folded into the one above,
            // because it is unrelated work with its own independent failure mode -
            // a companion-profile hiccup must not skip the fleet import or vice
            // versa. No-ops in one SharedPreferences read on every launch after
            // the bundle either was never present (every clone but Kevin's own
            // machine) or has already imported once.
            appScope.launch {
                // Already hardened internally ("never throws" per its own class doc),
                // wrapped anyway so the guarantee is enforced here rather than trusted.
                runCatching { MidnightImport.run(this@MidnightApplication) }
                    .onFailure { MidnightEvents.appStartWorkFailed("midnight_import", it) }

            }

            // One of the notes/lists/calendar domain's three callers of the one idempotent
            // rescheduleAll() (`.scratch/notes-lists-calendar/issues/03-android-alarm-mechanism.md`) -
            // the other two are BootReceiver and ExactAlarmPermissionReceiver. Must run unconditionally
            // on every process start, same L12 reasoning as the caches above: a reminder scheduled last
            // session needs its alarm confirmed live (or, for a one-off whose time already passed while
            // the process was dead, reported MISSED - ticket 12) before the driver ever opens a screen
            // or taps the assistant.
            appScope.launch {
                runCatching { com.kevin.legion.notes.AlarmScheduler.rescheduleAll(this@MidnightApplication) }
                    .onFailure { MidnightEvents.appStartWorkFailed("reschedule_alarms", it) }
            }

            // The built-in Dates aspect (aspect-engine ticket 19, repointed off the engine by
            // backend-erp ticket 17, "RULED 2026-08-28"): seed the Dates aspect/record-type rows
            // in the engine if this is the first run this schema has ever seen (idempotent - see
            // DatesAspectSeeder.ensureSeeded's own doc comment) - CORRECTED 2026-08-28: as of
            // ticket 17's repoint, neither CalendarImportController.importNow nor DatesAgenda reads
            // this schema any more (both talk to the local `events` table directly); this call now
            // exists only to keep "Dates" registered as a generic engine aspect for
            // EngineToolbox's list_aspects/the xlsx mirror, matching the identical reason
            // NotesAspectSeeder is still seeded every launch (EngineDataMigrationWave1) even though
            // Notes itself retired off the engine first. Then pull in whatever changed on Google
            // since the process last ran (ticket 19 point 2's "runs on app foreground" - this
            // app-start block IS that trigger, same "process start doubles as foreground launch"
            // reasoning AssistantIgnition.resumeIfEnabled's own comment below already uses), then
            // arm the Dates aspect's own single next alarm against whatever the import (or a purely
            // -legion-authored event) left due soonest. Three separate try/catch boundaries, same
            // L12 reasoning as every block in this section: a calendar-import failure must never
            // cost the alarm re-arm, or vice versa.
            appScope.launch {
                runCatching { com.kevin.legion.engine.dates.DatesAspectSeeder.ensureSeeded(this@MidnightApplication) }
                    .onFailure { MidnightEvents.appStartWorkFailed("seed_dates_aspect", it) }
                runCatching { com.kevin.legion.calendar.CalendarImportController.importNow(this@MidnightApplication) }
                    .onFailure { MidnightEvents.appStartWorkFailed("import_google_calendar", it) }
                runCatching { com.kevin.legion.service.DatesAlarmScheduler.armNext(this@MidnightApplication) }
                    .onFailure { MidnightEvents.appStartWorkFailed("arm_dates_reminder", it) }
            }

            // Wave 1 of the aspect-engine migration (`.scratch/aspect-engine/issues/21-migration-waves.md`):
            // seeds the built-in Notes and Places aspects, then copies existing ListItem/TaggedPlace
            // rows onto the engine (see EngineDataMigrationWave1's own doc comment for the two-layer
            // idempotency this rests on - a SharedPreferences completion flag per domain plus a
            // per-row guid check, so a crash partway through never duplicates a row on retry).
            // ADDITIVE ONLY: never touches the legacy tables, which stay the live read/write path for
            // every existing screen and voice tool until this aspect's own cutover wave (ticket 14
            // point 2). Two independent runCatching calls, same L12 "independent failure mode"
            // reasoning as the Dates block above - a Notes copy failure must never cost Places, or
            // vice versa.
            appScope.launch {
                runCatching { com.kevin.legion.engine.migration.EngineDataMigrationWave1.copyNotesIfNeeded(this@MidnightApplication) }
                    .onFailure { MidnightEvents.appStartWorkFailed("migrate_notes_wave1", it) }
                runCatching { com.kevin.legion.engine.migration.EngineDataMigrationWave1.copyPlacesIfNeeded(this@MidnightApplication) }
                    .onFailure { MidnightEvents.appStartWorkFailed("migrate_places_wave1", it) }

                // Cutover 1 (`docs/architecture/cutover1-2026-08-24.md`, ticket 22 point 3): the
                // Notes/Places aspect is now the live read/write path (NotesController/PlaceController
                // above are engine-backed), so this one-time catch-up re-runs the wave 1 copier to pick
                // up any legacy row written between wave 1 landing and this install, then rekeys every
                // ListItemSkip row onto its item's new engine id. Guarded by its OWN completion marker
                // (see EngineDataMigrationWave1.catchUpOnce's own doc comment) so it only ever forces a
                // rescan once. Deliberately sequenced in the SAME coroutine, after the two calls just
                // above, rather than a second appScope.launch - catchUpOnce clears and re-derives from
                // the exact KEY_NOTES_COMPLETED/KEY_PLACES_COMPLETED flags those two calls set, and
                // racing that reset against the calls themselves would be exactly the kind of
                // "improvise past a real ordering dependency" this codebase's own lessons warn about.
                runCatching { com.kevin.legion.engine.migration.EngineDataMigrationWave1.catchUpOnce(this@MidnightApplication) }
                    .onFailure { MidnightEvents.appStartWorkFailed("cutover1_notes_places_catchup", it) }
            }

            // Wave 2 of the aspect-engine migration (`.scratch/aspect-engine/issues/21-migration-waves.md`):
            // seeds the built-in Pantry aspect, then copies existing PantryReceipt/PantryLineItem
            // rows onto the engine (see EngineDataMigrationWave2's own doc comment - same two-layer
            // idempotency as Wave 1 above, plus a fold-in of any line-item-level Failure into the
            // completion check so a partially-copied receipt can never be marked done).
            // ADDITIVE ONLY: never touches pantry_receipts/pantry_line_items, which stay the live
            // read/write path for `pantry/PantryController` and every existing pantry voice tool
            // until this aspect's own cutover wave (ticket 14 point 2). Senior review, 2026-08-23
            // (MUST-FIX 1): this block was missing entirely on first landing - the migration
            // compiled and its own tests passed, but with zero call sites outside its own file it
            // structurally could never run on a real device. Same appScope.launch + runCatching
            // isolation as every block in this section.
            appScope.launch {
                runCatching { com.kevin.legion.engine.migration.EngineDataMigrationWave2.copyPantryIfNeeded(this@MidnightApplication) }
                    .onFailure { MidnightEvents.appStartWorkFailed("migrate_pantry_wave2", it) }

                // Cutover 2 (`docs/architecture/cutover2-2026-08-24.md`, ticket 22 point 3): the
                // Pantry aspect is now the live read/write path (PantryController above is
                // engine-backed), so this one-time catch-up re-runs the wave 2 copier to pick up any
                // legacy row written between wave 2 landing and this install. Guarded by its OWN
                // completion marker (see EngineDataMigrationWave2.catchUpOnce's own doc comment) so
                // it only ever forces a rescan once. Sequenced in the SAME coroutine, after the call
                // just above, for the same reason cutover 1's catch-up is sequenced after its own
                // wave's calls - catchUpOnce clears and re-derives from the exact
                // KEY_PANTRY_COMPLETED flag that call sets.
                runCatching { com.kevin.legion.engine.migration.EngineDataMigrationWave2.catchUpOnce(this@MidnightApplication) }
                    .onFailure { MidnightEvents.appStartWorkFailed("cutover2_pantry_catchup", it) }
            }

            // Wave 3 of the aspect-engine migration (`.scratch/aspect-engine/issues/21-migration-waves.md`):
            // seeds the built-in Ledger aspect, then copies existing LedgerTransaction rows onto the
            // engine (see EngineDataMigrationWave3's own doc comment - same two-layer idempotency as
            // Waves 1/2).
            //
            // Cutover 3 (`docs/architecture/cutover3-2026-08-24.md`): the Ledger aspect is now the
            // live read/write path (LedgerController/IngestPipeline above are engine-backed), so this
            // one-time catch-up re-runs the wave 3 copier AND the rule-7 reconciliation pass once, to
            // pick up any legacy row - or any legacy supersession - that happened between wave 3
            // landing and this install. Guarded by its OWN completion marker (see
            // EngineDataMigrationWave3.catchUpOnce's own doc comment), sequenced in the SAME coroutine
            // after the ordinary copy call for the same reason cutover 2's catch-up is sequenced after
            // its own wave's call.
            appScope.launch {
                runCatching { com.kevin.legion.engine.migration.EngineDataMigrationWave3.copyLedgerIfNeeded(this@MidnightApplication) }
                    .onFailure { MidnightEvents.appStartWorkFailed("migrate_ledger_wave3", it) }

                runCatching { com.kevin.legion.engine.migration.EngineDataMigrationWave3.catchUpOnce(this@MidnightApplication) }
                    .onFailure { MidnightEvents.appStartWorkFailed("cutover3_ledger_catchup", it) }
            }

            // Wave 4 of the aspect-engine migration (`.scratch/aspect-engine/issues/21-migration-waves.md`):
            // seeds the built-in Fleet aspect (Vehicle/ServiceHistory/MaintenanceSchedule), then
            // copies the fleet CORE CHAIN - vehicles, service records, maintenance items, odometer -
            // onto the engine (see EngineDataMigrationWave4's own doc comment). ADDITIVE-ONLY for the
            // copy half: never touches vehicles/service_records/maintenance_items, which stay the
            // live read/write path for `vehicle/VehicleController` and every existing fleet voice
            // tool until this aspect's own cutover wave (ticket 14 point 2). Full carve:
            // `docs/architecture/wave4-carve-2026-08-23.md`. Wired in from this wave's first landing
            // (Wave 2's senior review MUST-FIX 1: a migration with zero call sites cannot run on a
            // real device), same appScope.launch + runCatching isolation as every block in this
            // section.
            appScope.launch {
                runCatching { com.kevin.legion.engine.migration.EngineDataMigrationWave4.copyFleetIfNeeded(this@MidnightApplication) }
                    .onFailure { MidnightEvents.appStartWorkFailed("migrate_fleet_wave4", it) }
            }

            // Ticket 04's label rule (`.scratch/fleet-maintenance/issues/04-one-car-label-rule.md`):
            // the retired "this car" sentinel is a magic value masquerading as user data, and the
            // two rows carrying it are both archived and invisible today - which is exactly why
            // they would otherwise survive forever to trap the next label surface that forgets to
            // filter it. Idempotent (a no-op UPDATE once no row matches), so this runs
            // unconditionally on every process start rather than tracking a run-once flag, same L12
            // reasoning as the caches above.
            appScope.launch {
                runCatching {
                    com.kevin.legion.vehicle.VehicleController.clearThisCarSentinel(this@MidnightApplication)
                }.onFailure { MidnightEvents.appStartWorkFailed("clear_this_car_sentinel", it) }
            }

            // goal-plans ticket 06: "a materializer that runs on app open, and for the current day
            // at acceptance". `accept_goal_plan` (`service/LiveToolbox.kt`) covers the second half;
            // this is the first - the BIO checklist's plan lines are now ordinary ONE-OFF list
            // items materialized fresh per day (ticket 06 replaced ticket 04's recurring-item
            // design after finding a recurring item can never be ticked), so a day that turns over
            // while the app is closed needs today's rows created the next time the app is opened,
            // not just the next time a plan is accepted. Idempotent (see
            // GoalChecklistSync.materializeToday's own doc comment for how), same L12 reasoning as
            // every other block in this gated section: it must run unconditionally on process
            // start, not from a service that might not be running.
            appScope.launch {
                runCatching {
                    com.kevin.legion.advisor.GoalChecklistSync.materializeToday(this@MidnightApplication)
                }.onFailure { MidnightEvents.appStartWorkFailed("materialize_goal_checklist", it) }
            }

            // The mirror/sync lifecycle triggers (aspect-engine ticket 20, senior review MUST-FIX
            // 2) - registers RecordStore.afterWrite -> debounced export, and ProcessLifecycleOwner
            // foreground/background -> import/export. Same gated-block reasoning as every other
            // entry here: it constructs a MirrorSync, which opens CarDatabase, so it must not run
            // under Robolectric for the identical race reasons documented above. bind() itself is
            // synchronous and idempotent (just registers listeners), so it does not need its own
            // appScope.launch/runCatching wrapper the way one-shot Room WORK above does - nothing
            // here does I/O until an actual write or lifecycle event fires later.
            runCatching { MirrorLifecycleBinder.bind(this@MidnightApplication, appScope) }
                .onFailure { MidnightEvents.appStartWorkFailed("bind_mirror_lifecycle", it) }

            // Reconcile the assistant's on/off flag to reality (measured defect, 2026-08-17):
            // AssistantIgnition's persisted flag can read true - and every UI surface built on it
            // agree - while AriaForegroundService is not actually running, because the ONLY
            // callers of AssistantIgnition.start() were the Settings toggle's own handler and
            // nothing else ever restarted the service after a reboot or any other process death.
            // resumeIfEnabled() is NOT a consent action (it never calls setEnabled - see its own
            // doc) - it is a no-op the instant the flag is false, so a driver who never opted in
            // gets nothing started here. Safe to call from a foreground app launch specifically
            // because the app is starting because the user opened it, so none of the
            // background-foreground-service-start restrictions (see BootReceiver's narrower call
            // of the same function) apply - the full permission-gated type set in
            // AriaForegroundService.startForegroundCompat, microphone included, is fine here.
            //
            // Not gated on isRunningUnderRobolectric alone by coincidence - it sits inside the same
            // gated block as the three calls above for the identical L12 race reasoning (a
            // Robolectric test starting a real foreground service intent would be its own hazard,
            // never mind the DB race those three already document).
            runCatching { com.kevin.legion.service.AssistantIgnition.resumeIfEnabled(this@MidnightApplication) }
                .onFailure { MidnightEvents.appStartWorkFailed("resume_assistant_ignition", it) }
        }

        MidnightEvents.setBuildContext(
            buildType = if (BuildConfig.DEBUG) "debug" else "release",
            deviceModel = android.os.Build.MODEL ?: "unknown",
            isEmulator = isProbablyEmulator(),
        )
        MidnightEvents.setCompanionName(CompanionProfile.name(this).ifBlank { "unset" })
        MidnightEvents.setHasGeminiKey(CompanionProfile.hasGeminiKey(this))
        MidnightEvents.setGlEsVersion(rawGlEsVersionHex())
    }

    /** Raw `reqGlEsVersion` as hex (e.g. "0x30000" for ES 3.0). */
    private fun rawGlEsVersionHex(): String {
        val am = getSystemService(android.app.ActivityManager::class.java)
        val version = am?.deviceConfigurationInfo?.reqGlEsVersion ?: return "unknown"
        return "0x%08x".format(version)
    }

    /**
     * Cheap emulator heuristic. Standard fingerprint/model markers; false
     * negatives are fine (an unrecognized emulator just shows up as a device).
     */
    private fun isProbablyEmulator(): Boolean {
        val fp = android.os.Build.FINGERPRINT ?: ""
        val model = android.os.Build.MODEL ?: ""
        val product = android.os.Build.PRODUCT ?: ""
        return fp.startsWith("generic") || fp.contains("emulator") ||
            model.contains("Emulator") || model.contains("Android SDK built for") ||
            product.contains("sdk_gphone") || product == "google_sdk"
    }

    /**
     * True only inside a Robolectric JVM unit test, never on a real device or emulator.
     * Robolectric's shadow layer sets `Build.FINGERPRINT` to the literal string `"robolectric"` -
     * **confirmed by running a throwaway `RobolectricTestRunner` test and printing it**, not
     * assumed, since main source cannot depend on the `org.robolectric` classes themselves
     * (Robolectric is `testImplementation`-only - see `app/build.gradle.kts`) to check some other
     * way. See [onCreate]'s doc comment at the call site for why this gate exists at all.
     */
    private fun isRunningUnderRobolectric(): Boolean = android.os.Build.FINGERPRINT == "robolectric"
}
