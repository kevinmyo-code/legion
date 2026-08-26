package com.kevin.legion.ui.settings

import com.kevin.legion.backend.EventsReconcile
import com.kevin.legion.backend.MembershipResult
import com.kevin.legion.backend.PantryReconcile
import com.kevin.legion.backend.PlacesReconcile

/**
 * Pure UI-state derivation for `ui/settings/BackendMigrationScreen.kt` - backend-erp Phase 4's
 * "hands path" for [PlacesReconcile]/[PantryReconcile]/[EventsReconcile]
 * (`.scratch/backend-erp/issues/05-migration-path.md`). Kept Android-free and Compose-free on
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

    /** [PantryReconcile.Report] in plain words. [PantryReconcile.Report.skippedUnreconciled] is
     * rendered as a named, expected exception (per that report's own doc comment), never lumped
     * in with the onlyOnEngine/onlyOnServer diff lines below it. */
    fun renderPantryReport(report: PantryReconcile.Report): List<String> = buildList {
        add(
            "Engine had ${report.engineCount} ${plural(report.engineCount, "receipt")}; " +
                "${report.uploaded} newly uploaded this run.",
        )
        add(
            "Server now has ${report.serverCountAfter} ${plural(report.serverCountAfter, "receipt")}; " +
                "the on-device replica now has ${report.replicaCountAfter}.",
        )
        if (report.skippedUnreconciled.isNotEmpty()) {
            add(
                "Skipped, on purpose (not an error) - these no longer reconcile against their " +
                    "own arithmetic and were never uploaded: ${report.skippedUnreconciled.joinToString("; ")}.",
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

    /** [EventsReconcile.Report] in plain words. [EventsReconcile.Report.skippedUndated] is
     * rendered as a named, expected exception (per that report's own doc comment) - a non-empty
     * list there is a steady state, not a bug to chase to zero. */
    fun renderEventsReport(report: EventsReconcile.Report): List<String> = buildList {
        add(
            "Engine had ${report.datesEngineCount} dated ${plural(report.datesEngineCount, "event")} and " +
                "${report.notesEngineCount} note ${plural(report.notesEngineCount, "item")}; " +
                "${report.uploaded} newly uploaded this run.",
        )
        add(
            "Server now has ${report.serverCountAfter} ${plural(report.serverCountAfter, "row")}; " +
                "the on-device replica now has ${report.replicaCountAfter}.",
        )
        if (report.skippedUndated.isNotEmpty()) {
            add(
                "Skipped, on purpose (not an error) - these have no date and were never uploaded: " +
                    "${report.skippedUndated.joinToString("; ")}.",
            )
        }
        if (report.onlyOnEngine.isNotEmpty()) {
            add("Only on this device, not on the server: ${report.onlyOnEngine.joinToString(", ")}.")
        }
        if (report.onlyOnServer.isNotEmpty()) {
            add("Only on the server, not on this device: ${report.onlyOnServer.joinToString(", ")}.")
        }
        add(if (report.isClean) "Clean - everything matches on both sides." else "Not clean yet - see the lines above.")
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
