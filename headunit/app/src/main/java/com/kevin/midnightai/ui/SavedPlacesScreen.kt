package com.kevin.midnightai.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.kevin.midnightai.data.local.TaggedPlace
import com.kevin.midnightai.location.PlaceController
import kotlinx.coroutines.launch

/**
 * Full-screen list of voice-tagged places, shown only when the driver asks
 * ("show me my saved places"). Each row can be deleted; the list reloads
 * after a delete.
 */
@Composable
internal fun SavedPlacesScreen(context: Context, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var places by remember { mutableStateOf<List<TaggedPlace>>(emptyList()) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        places = PlaceController.all(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Saved Places",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClose) {
                Text("Close")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (places.isEmpty()) {
            Text(
                text = "No saved places yet. Say \"this is my work\" while you're somewhere to tag it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (place in places) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(start = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = place.label.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "%.5f, %.5f".format(place.latitude, place.longitude),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = {
                            scope.launch {
                                PlaceController.forget(context, place.label)
                                reloadKey++
                            }
                        }) {
                            Text("Forget")
                        }
                    }
                }
            }
        }
    }
}
