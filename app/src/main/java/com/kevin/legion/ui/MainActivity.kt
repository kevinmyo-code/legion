package com.kevin.legion.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Placeholder entry point. The real UI is a clean-slate rebuild - Midnight
 * AI's city-pop screens (Cruise/LightsOut/Logbook/etc.) didn't port; see
 * README.md's "Not done yet" section. This exists only so the app installs
 * and launches while the orchestrator/data-layer port is verified.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PlaceholderScreen()
        }
    }
}

@Composable
private fun PlaceholderScreen() {
    MaterialTheme {
        Surface {
            Text("Legion - UI not built yet")
        }
    }
}
