package com.kevin.legion.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.kevin.legion.location.PlaceController

/**
 * Placeholder for the show_saved_places voice tool (kept per the 2026-07-31
 * carry-over decision). Real UI is a clean-slate rebuild, same as
 * [MainActivity] - this just lists labels so the tool has somewhere to point.
 */
class SavedPlacesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SavedPlacesPlaceholderScreen()
        }
    }
}

@Composable
private fun SavedPlacesPlaceholderScreen() {
    val context = LocalContext.current
    var places by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(Unit) {
        places = PlaceController.all(context).map { it.label }
    }
    MaterialTheme {
        Surface {
            Text(if (places.isEmpty()) "No saved places yet" else places.joinToString("\n"))
        }
    }
}
