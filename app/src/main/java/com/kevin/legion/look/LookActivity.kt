package com.kevin.legion.look

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevin.legion.location.LocationController
import com.kevin.legion.ui.theme.soft.SoftTheme
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The camera screen for "look at this" (ADR 0084). Shows the preview and, while [LookController]
 * says the look is open, hands about one JPEG a second to it, which sends it into the open voice
 * conversation. Leaving the screen ([onStop]) ends the look and stops frames at once.
 *
 * Frames are encoded in memory and dropped after the send; nothing here writes to disk or logs a
 * frame. The status line says the assistant can see only after a frame actually went out
 * ([LookController.State.framesSent]).
 *
 * Opened by the `look` voice tool and by the Show button on the Fleet screen ([ShowToAssistantButton]), both through
 * [LookController] (ADR 0035).
 */
class LookActivity : ComponentActivity() {

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val source = sourceOf(intent)
        LookController.ensureOpen(source)
        setContent {
            SoftTheme {
                LookScreen(source = source, onClose = ::endLook, bindCamera = ::bindCamera)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A voice `look` while the screen is already up: the screen is showing for this look too.
        LookController.ensureOpen(sourceOf(intent))
    }

    override fun onStart() {
        super.onStart()
        // GPS speed while the look is open, for the moving check ([LookRules.motion]).
        LocationController.startFastMode(this)
    }

    override fun onStop() {
        LocationController.stopFastMode()
        // Leaving the screen ends the look: frames stop the moment it is not on screen.
        LookController.close()
        cameraProvider?.unbindAll()
        super.onStop()
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    private fun endLook() {
        LookController.close()
        finish()
    }

    /** Binds preview and analysis to this activity. [onLive] runs once the first camera frame arrives. */
    private fun bindCamera(previewView: PreviewView, onLive: () -> Unit) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            cameraProvider = provider
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                            ),
                        )
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            var announced = false
            analysis.setAnalyzer(analysisExecutor) { image ->
                if (!announced) {
                    announced = true
                    runOnUiThread(onLive)
                }
                analyze(image)
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "camera bind failed: ${e.message}")
                LookController.close("The camera could not be opened, so nothing was sent.")
            } catch (e: IllegalStateException) {
                Log.w(TAG, "camera bind failed: ${e.message}")
                LookController.close("The camera could not be opened, so nothing was sent.")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** One analysis frame: encoded and offered only when one is due, and always closed. */
    private fun analyze(image: ImageProxy) {
        try {
            if (!LookController.frameWanted()) return
            val jpeg = encode(image) ?: return
            LookController.offerFrame(jpeg)
        } finally {
            image.close()
        }
    }

    private fun encode(image: ImageProxy): ByteArray? = runCatching {
        val raw = image.toBitmap()
        val rotation = image.imageInfo.rotationDegrees
        val longest = maxOf(raw.width, raw.height)
        val scale = if (longest > LookRules.MAX_FRAME_SIDE) LookRules.MAX_FRAME_SIDE.toFloat() / longest else 1f
        val matrix = Matrix().apply {
            if (rotation != 0) postRotate(rotation.toFloat())
            if (scale != 1f) postScale(scale, scale)
        }
        val frame = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        val out = ByteArrayOutputStream()
        frame.compress(Bitmap.CompressFormat.JPEG, LookRules.JPEG_QUALITY, out)
        if (frame !== raw) frame.recycle()
        raw.recycle()
        out.toByteArray()
    }.getOrNull()

    companion object {
        private const val TAG = "LookActivity"
        private const val EXTRA_SOURCE = "look_source"
        private const val ANALYSIS_WIDTH = 640
        private const val ANALYSIS_HEIGHT = 480

        private fun sourceOf(intent: Intent?): LookController.Source =
            intent?.getStringExtra(EXTRA_SOURCE)
                ?.let { runCatching { LookController.Source.valueOf(it) }.getOrNull() }
                ?: LookController.Source.HAND


        /** Brings the camera screen up. True when Android accepted the start. */
        fun open(context: Context, source: LookController.Source): Boolean {
            val intent = Intent(context, LookActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_SOURCE, source.name)
            // ActivityNotFoundException or SecurityException: the caller says in words it did not open.
            return runCatching { context.startActivity(intent) }
                .onFailure { Log.w(TAG, "look screen did not start: ${it.javaClass.simpleName}") }
                .isSuccess
        }
    }
}

@Composable
private fun LookScreen(
    source: LookController.Source,
    onClose: () -> Unit,
    bindCamera: (PreviewView, () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val state by LookController.state.collectAsStateWithLifecycle()
    var permission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var asked by remember { mutableStateOf(false) }
    var cameraLive by remember { mutableStateOf(false) }
    var conversationOpen by remember { mutableStateOf(LookController.conversationOpen()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permission = granted
        asked = true
        if (!granted) LookController.close("Camera permission was refused, so nothing was sent.")
    }
    LaunchedEffect(Unit) { if (!permission) ask.launch(Manifest.permission.CAMERA) }

    // The button path joins or starts the conversation only once the camera is really running.
    LaunchedEffect(cameraLive) {
        if (cameraLive && source == LookController.Source.HAND && LookController.state.value.open) {
            LookController.startConversationForHand()
        }
    }
    LaunchedEffect(Unit) { watchOpenLook(context) { conversationOpen = it } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (permission) {
            AndroidView(
                factory = { ctx -> PreviewView(ctx).also { view -> bindCamera(view) { cameraLive = true } } },
                modifier = Modifier.fillMaxSize(),
            )
        }
        LookPanel(
            status = statusLine(state, permission, asked, cameraLive, conversationOpen),
            notice = state.notice,
            onClose = onClose,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        Text(
            "Parked use only. Frames are not saved.",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = HINT_ALPHA),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp),
        )
    }
}

/**
 * While the screen shows: every [WATCH_MS] re-check motion (an open look closes if the car starts
 * moving) and end the look if the conversation it was feeding has ended. Runs until the screen goes.
 */
private suspend fun watchOpenLook(context: Context, onConversation: (Boolean) -> Unit) {
    var everSent = false
    while (true) {
        delay(WATCH_MS)
        val open = LookController.conversationOpen()
        onConversation(open)
        val s = LookController.state.value
        if (s.open) {
            if (s.framesSent > 0) everSent = true
            if (LookController.motionProbe(context) == LookRules.Motion.MOVING) {
                LookController.close(LookRules.REFUSED_MOVING)
            } else if (everSent && !open) {
                LookController.close("The conversation ended, so the assistant can no longer see.")
            }
        }
    }
}

@Composable
private fun LookPanel(status: String, notice: String?, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = PANEL_ALPHA))
            .navigationBarsPadding()
            .padding(16.dp),
    ) {
        Text(status, style = MaterialTheme.typography.bodyLarge, color = Color.White)
        notice?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = NOTICE_AMBER)
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Stop showing") }
    }
}

/** The status line, in words. Says the assistant can see only after a frame went out. */
internal fun statusLine(
    state: LookController.State,
    permission: Boolean,
    asked: Boolean,
    cameraLive: Boolean,
    conversationOpen: Boolean,
): String = when {
    !permission && asked -> "No camera permission. Nothing was sent."
    !permission -> "Waiting for camera permission. Nothing sent yet."
    !state.open -> "Closed. The assistant cannot see."
    !cameraLive -> "Starting the camera. Nothing sent yet."
    !conversationOpen -> "Connecting to the assistant. Nothing sent yet."
    state.framesSent == 0 -> "Camera on. No picture has reached the assistant yet."
    else -> "The assistant can see this."
}

private const val WATCH_MS = 2_000L
private const val PANEL_ALPHA = 0.6f
private const val HINT_ALPHA = 0.8f
private const val NOTICE_AMBER_ARGB = 0xFFFFD27A
private val NOTICE_AMBER = Color(NOTICE_AMBER_ARGB)
