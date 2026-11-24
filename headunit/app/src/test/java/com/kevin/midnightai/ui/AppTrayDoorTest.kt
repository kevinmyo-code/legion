package com.kevin.midnightai.ui

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import com.kevin.midnightai.util.ExternalScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The APPS grid on Cruise, the car's home screen. Its package list is read once in a
 * `LaunchedEffect`, so it is stale by construction: an app uninstalled or un-exported since then
 * used to throw out of the tap and kill the launcher, and in the uninstalled case the old code
 * instead skipped the launch silently and closed the tray on nothing. Both are the dead tap a home
 * app may never do (root `CLAUDE.md` section 1), so both say so now and the tray stays open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h456dp-land-mdpi")
class AppTrayDoorTest {
    @org.junit.After fun closeActivities() = TestActivities.closeAll()

    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun `tapping an app that is gone says so and leaves the tray open`() {
        installGhostApp()
        var dismissed = false
        val activity = TestActivities.start()
        activity.setContent {
            AppTrayOverlay(onDismiss = { dismissed = true }, onEscapeToOS = { null })
        }
        awaitGhostCell(activity)

        // Uninstalled between the list load and the tap. Dropping the launcher intent filter is
        // what takes getLaunchIntentForPackage to null, which is the real shape of this case: an
        // app with no launcher-category activity any more.
        shadowOf(app.packageManager).clearIntentFilterForActivity(ghostComponent())
        shadowOf(app.packageManager).removePackage(GHOST_PKG)
        ComposeTaps.clickable(activity).getValue(GHOST_LABEL).invoke()
        ComposeTaps.idle()

        assertEquals(
            ExternalScreen.INSTALLED_APP.missingWords("app called $GHOST_LABEL"),
            ComposeTaps.texts(activity).lastOrNull { it.startsWith("This head unit has no") },
        )
        assertTrue("the tray closed on a tap that opened nothing", !dismissed)
    }

    @Test fun `tapping an app the unit refuses to start says so and does not crash`() {
        installGhostApp()
        var dismissed = false
        val activity = TestActivities.start()
        activity.setContent {
            // A non-exported activity throws SecurityException out of startActivity, not
            // ActivityNotFoundException. Robolectric's shadow cannot produce that, so the throw
            // comes from the Context the tray is handed.
            CompositionLocalProvider(LocalContext provides RefusingContext(app)) {
                AppTrayOverlay(onDismiss = { dismissed = true }, onEscapeToOS = { null })
            }
        }
        awaitGhostCell(activity)

        ComposeTaps.clickable(activity).getValue(GHOST_LABEL).invoke()
        ComposeTaps.idle()

        assertEquals(
            ExternalScreen.INSTALLED_APP.refusedWords("app called $GHOST_LABEL"),
            ComposeTaps.texts(activity).lastOrNull { it.startsWith("This head unit refused") },
        )
        assertTrue("the tray closed on a tap that opened nothing", !dismissed)
    }

    private fun awaitGhostCell(activity: androidx.activity.ComponentActivity) {
        ComposeTaps.waitFor { ComposeTaps.clickable(activity).containsKey(GHOST_LABEL) }
        assertTrue(
            "the ghost app never appeared in the tray, so this test proves nothing",
            ComposeTaps.clickable(activity).containsKey(GHOST_LABEL),
        )
    }

    private fun ghostComponent() = ComponentName(GHOST_PKG, "$GHOST_PKG.MainActivity")

    /** One launchable third-party app in the shadow package manager, with a readable label. */
    private fun installGhostApp() {
        val pm = shadowOf(app.packageManager)
        pm.installPackage(
            PackageInfo().apply {
                packageName = GHOST_PKG
                applicationInfo = ApplicationInfo().apply {
                    packageName = GHOST_PKG
                    nonLocalizedLabel = GHOST_LABEL
                    flags = ApplicationInfo.FLAG_INSTALLED
                }
            },
        )
        val component = ghostComponent()
        pm.addOrUpdateActivity(
            ActivityInfo().apply {
                packageName = GHOST_PKG
                name = component.className
                nonLocalizedLabel = GHOST_LABEL
                exported = true
                applicationInfo = app.packageManager.getApplicationInfo(GHOST_PKG, 0)
            },
        )
        pm.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    private class RefusingContext(base: Context) : ContextWrapper(base) {
        override fun startActivity(intent: Intent?) =
            throw SecurityException("not exported: $intent")
    }

    private companion object {
        const val GHOST_PKG = "com.example.ghostapp"
        const val GHOST_LABEL = "Ghost"
    }
}
