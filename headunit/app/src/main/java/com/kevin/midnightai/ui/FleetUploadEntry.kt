package com.kevin.midnightai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.kevin.midnightai.fleetupload.FleetPending
import com.kevin.midnightai.fleetupload.FleetUpload
import com.kevin.midnightai.fleetupload.FleetUploadReason
import com.kevin.midnightai.fleetupload.FleetUploadStatus
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaDimens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Settings > System > Drive logs > Car data to engine (2026-10-17): the hands path for the
 * OBD/DTC upload. It calls the SAME [com.kevin.midnightai.fleetupload.FleetUploader] the service
 * does, so the button and the automatic triggers cannot disagree. Last attempt time, what is
 * still waiting, and the last result in words (errors in red). No animation (frame-clock rule).
 */
@Composable
fun FleetUploadEntry(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status = remember { FleetUploadStatus(context) }

    var lastAt by remember { mutableStateOf(status.lastAtMs()) }
    var lastOk by remember { mutableStateOf(status.lastOk()) }
    var lastSentence by remember { mutableStateOf(status.lastSentence()) }
    var pending by remember { mutableStateOf<FleetPending?>(null) }
    var working by remember { mutableStateOf(false) }

    suspend fun refresh() {
        lastAt = status.lastAtMs()
        lastOk = status.lastOk()
        lastSentence = status.lastSentence()
        pending = runCatching { FleetUpload.create(context).pending() }.getOrNull()
    }

    LaunchedEffect(Unit) { refresh() }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = lastAt?.let { "Last car data attempt: ${formatFleetTime(it)}" } ?: "No car data upload attempt yet.",
            style = MaterialTheme.typography.bodySmall,
            color = AriaColors.TextDim,
        )
        lastSentence?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = if (lastOk) AriaColors.NeonCyan else AriaColors.ScanRed,
                modifier = Modifier.padding(top = AriaDimens.s1),
            )
        }
        Text(
            text = pending?.let { "Waiting on this unit: ${it.samples} OBD samples, ${it.codeEvents} code events." }
                ?: "Waiting on this unit: could not be counted.",
            style = MaterialTheme.typography.bodySmall,
            color = AriaColors.TextDim,
            modifier = Modifier.padding(top = AriaDimens.s1),
        )
        Spacer(Modifier.height(AriaDimens.s3))
        Button(
            onClick = {
                if (working) return@Button
                working = true
                scope.launch {
                    runCatching { FleetUpload.create(context).upload(FleetUploadReason.MANUAL) }
                    refresh()
                    working = false
                }
            },
            enabled = !working,
            modifier = Modifier.heightIn(min = AriaDimens.driveTouchMin),
        ) { Text(if (working) "UPLOADING..." else "UPLOAD CAR DATA NOW") }
    }
}

private fun formatFleetTime(ms: Long): String =
    SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(ms))
