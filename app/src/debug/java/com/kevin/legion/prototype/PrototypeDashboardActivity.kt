package com.kevin.legion.prototype

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
 * THROWAWAY debug-only entry point for aspect-engine tickets 09/18's staged dashboard-grid
 * prototype. Never registered outside `app/src/debug/AndroidManifest.xml` - it cannot ship in a
 * release build, and it is not `MainActivity` or any real screen (ticket 09 was explicit: do not
 * touch a shipped screen for this).
 *
 * Launch from an adb shell once the debug build is installed:
 *   `adb shell am start -n com.kevin.legion/.prototype.PrototypeDashboardActivity`
 *
 * The whole `prototype/` package (this file, [PrototypeDashboardRoot], [PrototypeData],
 * [PrototypeGridPage]) is meant to be deleted wholesale once ticket 18's real widget pager ships
 * on a real screen - keeping it around past that point is dead weight, not a feature. The stage-1
 * reorderable column that used to live here ([ReorderableWidgetColumn]) was already deleted
 * 2026-08-23 once its side-by-side comparison purpose was served.
 */
class PrototypeDashboardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LegionTheme {
                Surface(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    PrototypeDashboardRoot()
                }
            }
        }
    }
}
