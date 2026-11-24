package com.kevin.midnightai.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.kevin.midnightai.util.ExternalDoor
import com.kevin.midnightai.util.ExternalScreen
import java.io.File

/**
 * Camera capture for car photos (2026-07-12 dual-target work: phone builds
 * have a real camera, the head unit rarely does). `TakePicture` fires
 * `MediaStore.ACTION_IMAGE_CAPTURE` and needs no `CAMERA` permission of its
 * own - declaring one would force a runtime grant dialog on every install,
 * including camera-less head units. Callers gate the affordance itself on
 * [hasCamera] instead.
 *
 * Reuses the same FileProvider authority + `cache-path` (`res/xml/provider_paths.xml`,
 * `name="."` covers all of cacheDir) that [com.kevin.midnightai.util.LogbookExporter]
 * already uses for share-sheet URIs - no manifest/provider change needed.
 */

/** Whether this device reports a camera at all - gates the "Take photo" affordance. */
internal fun hasCamera(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

/**
 * Returns a launch function: call it to open the system camera app. On a
 * successful capture, the temp JPEG (written under `cacheDir/camera/`) is
 * decoded off the picked orientation as-is and handed to [onCaptured];
 * the temp file is deleted either way so `cacheDir` doesn't accumulate.
 * Callers pass the bitmap straight to the matching `AvatarStudio.save*`
 * function (same downscale-and-persist path the gallery pickers use).
 */
@Composable
internal fun rememberCameraCaptureLauncher(
    context: Context,
    onCaptured: (Bitmap) -> Unit,
): () -> String? {
    var pendingFile by remember { mutableStateOf<File?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val file = pendingFile
        pendingFile = null
        if (success && file != null) {
            BitmapFactory.decodeFile(file.absolutePath)?.let(onCaptured)
        }
        file?.delete()
    }
    // Returns null when a camera app took the request, else the words for what did not happen.
    // This unit has no camera of its own (CLAUDE.md section 8) and a gutted ROM may have no camera
    // app at all, so the launch threw out of the tap and took the launcher with it. The whole body
    // goes through the door because a misconfigured FileProvider throws IllegalArgumentException
    // here too, which killed it just the same.
    return {
        ExternalDoor.attempt(ExternalScreen.CAMERA) {
            val dir = File(context.cacheDir, "camera").apply { mkdirs() }
            val file = File(dir, "${System.currentTimeMillis()}.jpg")
            pendingFile = file
            val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            launcher.launch(uri)
        }
    }
}
