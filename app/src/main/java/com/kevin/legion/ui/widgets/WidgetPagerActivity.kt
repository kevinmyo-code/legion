package com.kevin.legion.ui.widgets

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.kevin.legion.ui.theme.LegionTheme

/**
 * The production widget pager's hosting entry point (aspect-engine ticket 18 build item 5: "do not
 * wire this as the app's home yet - expose it behind a new activity or a debug entry alongside
 * MainActivity; cutover is a later, deliberate step"). Registered in `AndroidManifest.xml` as
 * `exported="false"`, no launcher `<intent-filter>` - it ships in the REAL `main` source set
 * (unlike `prototype/PrototypeDashboardActivity`, which is debug-only, since [WidgetPagerRoot] now
 * reads and writes real, persistent `widget_instances` rows rather than throwaway fixtures), but is
 * reachable only the same way the prototype was:
 *
 * `adb shell am start -n com.kevin.legion/.ui.widgets.WidgetPagerActivity`
 *
 * A later ticket makes this (or the [WidgetPagerRoot] it hosts) `MainActivity`'s own `NavHost`
 * destination, per ticket 14's "old screens keep working until verified" posture - this activity
 * itself may be deleted at that point in favour of a `composable(LegionRoute.DASHBOARD)` entry, same
 * as `SavedPlacesActivity`/`LedgerImportActivity`/`PantryImportActivity` were (see
 * `AndroidManifest.xml`'s own comment on that absorption).
 */
class WidgetPagerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LegionTheme {
                Surface(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    WidgetPagerRoot()
                }
            }
        }
    }
}
