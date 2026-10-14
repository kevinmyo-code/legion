package com.kevin.midnightai.sync

import com.kevin.midnightai.sync.CompanionSync.CompanionIdentity
import com.kevin.midnightai.sync.CompanionSync.Decision
import com.kevin.midnightai.sync.CompanionSync.decideCompanion
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the pure companion-identity sync decision matrix (S1's last
 * unbuilt piece). No device needed - see [CompanionSync]'s KDoc for the
 * full rule set.
 */
class CompanionSyncTest {

    private fun identity(name: String = "Zero", updatedAt: Long = 0L) =
        CompanionIdentity(name = name, persona = "warm, playful", traits = "{}", voice = "Sulafat", updatedAt = updatedAt)

    /** What a device has for a car it has never held: nothing at all. */
    private fun blank(updatedAt: Long = 0L) =
        CompanionIdentity(name = "", persona = "", traits = "", voice = "", updatedAt = updatedAt)

    // --- Blank local = absence, not a rival (per-car identity, 2026-07-16) ---

    @Test
    fun blankLocal_adoptsRemote_neverPrompts() {
        // The regression this guards: identity went per-car, so `reconciled` is
        // per-car too. The first time a device selects a car it has never held,
        // local is blank and reconciled is false - which used to PROMPT, asking the
        // driver to choose between nothing and their own companion. Answering "keep
        // local" would upload the blank and destroy that car's identity on Drive.
        val remote = identity(name = "Cherokee", updatedAt = 500L)
        assertEquals(Decision.ADOPT_REMOTE, decideCompanion(blank(), remote, reconciled = false))
    }

    @Test
    fun blankLocal_adoptsRemote_evenWhenLocalClockIsNewer() {
        // A blank local must never win on the clock. Nothing beats something only
        // when the something doesn't exist.
        val remote = identity(name = "Cherokee", updatedAt = 1L)
        assertEquals(Decision.ADOPT_REMOTE, decideCompanion(blank(updatedAt = 999L), remote, reconciled = true))
    }

    @Test
    fun blankBoth_doesNothing() {
        // Ordered after sameContentAs, so two blanks are still "identical", not an adopt.
        assertEquals(Decision.NOTHING, decideCompanion(blank(), blank(), reconciled = false))
    }

    @Test
    fun realLocalVsRealRemote_stillPrompts_whenNeverReconciled() {
        // The genuine "two companions met" case must survive the blank-local fix.
        val local = identity(name = "Zero", updatedAt = 100L)
        val remote = identity(name = "Cherokee", updatedAt = 200L)
        assertEquals(Decision.PROMPT, decideCompanion(local, remote, reconciled = false))
    }

    @Test
    fun blankRemote_againstRealLocal_uploadsLocal() {
        // Mirror image: a blank REMOTE is not a rival either. Reconciled, so LWW -
        // the real local identity is newer and wins.
        val local = identity(name = "Zero", updatedAt = 200L)
        assertEquals(Decision.UPLOAD_LOCAL, decideCompanion(local, blank(updatedAt = 100L), reconciled = true))
    }

    @Test
    fun remoteAbsent_uploadsLocal() {
        val local = identity(updatedAt = 100L)
        assertEquals(Decision.UPLOAD_LOCAL, decideCompanion(local, remote = null, reconciled = false))
        // Reconciled state shouldn't matter here - there's nothing to reconcile against yet.
        assertEquals(Decision.UPLOAD_LOCAL, decideCompanion(local, remote = null, reconciled = true))
    }

    @Test
    fun equalContent_doesNothing() {
        val local = identity(name = "Zero", updatedAt = 100L)
        val remote = identity(name = "Zero", updatedAt = 999L)  // clock differs, content doesn't
        assertEquals(Decision.NOTHING, decideCompanion(local, remote, reconciled = false))
        assertEquals(Decision.NOTHING, decideCompanion(local, remote, reconciled = true))
    }

    @Test
    fun differentContent_neverReconciled_prompts() {
        val local = identity(name = "Zero", updatedAt = 100L)
        val remote = identity(name = "Kaze", updatedAt = 100L)
        assertEquals(Decision.PROMPT, decideCompanion(local, remote, reconciled = false))
    }

    @Test
    fun differentContent_reconciled_remoteNewer_adoptsRemote() {
        val local = identity(name = "Zero", updatedAt = 100L)
        val remote = identity(name = "Kaze", updatedAt = 200L)
        assertEquals(Decision.ADOPT_REMOTE, decideCompanion(local, remote, reconciled = true))
    }

    @Test
    fun differentContent_reconciled_localNewer_uploadsLocal() {
        val local = identity(name = "Zero", updatedAt = 300L)
        val remote = identity(name = "Kaze", updatedAt = 200L)
        assertEquals(Decision.UPLOAD_LOCAL, decideCompanion(local, remote, reconciled = true))
    }

    @Test
    fun differentContent_reconciled_equalClock_doesNothing() {
        val local = identity(name = "Zero", updatedAt = 200L)
        val remote = identity(name = "Kaze", updatedAt = 200L)
        assertEquals(Decision.NOTHING, decideCompanion(local, remote, reconciled = true))
    }
}
