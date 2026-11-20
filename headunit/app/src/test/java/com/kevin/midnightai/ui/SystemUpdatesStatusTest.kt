package com.kevin.midnightai.ui

import com.kevin.midnightai.update.ReleaseInfo
import com.kevin.midnightai.update.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** HU-1 T7: the Updates row leads System and says where the updater is, in words. */
class SystemUpdatesStatusTest {

    private val release = ReleaseInfo("r1", 263231200L, "1.0+abc", "00", 1L, "fixes", "aa")

    @Test fun updatesIsTheFirstSystemRow() {
        assertEquals(SystemRow.UPDATES, systemRows().first())
    }

    @Test fun everyOtherSystemRowStaysInItsOldOrder() {
        assertEquals(
            listOf(
                SystemRow.UPDATES, SystemRow.CLOCK, SystemRow.PERMISSIONS, SystemRow.PREFERENCES,
                SystemRow.LOGS, SystemRow.DEVELOPER_TOOLS, SystemRow.RESET,
            ),
            systemRows(),
        )
    }

    @Test fun upToDateNamesTheInstalledVersionCodeWithAGreenDot() {
        val s = updateStatusLine(UpdateState.UpToDate(0L), installedCode = 263101640L)
        assertEquals(CategoryStatus("Up to date · 263101640", SettingsDot.OK), s)
    }

    @Test fun readyAsksForATap() {
        val s = updateStatusLine(UpdateState.Ready(release, File("x")), installedCode = 1L)
        assertEquals(CategoryStatus("Update ready · tap to install", SettingsDot.WARN), s)
    }

    @Test fun notCheckedYetShowsTheVersionWithNoDot() {
        // Nothing was read yet, so no dot: a dot only ever comes from a real check.
        assertEquals(CategoryStatus("Version 263101640"), updateStatusLine(UpdateState.Idle, 263101640L))
        assertEquals(CategoryStatus("Checking for updates"), updateStatusLine(UpdateState.Checking, 263101640L))
    }

    @Test fun inFlightStatesSayWhatIsHappening() {
        assertEquals("Downloading update", updateStatusLine(UpdateState.Downloading(release, 0L), 1L).line)
        assertEquals("Update installs after this chat", updateStatusLine(UpdateState.Waiting(release, File("x")), 1L).line)
        assertEquals("Installing update", updateStatusLine(UpdateState.Installing(release, 0L), 1L).line)
        assertEquals("Update found · 263231200", updateStatusLine(UpdateState.Available(release, "why"), 1L).line)
    }

    @Test fun debugBuildSaysItNeverUpdates() {
        assertEquals(CategoryStatus("Debug build · no updates"), updateStatusLine(UpdateState.DebugBuild, 1L))
    }

    @Test fun failureIsAWarningNotAnError() {
        assertEquals(CategoryStatus("Update check failed", SettingsDot.WARN), updateStatusLine(UpdateState.Failed("x"), 1L))
    }

    @Test fun aMissingPermissionStillWinsTheSystemRail() {
        val perms = permissionsCategoryStatus(1)
        val update = updateStatusLine(UpdateState.Ready(release, File("x")), 1L)
        assertEquals(perms, systemCategoryStatus(perms, update))
    }

    @Test fun withPermissionsGrantedTheRailShowsTheUpdateLine() {
        val update = updateStatusLine(UpdateState.UpToDate(0L), 263101640L)
        assertEquals(update, systemCategoryStatus(permissionsCategoryStatus(0), update))
    }

    @Test fun anUncheckedUpdaterLeavesThePermissionsLine() {
        val perms = permissionsCategoryStatus(0)
        assertEquals(perms, systemCategoryStatus(perms, updateStatusLine(UpdateState.Idle, 5L)))
    }
}
