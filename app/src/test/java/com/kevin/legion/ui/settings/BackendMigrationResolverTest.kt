package com.kevin.legion.ui.settings

import com.kevin.legion.backend.EventsReconcile
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
}
