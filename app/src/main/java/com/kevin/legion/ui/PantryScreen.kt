package com.kevin.legion.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.kevin.legion.data.PantryPhotoStore
import com.kevin.legion.pantry.PantryController

/**
 * `pantry` tab host. What this screen looks like is ticket 08/09's job (out
 * of scope here) - this is the minimal host that compiles, renders, and
 * makes `pantry/import` reachable.
 */
@Composable
fun PantryScreen(onOpenImport: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Pantry - not built yet. See ticket 08/09.")
            Button(onClick = onOpenImport) {
                Text("Import receipt")
            }
        }
    }
}

/**
 * `pantry/import` - absorbed from the deleted `PantryImportActivity`.
 * Content unchanged (take or pick a photo, [PantryPhotoStore] saves it,
 * [PantryController] extracts and reconciles it); only the hosting changed.
 * `decodeUri` moved from an Activity method (needed `contentResolver`) to a
 * private top-level function taking `Context`, since there is no Activity
 * instance here to hang it off.
 */
@Composable
fun PantryImportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Take or pick a photo of a grocery receipt.") }
    var pendingBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { pendingBitmap = decodePantryUri(context, it) }
    }
    val cameraLauncher = rememberCameraCaptureLauncher(context) { pendingBitmap = it }

    LaunchedEffect(pendingBitmap) {
        val current = pendingBitmap ?: return@LaunchedEffect
        status = "Importing..."
        val file = PantryPhotoStore.save(context, current)
        status = PantryController.importReceipt(context, file).message
        pendingBitmap = null
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Button(onClick = onBack) {
                Text("< Back")
            }
            Text(status)
            if (hasCamera(context)) {
                Button(onClick = cameraLauncher) {
                    Text("Take photo")
                }
            }
            Button(onClick = { pickImage.launch("image/*") }) {
                Text("Pick from gallery")
            }
        }
    }
}

private fun decodePantryUri(context: android.content.Context, uri: Uri): Bitmap? = try {
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
} catch (e: Exception) {
    null
}
