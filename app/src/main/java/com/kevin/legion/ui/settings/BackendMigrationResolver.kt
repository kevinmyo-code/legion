package com.kevin.legion.ui.settings

import com.kevin.legion.backend.ConversationAuditReconcile
import com.kevin.legion.backend.FleetReconcile
import com.kevin.legion.backend.MembershipResult
import com.kevin.legion.backend.ObdSampleReconcile
import com.kevin.legion.backend.PantryReconcile
import com.kevin.legion.backend.PlacesReconcile

/**
 * Pure UI-state derivation for `ui/settings/BackendMigrationScreen.kt` - backend-erp Phase 4's
 * "hands path" for [PlacesReconcile]/[PantryReconcile]/`EventsReconcile`
 * (`.scratch/backend-erp/issues/05-migration-path.md`). **`EventsReconcile` (Notes + Dates) was
 * retired 2026-09-02 (live-sync ticket 04) along with [renderEventsReport] and the screen's own
 * "Notes + Dates" row** - `EventsSync`/`EventsRealtime`/`EventsOutbox` now sync that aspect live,
 * automatically, and the old one-button wholesale wipe-and-refill was traced as actively dangerous
 * (it hid 120 real coursework rows and, separately, its retraction guard soft-deleted ~130 rows on
 * a routine re-check) rather than merely redundant - see `memory/library/decisions.md`'s
 * 2026-09-02 entry. The two remaining reconciles below are unaffected. Kept Android-free and Compose-free on
 * purpose, same posture as [com.kevin.legion.ui.spotify.SpotifyConnectResolver]/
 * [com.kevin.legion.ui.sync.DriveBackupResolver]: the screen owns [SupabaseClientProvider]/
 * [SupabaseAuth]/coroutine plumbing and the real network calls; this object only turns
 * already-fetched values into worded lines and a readiness verdict, so every branch here is a
 * plain JVM unit test with no Robolectric and no network.
 *
 * **Why every list is rendered as actual labels/ids, not just a count** (task brief): a count of
 * "3 only on engine" tells Kevin nothing he can act on. The reconciles themselves already return
 * sorted label/guid lists for exactly this reason ([PlacesReconcile.Report.onlyOnEngine] etc) -
 * this object's only job is to join them into a sentence a person reads once and understands.
 */
object BackendMigrationResolver {

    /** How far the household is from being able to run any reconcile at all. */
    enum class Readiness {
        /** No Supabase project URL/anon key saved yet ([com.kevin.legion.backend.SupabaseConfig.isConfigured]). */
        NOT_CONFIGURED,

        /** Configured, but the signed-in account is not (yet, or no longer) on the household roster,
         * not signed in at all, or the membership check itself could not complete. */
        NOT_READY,

        /** Configured AND a confirmed household member. The only state a reconcile may run in. */
        READY,
    }

    /**
     * [configured] is [com.kevin.legion.backend.SupabaseConfig.isConfigured]'s own result;
     * [membership] is the most recent [com.kevin.legion.backend.SupabaseAuth.isHouseholdMember]
     * result, or null if that check has not completed yet (treated the same as [NOT_READY] -
     * an unread state must never be treated as [READY], CLAUDE.md's own "unreadable and empty
     * are different sentences" rule applied to a permission gate rather than a data read).
     */
    fun readiness(configured: Boolean, membership: MembershipResult?): Readiness = when {
        !configured -> Readiness.NOT_CONFIGURED
        membership is MembershipResult.Member -> Readiness.READY
        else -> Readiness.NOT_READY
    }

    /**
     * The worded reason every button on the screen is disabled, or null once [readiness] is
     * [Readiness.READY]. Never a bare disabled control with no reason (task brief) - matches
     * `ui/KeyScreen.kt`'s own household-state wording rather than inventing new copy for the
     * same four states.
     */
    fun disabledReason(readiness: Readiness, membership: MembershipResult?): String? = when (readiness) {
        Readiness.READY -> null
        Readiness.NOT_CONFIGURED ->
            "Not configured - add your Supabase project URL and anon key on the Gemini key " +
                "screen first."
        Readiness.NOT_READY -> when (membership) {
            is MembershipResult.NotAMember -> membership.message
            MembershipResult.NotSignedIn -> "Not signed in - sign in on the Gemini key screen first."
            is MembershipResult.NetworkUnreachable -> membership.message
            // Worded distinctly from NotSignedIn on purpose - this is the "still restoring the
            // session, do not know yet" state (see MembershipResult.Indeterminate's doc comment),
            // never the same sentence as a confirmed sign-out.
            is MembershipResult.Indeterminate -> membership.message
            MembershipResult.NotConfigured, null ->
                "Not configured - add your Supabase project URL and anon key on the Gemini key " +
                    "screen first."
            MembershipResult.Member -> null // unreachable: readiness() already routed this to READY
        }
    }

    /**
     * [PlacesReconcile.Report] in plain words, one sentence per line. Every non-empty
     * onlyOnEngine/onlyOnServer list is spelled out by label, never collapsed to a count alone.
     */
    fun renderPlacesReport(report: PlacesReconcile.Report): List<String> = buildList {
        add("Engine had ${report.engineCount} ${plural(report.engineCount, "place")}; ${report.uploaded} uploaded.")
        add(
            "Server now has ${report.serverCountAfter} ${plural(report.serverCountAfter, "place")}; " +
                "the on-device replica now has ${report.replicaCountAfter}.",
        )
        if (report.onlyOnEngine.isNotEmpty()) {
            add("Only on this device, not yet on the server: ${report.onlyOnEngine.joinToString(", ")}.")
        }
        if (report.onlyOnServer.isNotEmpty()) {
            add("Only on the server, not on this device: ${report.onlyOnServer.joinToString(", ")}.")
        }
        add(if (report.isClean) "Clean - every place matches on both sides." else "Not clean yet - see the lines above.")
    }

    /** [PantryReconcile.Report] in plain words.
     *
     * **AMENDED 2026-08-26 (ticket 08).** [PantryReconcile.Report.uploadedUnreconciled] is rendered
     * as its own line - these rows DID upload, unlike [PantryReconcile.Report.rejectedOveraccounted]
     * which did not, and the two must never be worded alike or a reader would not be able to tell
     * an unverified-but-present receipt from a genuinely missing one. This wording is also what
     * CLAUDE.md section 4 rule 7 condition 3 means by "every surface says so in words" for the
     * migration report specifically. */
    fun renderPantryReport(report: PantryReconcile.Report): List<String> = buildList {
        add(
            "Engine had ${report.engineCount} ${plural(report.engineCount, "receipt")}; " +
                "${report.uploaded} newly uploaded this run.",
        )
        add(
            "Server now has ${report.serverCountAfter} ${plural(report.serverCountAfter, "receipt")}; " +
                "the on-device replica now has ${report.replicaCountAfter}.",
        )
        if (report.uploadedUnreconciled.isNotEmpty()) {
            add(
                "Uploaded but UNVERIFIED - these receipts charge more than their captured lines " +
                    "explain and could not be re-checked, so they were uploaded as unreconciled " +
                    "rather than dropped: ${report.uploadedUnreconciled.joinToString("; ")}.",
            )
        }
        if (report.rejectedOveraccounted.isNotEmpty()) {
            add(
                "Rejected, on purpose (not an error) - these receipts' captured lines add up to " +
                    "more than the printed total and were never uploaded: " +
                    "${report.rejectedOveraccounted.joinToString("; ")}.",
            )
        }
        if (report.onlyOnEngine.isNotEmpty()) {
            add("Only on this device, not on the server: ${report.onlyOnEngine.joinToString(", ")}.")
        }
        if (report.onlyOnServer.isNotEmpty()) {
            add("Only on the server, not on this device: ${report.onlyOnServer.joinToString(", ")}.")
        }
        add(if (report.isClean) "Clean - every reconciling receipt matches on both sides." else "Not clean yet - see the lines above.")
    }

    // renderEventsReport(EventsReconcile.Report) REMOVED 2026-09-02 (live-sync ticket 04) along
    // with EventsReconcile itself - see this object's own class doc for why (120 hidden coursework
    // rows, a retraction guard that treated a second Settings tap as consent). The worked account
    // of the undated-item wording (ticket 07) and the retraction-guard wording (ticket 20's 213-row
    // incident) that used to live in this function's own doc comment is preserved in
    // `.scratch/live-sync/map.md` and `memory/library/decisions.md`'s 2026-09-02 entry.

    /**
     * [FleetReconcile.Report] in plain words - ten tables, so this earns different structure from
     * the three siblings above rather than ten copies of their line shape.
     *
     * **Fleet is a PROJECTION, never worded as a cutover** (`.scratch/backend-erp/issues/
     * 14-a-vehicle-row-is-co-owned.md`, "RULED 2026-08-27: option 3"). Places/pantry/notes+dates
     * really do move the read to the replica once clean; fleet never does - the phone goes on
     * reading its own tables and Drive goes on syncing fleet between the two phones, both
     * unconditionally, forever. Saying "clean" here must never read like "migrated" does on the
     * other three rows, so the first line states the projection's actual shape before any number
     * appears, and no line below it uses the word "migrated".
     *
     * **[FleetReconcile.ServiceHistoryReport.skippedUnresolvedVehicle] and its seven table-level
     * twins are named in words, never folded into a bare count.** A skipped row is a diagnostic or
     * a drive whose parent vehicle has not reached the server yet - the reconcile refuses to guess
     * which vehicle it belongs to, because a wrong guess would attribute a drive to the wrong car.
     * Collapsing that into "3 skipped" would read like an error; it is a deliberate refusal to
     * mis-parent, and the sentence says so.
     *
     * **[FleetReconcile.VehicleReport.skippedUnexportable] is a different bucket at a different
     * level** - not a child row whose parent has not migrated YET, but a vehicle that cannot satisfy
     * the server's own shape at all until its own data changes (a year of 0, an unpaired odometer
     * baseline). It gets its own line, worded so "run this again" is not the fix - editing the
     * vehicle is.
     *
     * **A table with nothing to upload and a table where everything was already on the server read
     * differently on purpose** - [compactFleetTableLine]'s first branch - because the first means
     * "you have none of this data" and the second means "this run genuinely changed nothing", and
     * collapsing both into "0 uploaded" would erase that distinction.
     */
    fun renderFleetReport(report: FleetReconcile.Report): List<String> {
        val skipped = report.serviceHistory.skippedUnresolvedVehicle +
            report.drive.skippedUnresolvedVehicle +
            report.codeEvent.skippedUnresolvedVehicle +
            report.codeClearEvent.skippedUnresolvedVehicle +
            report.oilAnalysis.skippedUnresolvedVehicle +
            report.vehicleSpec.skippedUnresolvedVehicle +
            report.buildEntry.skippedUnresolvedVehicle +
            report.driveReassignment.skippedUnresolvedVehicle

        return buildList {
            add(
                "This is a one-way export to your own Supabase project, for the laptop surface and " +
                    "for durability - it is not a cutover. The phone keeps reading its own fleet " +
                    "tables, and Drive keeps syncing fleet between your two phones, unchanged by " +
                    "anything below.",
            )
            add(if (report.isClean) "Overall: clean - every table matches the server." else "Overall: NOT clean - see the tables below.")
            add(compactFleetTableLine("Vehicles", report.vehicle.engineCount, report.vehicle.uploaded, report.vehicle.serverCountAfter, report.vehicle.isClean, report.vehicle.onlyOnEngine, report.vehicle.onlyOnServer))
            if (report.vehicle.skippedUnexportable.isNotEmpty()) {
                add(
                    "Not uploaded, ${report.vehicle.skippedUnexportable.size} " +
                        "${plural(report.vehicle.skippedUnexportable.size, "vehicle")} the server would reject as " +
                        "written: ${report.vehicle.skippedUnexportable.joinToString("; ")}. Nothing on this " +
                        "device was changed - fix the vehicle's own data on the phone and run this again.",
                )
            }
            add(compactFleetTableLine("Service history", report.serviceHistory.engineCount, report.serviceHistory.uploaded, report.serviceHistory.serverCountAfter, report.serviceHistory.isClean, report.serviceHistory.onlyOnEngine, report.serviceHistory.onlyOnServer))
            add(compactFleetTableLine("Drives", report.drive.sourceCount, report.drive.uploaded, report.drive.serverCountAfter, report.drive.isClean, report.drive.onlyOnSource, report.drive.onlyOnServer))
            add(compactFleetTableLine("Code events", report.codeEvent.sourceCount, report.codeEvent.uploaded, report.codeEvent.serverCountAfter, report.codeEvent.isClean, report.codeEvent.onlyOnSource, report.codeEvent.onlyOnServer))
            add(compactFleetTableLine("Code-clear events", report.codeClearEvent.sourceCount, report.codeClearEvent.uploaded, report.codeClearEvent.serverCountAfter, report.codeClearEvent.isClean, report.codeClearEvent.onlyOnSource, report.codeClearEvent.onlyOnServer))
            add(compactFleetTableLine("Oil analyses", report.oilAnalysis.sourceCount, report.oilAnalysis.uploaded, report.oilAnalysis.serverCountAfter, report.oilAnalysis.isClean, report.oilAnalysis.onlyOnSource, report.oilAnalysis.onlyOnServer))
            add(compactFleetTableLine("Chassis quirks", report.chassisQuirk.sourceCount, report.chassisQuirk.uploaded, report.chassisQuirk.serverCountAfter, report.chassisQuirk.isClean, report.chassisQuirk.onlyOnSource, report.chassisQuirk.onlyOnServer))
            add(compactFleetTableLine("Vehicle specs", report.vehicleSpec.sourceCount, report.vehicleSpec.uploaded, report.vehicleSpec.serverCountAfter, report.vehicleSpec.isClean, report.vehicleSpec.onlyOnSource, report.vehicleSpec.onlyOnServer))
            add(compactFleetTableLine("Build entries", report.buildEntry.sourceCount, report.buildEntry.uploaded, report.buildEntry.serverCountAfter, report.buildEntry.isClean, report.buildEntry.onlyOnSource, report.buildEntry.onlyOnServer))
            add(compactFleetTableLine("Drive reassignments", report.driveReassignment.sourceCount, report.driveReassignment.uploaded, report.driveReassignment.serverCountAfter, report.driveReassignment.isClean, report.driveReassignment.onlyOnSource, report.driveReassignment.onlyOnServer))
            if (skipped.isNotEmpty()) {
                add(
                    "Held back, not uploaded: ${skipped.size} ${plural(skipped.size, "row")} whose car " +
                        "has not reached the server yet. Not an error - guessing the wrong car would " +
                        "attribute a drive or a diagnostic to the wrong vehicle, so these wait rather " +
                        "than guess. Run this again once Vehicles is clean: ${skipped.joinToString("; ")}.",
                )
            }
        }
    }

    /**
     * One compact line per fleet table for [renderFleetReport]. [count] is that table's
     * on-device row total, distinct from "clean" (server agrees) so a reader can tell "nothing to
     * export" from "everything already matched" from "this run changed something", three different
     * facts a single number cannot carry.
     *
     * **AMENDED 2026-08-28 (ticket 10, "the wording that asserted a state nobody checked").** The
     * `uploaded == 0` branch used to say "already all on the server" - a claim about server state
     * that "0 uploaded this run" does not establish. A row can upload zero because everything
     * really is already there, OR because every row was held back before ever reaching the upload
     * call (a drive reassignment whose vehicle has not migrated yet, in the observed defect) -
     * those are opposite facts and the old wording could not tell them apart. Every [FleetReconcile]
     * report type DOES carry a real, verified [serverCountAfter] (queried from the server after
     * the run, not assumed), so this now says what actually happened - nothing uploaded this run -
     * and states the real server count plainly rather than asserting anything about it beyond what
     * that count already says. [isClean]/[onlyOnSource]/[onlyOnServer] still carry the rest of the
     * truth exactly as before; this amendment touches only the `uploaded == 0` sentence.
     */
    private fun compactFleetTableLine(
        label: String,
        count: Int,
        uploaded: Int,
        serverCountAfter: Int,
        isClean: Boolean,
        onlyOnSource: List<String>,
        onlyOnServer: List<String>,
    ): String {
        val base = when {
            count == 0 -> "$label: none on this device."
            uploaded == 0 -> "$label: $count on this device, none uploaded this run. Server has $serverCountAfter."
            else -> "$label: $count on this device, $uploaded uploaded this run."
        }
        if (isClean) return "$base Clean."
        val detail = buildString {
            if (onlyOnSource.isNotEmpty()) append(" Only on this device: ${onlyOnSource.joinToString(", ")}.")
            if (onlyOnServer.isNotEmpty()) append(" Only on the server: ${onlyOnServer.joinToString(", ")}.")
        }
        return "$base NOT clean.$detail"
    }

    /**
     * [ObdSampleReconcile] has no "clean" concept - see that object's own class doc for why it
     * does not attempt a symmetric server diff at all - so this reads entirely differently from
     * [renderFleetReport]: it states what this run sent, not whether the two sides now agree.
     */
    fun renderObdSampleReport(report: ObdSampleReconcile.Report): List<String> = buildList {
        add(
            "This uploads new OBD telemetry to your own Supabase project in batches, resuming " +
                "from where the last run left off - it does not re-scan or re-check the whole " +
                "table every time.",
        )
        add("${report.sourceCount} ${plural(report.sourceCount, "sample")} on this device; ${report.uploaded} uploaded this run.")
        // Ticket 30: these are two different sentences on purpose. One says "run Fleet first and
        // this will pick back up"; the other says "no run of Fleet will ever take this vehicle, so
        // its samples are skipped for good." Never merged into one line.
        if (report.skippedPermanentlyUnexportableVehicles.isNotEmpty()) {
            add(
                "${report.skippedPermanentlyUnexportableSampleCount} " +
                    "${plural(report.skippedPermanentlyUnexportableSampleCount, "sample")} skipped for good - " +
                    "their vehicle can never be exported: " +
                    "${report.skippedPermanentlyUnexportableVehicles.joinToString("; ")}.",
            )
        }
        if (report.skippedUnresolvedVehicle.isNotEmpty()) {
            add(
                "Stopped early: ${report.skippedUnresolvedVehicle.joinToString("; ")}. Samples " +
                    "past this point were not checked this run - run Fleet's vehicle upload, then " +
                    "run this again.",
            )
        } else if (report.uploaded == 0 && report.skippedPermanentlyUnexportableVehicles.isEmpty()) {
            add("Nothing new since the last run.")
        }
    }

    /**
     * [ConversationAuditReconcile] carries no redaction decision of its own - every row already
     * arrived redacted-or-not from the phone's own table (see that object's own class doc) - so
     * this wording says only what was sent, with no "clean" claim either, same shape as
     * [renderObdSampleReport].
     */
    fun renderConversationAuditReport(report: ConversationAuditReconcile.Report): List<String> = buildList {
        add(
            "This uploads your conversation-and-tool-call audit trail to your own Supabase " +
                "project, resuming from where the last run left off. Read-through redaction " +
                "already happened on this device before any of it was ever stored, so nothing " +
                "sent here is content this app promised to keep off a server.",
        )
        add("${report.sourceCount} ${plural(report.sourceCount, "row")} on this device; ${report.uploaded} uploaded this run.")
        if (report.uploaded == 0) add("Nothing new since the last run.")
    }

    /**
     * A run that threw before returning a [Result], worded per CLAUDE.md §7's outcome-verb rule:
     * say what did NOT happen, never claim a partial upload landed. [reason] is
     * [com.kevin.legion.ui.failureReason]'s own already-worded output, passed in rather than
     * re-derived here so this stays a plain string-in/string-out function.
     *
     * **Deliberately non-committal about how far an upload got.** Every reconcile's upload loop
     * calls the backend once per row and can fail partway through - some rows may already be on
     * the server. Nothing here or in the reconcile itself can tell how many, and every upload is
     * idempotent by natural key (label/guid), so the honest and safe answer is "some rows may
     * already be there, run it again" rather than guessing a number.
     */
    fun renderFailure(reason: String): String =
        "Did not finish - $reason. Some records may already have reached the server, but the " +
            "on-device replica was not refreshed to reflect it. Nothing on this device was " +
            "changed. Safe to run again once the problem is fixed - it will not re-upload " +
            "anything already on the server."

    private fun plural(count: Int, noun: String): String = if (count == 1) noun else "${noun}s"
}
