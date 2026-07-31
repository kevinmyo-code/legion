package com.kevin.legion.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
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
import com.kevin.legion.data.PantryPhotoStore
import com.kevin.legion.pantry.PantryController

/**
 * Placeholder for the import_receipt voice tool (`.claude/plans/wiggly-
 * beaming-quasar.md`) - same posture as [LedgerImportActivity]/
 * [SavedPlacesActivity]: functional, not designed. Take a photo or pick one
 * from the gallery, [PantryPhotoStore] saves it, [PantryController] extracts
 * and reconciles it, the result (item count, or the quarantine reason) is
 * shown.
 */
class PantryImportActivity : ComponentActivity() {
    private var pendingBitmap = mutableStateOf<Bitmap?>(null)

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { pendingBitmap.value = decodeUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val cameraLauncher = rememberCameraCaptureLauncher(this) { pendingBitmap.value = it }
            PantryImportScreen(
                pendingBitmap = pendingBitmap,
                hasCamera = hasCamera(this),
                onTakePhoto = cameraLauncher,
                onPickImage = { pickImage.launch("image/*") },
            )
        }
    }

    private fun decodeUri(uri: Uri): Bitmap? = try {
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        null
    }
}

@Composable
private fun PantryImportScreen(
    pendingBitmap: androidx.compose.runtime.MutableState<Bitmap?>,
    hasCamera: Boolean,
    onTakePhoto: () -> Unit,
    onPickImage: () -> Unit,
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Take or pick a photo of a grocery receipt.") }
    val bitmap by pendingBitmap

    LaunchedEffect(bitmap) {
        val current = bitmap ?: return@LaunchedEffect
        status = "Importing..."
        val file = PantryPhotoStore.save(context, current)
        status = PantryController.importReceipt(context, file).message
        pendingBitmap.value = null
    }

    MaterialTheme {
        Surface {
            Column {
                Text(status)
                if (hasCamera) {
                    Button(onClick = onTakePhoto) {
                        Text("Take photo")
                    }
                }
                Button(onClick = onPickImage) {
                    Text("Pick from gallery")
                }
            }
        }
    }
}
