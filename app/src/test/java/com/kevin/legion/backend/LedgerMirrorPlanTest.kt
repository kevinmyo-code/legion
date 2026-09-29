package com.kevin.legion.backend

import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [planLedgerMirror] - every rule of the engine mirror, decided from rows alone (live-sync ticket
 * 08). The Room-backed half, including "server down leaves Room untouched", is
 * [LedgerTransactionsMirrorTest].
 */
class LedgerMirrorPlanTest {

    private fun remote(
        serverId: String,
        provenance: String = "DETERMINISTIC",
        category: String? = null,
        categoryPending: Boolean = category == null,
        originGuid: String? = null,
    ) = RemoteLedgerTransaction(
        serverId = serverId,
        statementId = if (provenance == "UNRECONCILED") null else "stmt-1",
        accountLast4 = "3119",
        accountNickname = "BofA checking",
        currency = "USD",
        txnDateEpochMs = 1_788_000_000_000L,
        description = "HEB GROCERY $serverId",
        amountCents = -6_000L,
        balanceCents = null,
        lineRef = "ref-$serverId",
        category = category,
        categoryPending = categoryPending,
        pendingLoggedAtMs = null,
        provenance = provenance,
        createdAtMs = 1_788_000_000_000L,
        originGuid = originGuid,
    )

    private fun local(
        id: Long,
        syncId: String,
        sourceFile: String = SYNCED_SOURCE_FILE,
        category: String? = null,
        pendingLoggedAt: Long? = null,
    ) = LedgerTransaction(
        id = id,
        sourceFile = sourceFile,
        accountId = "BofA checking",
        currency = LedgerCurrency.USD,
        txnDate = 1_788_000_000_000L,
        description = "row $id",
        amountCents = -100L,
        lineRef = "l-$id",
        ingestMethod = IngestMethod.UNRECONCILED,
        syncId = syncId,
        category = category,
        pendingLoggedAt = pendingLoggedAt,
    )

    @Test
    fun `a server row the phone has never seen is inserted as a synced row keyed by its server id`() {
        val plan = planLedgerMirror(emptyList(), listOf(remote("s-1", category = "Groceries", categoryPending = false)), complete = true)

        val row = plan.toInsert.single()
        assertEquals(SYNCED_SOURCE_FILE, row.sourceFile)
        assertEquals("s-1", row.syncId)
        assertEquals(IngestMethod.DETERMINISTIC, row.ingestMethod)
        assertEquals("Groceries", row.category)
        assertFalse(row.categoryPending)
    }

    @Test
    fun `an uncategorised server row lands uncategorised, never labelled a guess`() {
        // Server `category_pending` defaults true on a row nobody categorised; the phone's flag
        // means "unconfirmed AI guess" and would print "category guessed, not confirmed".
        val plan = planLedgerMirror(emptyList(), listOf(remote("s-1", category = null, categoryPending = true)), complete = true)

        val row = plan.toInsert.single()
        assertNull(row.category)
        assertFalse(row.categoryPending)
    }

    @Test
    fun `UNRECONCILED and USER both land UNRECONCILED, an unknown provenance is refused`() {
        val plan = planLedgerMirror(
            emptyList(),
            listOf(remote("s-1", "UNRECONCILED"), remote("s-2", "USER"), remote("s-3", "MYSTERY")),
            complete = true,
        )

        assertEquals(listOf(IngestMethod.UNRECONCILED, IngestMethod.UNRECONCILED), plan.toInsert.map { it.ingestMethod })
        assertEquals(1, plan.unrecognizedProvenance.size)
        assertTrue(plan.unrecognizedProvenance.single().contains("s-3"))
    }

    @Test
    fun `a server category fills a synced row the phone holds uncategorised`() {
        val plan = planLedgerMirror(
            listOf(local(7, "s-1", category = null)),
            listOf(remote("s-1", category = "Groceries", categoryPending = false)),
            complete = true,
        )

        assertEquals(listOf(LedgerMirrorPlan.CategoryFill(7, "Groceries", false)), plan.categoryFills)
        assertTrue(plan.toInsert.isEmpty())
        assertEquals(1, plan.alreadyPresent)
    }

    @Test
    fun `a category the phone already has is never overwritten by the server's`() {
        val plan = planLedgerMirror(
            listOf(local(7, "s-1", category = "Gifts")),
            listOf(remote("s-1", category = "Groceries", categoryPending = false)),
            complete = true,
        )
        assertTrue(plan.categoryFills.isEmpty())
    }

    @Test
    fun `a synced row the complete list no longer contains is deleted`() {
        val plan = planLedgerMirror(
            listOf(local(1, "s-kept"), local(2, "s-superseded")),
            listOf(remote("s-kept")),
            complete = true,
        )
        assertEquals(listOf("s-superseded"), plan.toDeleteSyncIds)
        assertFalse(plan.deletionsSkipped)
    }

    @Test
    fun `phone-only rows are never delete candidates`() {
        val plan = planLedgerMirror(
            listOf(
                local(1, "voice-guid", sourceFile = "voice", pendingLoggedAt = 1L),
                local(2, "file-guid", sourceFile = "eStmt_2026-08.pdf"),
                local(3, "s-gone"),
            ),
            listOf(remote("s-other")),
            complete = true,
        )
        assertEquals(listOf("s-gone"), plan.toDeleteSyncIds)
    }

    @Test
    fun `an incomplete list deletes nothing and says so`() {
        val plan = planLedgerMirror(
            listOf(local(1, "s-1"), local(2, "s-2")),
            listOf(remote("s-1"), remote("s-3")),
            complete = false,
        )
        assertTrue(plan.toDeleteSyncIds.isEmpty())
        assertTrue(plan.deletionsSkipped)
        // Inserts still happen: "at least these rows" is still true.
        assertEquals(listOf("s-3"), plan.toInsert.map { it.syncId })
    }

    @Test
    fun `an empty list against a phone holding server rows deletes nothing`() {
        val plan = planLedgerMirror(listOf(local(1, "s-1")), emptyList(), complete = true)
        assertTrue(plan.toDeleteSyncIds.isEmpty())
        assertTrue(plan.deletionsSkipped)
    }

    @Test
    fun `a row this phone minted and once uploaded is recognised by origin guid, not duplicated`() {
        val plan = planLedgerMirror(
            listOf(local(1, "phone-guid", sourceFile = "voice", pendingLoggedAt = 1L)),
            listOf(remote("s-9", "UNRECONCILED", category = "Dining", categoryPending = false, originGuid = "phone-guid")),
            complete = true,
        )
        assertTrue(plan.toInsert.isEmpty())
        // Not a synced row, so the server's category does not fill it either.
        assertTrue(plan.categoryFills.isEmpty())
        assertEquals(1, plan.alreadyPresent)
    }

    @Test
    fun `the not-synced line is silent after a success and worded after a failure`() {
        assertNull(ledgerMirrorStatusLine(lastSuccessAtMs = 0L, lastFailureAtMs = 0L))
        assertNull(ledgerMirrorStatusLine(lastSuccessAtMs = 20L, lastFailureAtMs = 10L))
        assertEquals(
            "Not synced: the server couldn't be read just now, so this shows what this phone last had",
            ledgerMirrorStatusLine(lastSuccessAtMs = 10L, lastFailureAtMs = 20L),
        )
        assertEquals(
            "Not synced: the server couldn't be read, so this shows only what is on this phone",
            ledgerMirrorStatusLine(lastSuccessAtMs = 0L, lastFailureAtMs = 20L),
        )
        assertEquals("Not synced - this phone's last copy", ledgerMirrorStatusLine(10L, 20L, short = true))
        assertEquals("Not synced - this phone only", ledgerMirrorStatusLine(0L, 20L, short = true))
    }
}
