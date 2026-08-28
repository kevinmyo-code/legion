package com.kevin.legion.ui.settings

import com.kevin.legion.backend.EventsReconcile
import com.kevin.legion.backend.FleetReconcile
import com.kevin.legion.backend.MembershipResult
import com.kevin.legion.backend.PantryReconcile
import com.kevin.legion.backend.PlacesReconcile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM tests for [BackendMigrationResolver] - the pure readiness/report-to-words layer
 * behind `ui/settings/BackendMigrationScreen.kt`. No Context, no network, no Robolectric: every
 * function under test takes only plain values and returns plain values.
 */
class BackendMigrationResolverTest {

    // ---------------------------------------------------------------------------- readiness

    @Test
    fun `not configured wins regardless of membership`() {
        assertEquals(
            BackendMigrationResolver.Readiness.NOT_CONFIGURED,
            BackendMigrationResolver.readiness(configured = false, membership = MembershipResult.Member),
        )
    }

    @Test
    fun `configured but no membership read yet is NOT_READY, never READY`() {
        assertEquals(
            BackendMigrationResolver.Readiness.NOT_READY,
            BackendMigrationResolver.readiness(configured = true, membership = null),
        )
    }

    @Test
    fun `configured and a confirmed member is READY`() {
        assertEquals(
            BackendMigrationResolver.Readiness.READY,
            BackendMigrationResolver.readiness(configured = true, membership = MembershipResult.Member),
        )
    }

    @Test
    fun `configured but not a member is NOT_READY`() {
        assertEquals(
            BackendMigrationResolver.Readiness.NOT_READY,
            BackendMigrationResolver.readiness(configured = true, membership = MembershipResult.NotAMember("nope")),
        )
    }

    // ------------------------------------------------------------------------ disabledReason

    @Test
    fun `disabledReason is null once ready`() {
        assertNull(BackendMigrationResolver.disabledReason(BackendMigrationResolver.Readiness.READY, MembershipResult.Member))
    }

    @Test
    fun `disabledReason names the missing config, in words`() {
        val reason = BackendMigrationResolver.disabledReason(BackendMigrationResolver.Readiness.NOT_CONFIGURED, null)
        assertTrue(reason != null && reason.contains("Not configured"))
    }

    @Test
    fun `disabledReason distinguishes not signed in from not a member`() {
        val notSignedIn = BackendMigrationResolver.disabledReason(BackendMigrationResolver.Readiness.NOT_READY, MembershipResult.NotSignedIn)
        val notAMember = BackendMigrationResolver.disabledReason(
            BackendMigrationResolver.Readiness.NOT_READY,
            MembershipResult.NotAMember("this account is not on the household roster"),
        )
        assertTrue(notSignedIn != null && notSignedIn.contains("Not signed in"))
        assertEquals("this account is not on the household roster", notAMember)
        assertTrue(notSignedIn != notAMember)
    }

    @Test
    fun `disabledReason surfaces a network failure message verbatim`() {
        val reason = BackendMigrationResolver.disabledReason(
            BackendMigrationResolver.Readiness.NOT_READY,
            MembershipResult.NetworkUnreachable("couldn't reach the server"),
        )
        assertEquals("couldn't reach the server", reason)
    }

    @Test
    fun `disabledReason words a still-restoring session distinctly from a confirmed sign-out`() {
        val stillRestoring = BackendMigrationResolver.disabledReason(
            BackendMigrationResolver.Readiness.NOT_READY,
            MembershipResult.Indeterminate("Still checking - the session is taking a moment to restore. Try again shortly."),
        )
        val notSignedIn = BackendMigrationResolver.disabledReason(
            BackendMigrationResolver.Readiness.NOT_READY,
            MembershipResult.NotSignedIn,
        )

        assertEquals(
            "Still checking - the session is taking a moment to restore. Try again shortly.",
            stillRestoring,
        )
        assertTrue(stillRestoring != notSignedIn)
    }

    // ------------------------------------------------------------------- report rendering: places

    @Test
    fun `clean places report says clean and lists nothing one-sided`() {
        val report = PlacesReconcile.Report(
            engineCount = 3, uploaded = 3, serverCountAfter = 3, replicaCountAfter = 3,
            onlyOnEngine = emptyList(), onlyOnServer = emptyList(),
        )
        val lines = BackendMigrationResolver.renderPlacesReport(report)
        assertTrue(lines.any { it.contains("3") && it.contains("uploaded") })
        assertTrue(lines.none { it.contains("Only on") })
        assertTrue(lines.last().contains("Clean"))
    }

    @Test
    fun `a one-sided places diff lists the actual labels, not just a count`() {
        val report = PlacesReconcile.Report(
            engineCount = 3, uploaded = 3, serverCountAfter = 4, replicaCountAfter = 4,
            onlyOnEngine = emptyList(), onlyOnServer = listOf("garage"),
        )
        val lines = BackendMigrationResolver.renderPlacesReport(report)
        assertTrue(lines.any { it.contains("garage") })
        assertTrue(lines.last().contains("Not clean"))
    }

    // ------------------------------------------------------------------- report rendering: pantry

    @Test
    fun `pantry report names rejectedOveraccounted as an expected exception, not an error`() {
        val report = PantryReconcile.Report(
            engineCount = 2, uploaded = 1,
            uploadedUnreconciled = emptyList(),
            rejectedOveraccounted = listOf("Costco (guid-1): totals do not reconcile"),
            serverCountAfter = 1, replicaCountAfter = 1,
            onlyOnEngine = listOf("guid-1"), onlyOnServer = emptyList(),
        )
        val lines = BackendMigrationResolver.renderPantryReport(report)
        val rejectedLine = lines.first { it.contains("Costco") }
        assertTrue(rejectedLine.contains("on purpose"))
        assertTrue(rejectedLine.contains("not an error"))
        assertTrue(lines.any { it.contains("guid-1") && it.contains("Only on this device") })
    }

    @Test
    fun `pantry report words an uploaded-unreconciled receipt as uploaded but unverified, distinct from a rejected one`() {
        val report = PantryReconcile.Report(
            engineCount = 1, uploaded = 0,
            uploadedUnreconciled = listOf("Walmart (guid-2): uploaded UNRECONCILED, USD 802c unaccounted for (total 12886c, lines 12084c)."),
            rejectedOveraccounted = emptyList(),
            serverCountAfter = 1, replicaCountAfter = 1,
            onlyOnEngine = emptyList(), onlyOnServer = emptyList(),
        )
        val lines = BackendMigrationResolver.renderPantryReport(report)
        val unverifiedLine = lines.first { it.contains("Walmart") }
        assertTrue(unverifiedLine.contains("UNVERIFIED"))
        assertTrue(unverifiedLine.contains("uploaded"))
        // Never worded the same as a rejection - a reader must be able to tell "present but
        // unverified" from "missing entirely" from the sentence alone.
        assertTrue(!unverifiedLine.contains("never uploaded"))
    }

    @Test
    fun `clean pantry report is clean`() {
        val report = PantryReconcile.Report(
            engineCount = 2, uploaded = 2,
            uploadedUnreconciled = emptyList(), rejectedOveraccounted = emptyList(),
            serverCountAfter = 2, replicaCountAfter = 2,
            onlyOnEngine = emptyList(), onlyOnServer = emptyList(),
        )
        val lines = BackendMigrationResolver.renderPantryReport(report)
        assertTrue(lines.last().contains("Clean"))
    }

    // ------------------------------------------------------------------- report rendering: events

    @Test
    fun `events report names uploadedUndated as an informational count, and reports both engine counts`() {
        val report = EventsReconcile.Report(
            datesEngineCount = 2, notesEngineCount = 3, uploaded = 4,
            uploadedUndated = 1,
            serverCountAfter = 4, replicaCountAfter = 4,
            onlyOnEngine = emptyList(), onlyOnServer = emptyList(),
        )
        val lines = BackendMigrationResolver.renderEventsReport(report)
        assertTrue(lines[0].contains("2") && lines[0].contains("3"))
        val undatedLine = lines.first { it.contains("no date") }
        assertTrue(undatedLine.contains("1"))
        assertTrue(!undatedLine.contains("never uploaded"))
        // uploadedUndated does NOT keep isClean false - the row genuinely landed on the server.
        assertTrue(lines.last().contains("Clean"))
    }

    @Test
    fun `clean events report is clean`() {
        val report = EventsReconcile.Report(
            datesEngineCount = 1, notesEngineCount = 0, uploaded = 1,
            uploadedUndated = 0, serverCountAfter = 1, replicaCountAfter = 1,
            onlyOnEngine = emptyList(), onlyOnServer = emptyList(),
        )
        val lines = BackendMigrationResolver.renderEventsReport(report)
        assertTrue(lines.last().contains("Clean"))
    }

    // ------------------------------------------------------------------------------- failure

    @Test
    fun `renderFailure never claims a completed upload and names the reason`() {
        val message = BackendMigrationResolver.renderFailure("network unreachable")
        assertTrue(message.contains("Did not finish"))
        assertTrue(message.contains("network unreachable"))
        assertTrue(message.contains("safe to run again", ignoreCase = true))
        assertTrue(message.contains("Nothing on this device was changed"))
    }

    // ------------------------------------------------------------------- report rendering: fleet

    /** A clean, fully-synced fleet report - every table already matches, nothing skipped. Tests
     * that need a one-sided or skipped-row report copy this and override just what they need. */
    private fun cleanFleetReport(): FleetReconcile.Report {
        val syncId = FleetReconcile.SyncIdReport(
            sourceCount = 1, uploaded = 0, skippedUnresolvedVehicle = emptyList(),
            serverCountAfter = 1, replicaCountAfter = 1, onlyOnSource = emptyList(), onlyOnServer = emptyList(),
        )
        return FleetReconcile.Report(
            vehicle = FleetReconcile.VehicleReport(
                engineCount = 2, uploaded = 0, serverCountAfter = 2, replicaCountAfter = 2,
                skippedUnexportable = emptyList(), onlyOnEngine = emptyList(), onlyOnServer = emptyList(),
            ),
            serviceHistory = FleetReconcile.ServiceHistoryReport(
                engineCount = 5, uploaded = 0, skippedUnresolvedVehicle = emptyList(),
                serverCountAfter = 5, replicaCountAfter = 5, onlyOnEngine = emptyList(), onlyOnServer = emptyList(),
            ),
            drive = FleetReconcile.DriveReport(
                sourceCount = 3, uploaded = 0, skippedUnresolvedVehicle = emptyList(),
                serverCountAfter = 3, replicaCountAfter = 3, onlyOnSource = emptyList(), onlyOnServer = emptyList(),
            ),
            codeEvent = syncId,
            codeClearEvent = syncId,
            oilAnalysis = syncId,
            chassisQuirk = FleetReconcile.ChassisQuirkReport(
                sourceCount = 1, uploaded = 0, serverCountAfter = 1, replicaCountAfter = 1,
                onlyOnSource = emptyList(), onlyOnServer = emptyList(),
            ),
            vehicleSpec = FleetReconcile.VehicleSpecReport(
                sourceCount = 1, uploaded = 0, skippedUnresolvedVehicle = emptyList(),
                serverCountAfter = 1, replicaCountAfter = 1, onlyOnSource = emptyList(), onlyOnServer = emptyList(),
            ),
            buildEntry = syncId,
            driveReassignment = syncId,
        )
    }

    @Test
    fun `a clean fleet report leads with clean and never claims a cutover`() {
        val lines = BackendMigrationResolver.renderFleetReport(cleanFleetReport())
        assertTrue(lines.any { it.contains("Overall: clean") })
        // Every other reconcile row on this screen really does move the read to the replica once
        // clean; fleet never does. If any line uses "migrated" a reader would reasonably conclude
        // the read path changed, which ticket 14's ruling explicitly says it does not.
        assertTrue(lines.none { it.contains("migrated", ignoreCase = true) })
        assertTrue(lines.any { it.contains("not a cutover") })
        assertTrue(lines.any { it.contains("Drive keeps syncing fleet") })
    }

    @Test
    fun `skipped-unresolved-vehicle rows are named in words, not just counted`() {
        val report = cleanFleetReport().copy(
            serviceHistory = cleanFleetReport().serviceHistory.copy(
                skippedUnresolvedVehicle = listOf("Oil change (guid-sh-1): vehicle not yet migrated"),
            ),
            drive = cleanFleetReport().drive.copy(
                skippedUnresolvedVehicle = listOf("sync-drive-9: vehicle not yet migrated"),
            ),
        )
        val lines = BackendMigrationResolver.renderFleetReport(report)
        val skippedLine = lines.first { it.contains("Held back") }
        assertTrue(skippedLine.contains("2 rows"))
        assertTrue(skippedLine.contains("not an error", ignoreCase = true) || skippedLine.contains("Not an error"))
        assertTrue(skippedLine.contains("wrong vehicle") || skippedLine.contains("wrong car"))
        assertTrue(skippedLine.contains("Oil change (guid-sh-1)"))
        assertTrue(skippedLine.contains("sync-drive-9"))
    }

    @Test
    fun `a fleet table with nothing to upload reads differently from one already fully synced`() {
        val emptyVehicleReport = cleanFleetReport().copy(
            vehicle = FleetReconcile.VehicleReport(
                engineCount = 0, uploaded = 0, serverCountAfter = 0, replicaCountAfter = 0,
                skippedUnexportable = emptyList(), onlyOnEngine = emptyList(), onlyOnServer = emptyList(),
            ),
        )
        val lines = BackendMigrationResolver.renderFleetReport(emptyVehicleReport)
        val vehicleLine = lines.first { it.startsWith("Vehicles:") }
        val serviceHistoryLine = lines.first { it.startsWith("Service history:") }
        assertTrue(vehicleLine.contains("none on this device"))
        // "already all on the server" was the wording defect (ticket 10, Kevin's 2026-08-28
        // ruling: "kind-scope the guard and fix the wording") - it asserted server state that
        // 0-uploaded-this-run never actually verified. cleanFleetReport's service history really
        // does have serverCountAfter == engineCount and isClean == true, so this line legitimately
        // states BOTH "none uploaded" and the real server count, and reads as clean below.
        assertTrue(serviceHistoryLine.contains("none uploaded this run"))
        assertTrue(serviceHistoryLine.contains("Server has 5"))
        assertTrue(vehicleLine != serviceHistoryLine)
    }

    @Test
    fun `a table held back for an unrelated reason never claims the server already has it`() {
        // Reproduces the exact observed defect: drive_reassignments had 1 row on-device, 0
        // uploaded (its vehicle had not migrated yet, so it was held back before ever reaching
        // the upload call), and the SERVER GENUINELY HAD ZERO. The old wording said "already all
        // on the server" - false - regardless of isClean. The renderer must never say a report
        // with an uploaded count of 0 and a server count of 0 already has the rows.
        val report = cleanFleetReport().copy(
            driveReassignment = cleanFleetReport().driveReassignment.copy(
                sourceCount = 1, uploaded = 0, serverCountAfter = 0, replicaCountAfter = 1,
                skippedUnresolvedVehicle = listOf("reassignment-1: vehicle not yet migrated"),
                onlyOnSource = listOf("reassignment-1"),
            ),
        )
        val lines = BackendMigrationResolver.renderFleetReport(report)
        val line = lines.first { it.startsWith("Drive reassignments:") }
        assertTrue(!line.contains("already all on the server"))
        assertTrue(line.contains("none uploaded this run"))
        assertTrue(line.contains("Server has 0"))
        assertTrue(line.contains("NOT clean"))
    }

    @Test
    fun `an unclean fleet table names the actual one-sided rows`() {
        val report = cleanFleetReport().copy(
            drive = cleanFleetReport().drive.copy(onlyOnServer = listOf("sync-9f2")),
        )
        val lines = BackendMigrationResolver.renderFleetReport(report)
        val driveLine = lines.first { it.startsWith("Drives:") }
        assertTrue(driveLine.contains("NOT clean"))
        assertTrue(driveLine.contains("sync-9f2"))
        assertTrue(lines.any { it.contains("Overall: NOT clean") })
    }

    @Test
    fun `renderFailure for a fleet run reads the same as every other row's failure`() {
        // Fleet shares renderFailure with places/pantry/events on purpose - a failed run is a
        // failed run regardless of aspect, and the wording already covers "some rows may already
        // be there" for any upload loop that can fail partway through.
        val message = BackendMigrationResolver.renderFailure("couldn't reach the server")
        assertTrue(message.contains("Did not finish"))
        assertTrue(message.contains("couldn't reach the server"))
    }
}
