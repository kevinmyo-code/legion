package com.kevin.legion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kevin.legion.location.PlaceController

/**
 * `fleet` tab host. What this screen looks like is ticket 08's job (out of
 * scope here, per ticket 07's resolution) - this is the minimal host that
 * compiles, renders, and makes `fleet/places` reachable so the tab isn't a
 * dead end.
 */
@Composable
fun FleetScreen(onOpenPlaces: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Fleet - not built yet. See ticket 08.")
            Button(onClick = onOpenPlaces) {
                Text("Saved places")
            }
        }
    }
}

/**
 * `fleet/places` - absorbed from the deleted `SavedPlacesActivity`. Content
 * unchanged (list of tagged-place labels for the `show_saved_places` voice
 * tool); only the hosting changed, per ticket 07 resolution §5 ("their
 * content is already written - only the hosting changes").
 */
@Composable
fun SavedPlacesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var places by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(Unit) {
        places = PlaceController.all(context).map { it.label }
    }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Button(onClick = onBack) {
                Text("< Back")
            }
            Text(if (places.isEmpty()) "No saved places yet" else places.joinToString("\n"))
        }
    }
}
