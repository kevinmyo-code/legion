package com.kevin.legion.ui.settings

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The camera pane that reads the head unit's pairing QR: CameraX preview plus ML Kit's barcode
 * scanner with its model BUNDLED in the APK (no Play services download, works offline). QR codes
 * only. Each distinct text it reads goes to [onQr] once; the ViewModel hands it to nothing but the
 * pairing parser, so a QR holding a web address is never opened. If ML Kit cannot start, the camera
 * is never opened and [onFailed] is called instead.
 */
@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
fun CarLinkQrScanPane(onQr: (String) -> Unit, onFailed: () -> Unit) {
    val context = LocalContext.current
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val preview = remember { PreviewView(context) }
    DisposableEffect(owner) {
        // The manifest removes ML Kit's start-up provider, so ML Kit starts here, only when Scan opens,
        // and before the camera: a failed start leaves nothing to release.
        val scanner = openScanner(
            init = { MlKitContext.initializeIfNeeded(context.applicationContext) },
            client = {
                BarcodeScanning.getClient(
                    BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
                )
            },
            failed = { kind -> Log.w("CarLink", "scanner could not start: $kind") },
        )
        if (scanner == null) {
            onFailed()
            return@DisposableEffect onDispose {}
        }
        val executor = Executors.newSingleThreadExecutor()
        val seen = HashSet<String>()
        val main = ContextCompat.getMainExecutor(context)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        // Leaving the pane before the camera is ready must not bind it afterwards.
        val gate = ScanBindGate()
        val mine = PaneCameraUses<UseCase>()
        providerFuture.addListener({ gate.runIfLive {
            val bound = startOrReport(
                step = { bindScanCamera(providerFuture.get(), owner, preview, executor, scanner, seen, main, onQr) },
                failed = { kind -> Log.w("CarLink", "camera could not start: $kind") },
            )
            if (bound == null) onFailed() else mine.record(bound)
        } }, main)
        onDispose {
            gate.dispose()
            val release = mine.release()
            if (release.isNotEmpty()) runCatching { providerFuture.get().unbind(*release.toTypedArray()) }
            scanner.close()
            executor.shutdown()
        }
    }
    AndroidView(factory = { preview }, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
}

/** Binds a preview and the QR analyser to the back camera (or any camera); returns what it bound. */
@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Suppress("LongParameterList") // the pane's own pieces, handed over once
private fun bindScanCamera(
    provider: ProcessCameraProvider,
    owner: LifecycleOwner,
    preview: PreviewView,
    executor: ExecutorService,
    scanner: BarcodeScanner,
    seen: MutableSet<String>,
    main: Executor,
    onQr: (String) -> Unit,
): List<UseCase> {
    val camera = pickCamera(
        CameraSelector.DEFAULT_BACK_CAMERA,
        provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA),
        provider.availableCameraInfos.map { it.cameraSelector },
    ) ?: error("no camera")
    val previewUse = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
    val analysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
    analysis.setAnalyzer(executor) { proxy ->
        val media = proxy.image
        if (media == null) {
            proxy.close()
        } else {
            scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                .addOnSuccessListener(main) { codes ->
                    codes.mapNotNull { it.rawValue }.firstOrNull { seen.add(it) }?.let(onQr)
                }
                .addOnCompleteListener { proxy.close() }
        }
    }
    provider.bindToLifecycle(owner, camera, previewUse, analysis)
    return listOf(previewUse, analysis)
}
