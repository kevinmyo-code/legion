package com.kevin.midnightai.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.kevin.midnightai.ui.theme.AriaTheme

/**
 * Thin host for [SavedPlacesScreen], launched by the voice tool
 * `show_saved_places`. With the full dashboard gone in the overlay
 * pivot, there's no on-screen surface to flip to a list, so the controller
 * starts this activity instead; "Close" finishes it and returns the driver to
 * whatever they were using under the floating button.
 */
class SavedPlacesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AriaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    SavedPlacesScreen(context = this, onClose = { finish() })
                }
            }
        }
    }
}
