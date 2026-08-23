package com.kevin.legion.sitrep

import android.content.pm.PackageManager
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Ticket 32 (`.scratch/hands-and-senses/issues/32-sitrep-on-demand-only.md`, Kevin: "sitreps stay
 * tap only or via voice activation only") - nothing arms a sitrep alarm on boot, on app start, or
 * ever. There is no scheduled path left to test the ABSENCE of behaviourally (the alarm receiver
 * that used to fire it, and the scheduler that used to arm it, are both DELETED, not merely
 * disabled - see this ticket's own "remove rather than default off, a switch that must stay off
 * is a trap someone flips later"), so this file proves the absence the only two ways that are
 * actually checkable: the classes themselves are gone, and nothing in the merged manifest -
 * which is what [com.kevin.legion.service.BootReceiver] and the OS both actually consult - still
 * declares a receiver for one.
 */
@RunWith(RobolectricTestRunner::class)
class SitrepOnDemandOnlyTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `SitrepScheduler no longer exists - there is nothing left to arm an alarm with`() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("com.kevin.legion.sitrep.SitrepScheduler")
        }
    }

    @Test
    fun `SitrepAlarmReceiver no longer exists - there is nothing left for an alarm to fire into`() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("com.kevin.legion.sitrep.SitrepAlarmReceiver")
        }
    }

    @Test
    fun `the manifest declares no sitrep receiver - BootReceiver has nothing sitrep-shaped to re-arm`() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_RECEIVERS)
        val receiverNames = info.receivers?.map { it.name }.orEmpty()
        assertTrue(
            "expected no sitrep receiver in the manifest, found: $receiverNames",
            receiverNames.none { it.contains("Sitrep", ignoreCase = true) },
        )
    }
}
