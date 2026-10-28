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
import com.kevin.midnightai.diag.DriveLog
import com.kevin.midnightai.diag.DriveLogStatus
import com.kevin.midnightai.diag.DriveLogUploader
import com.kevin.midnightai.diag.UploadReason
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaDimens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Settings > System > Drive logs (2026-10-15): the hands path for the drive-log upload.
 *
 * The unit keeps its own rolling log and uploads it to the household engine after every drive
 * (`diag/DriveLogLoop`); this row says when that last happened and how it ended, and the button
 * runs the same upload on demand. It calls the SAME [DriveLogUploader] the service does, so the
 * button and the automatic trigger cannot disagree.
 *
 * No animation here at all (frame-clock rule), Aria tokens, a 56dp button.
 */
@Composable
fun DriveLogEntry(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status = remember { DriveLogStatus(context) }

    var lastAt by remember { mutableStateOf(status.lastAtMs()) }
    var lastOk by remember { mutableStateOf(status.lastOk()) }
    var lastSentence by remember { mutableStateOf(status.lastSentence()) }
    var waitingBytes by remember { mutableStateOf(DriveLog.store()?.totalBytes() ?: 0L) }
    var working by remember { mutableStateOf(false) }

    fun refresh() {
        lastAt = status.lastAtMs()
        lastOk = status.lastOk()
        lastSentence = status.lastSentence()
        waitingBytes = DriveLog.store()?.totalBytes() ?: 0L
    }

    LaunchedEffect(Unit) { refresh() }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = lastAt?.let { "Last upload attempt: ${formatTime(it)}" } ?: "No upload attempt yet.",
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
            text = "On this unit now: ${formatSize(waitingBytes)}.",
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
                    val uploader = DriveLogUploader.create(context)
                    if (uploader == null) {
                        status.record(System.currentTimeMillis(), ok = false, sentence = "The drive log is not running, so nothing was sent.")
                    } else {
                        uploader.upload(UploadReason.MANUAL)
                    }
                    // The conversation spool goes too, under its own reason; its result is kept
                    // in its own status so this row still shows the drive log's.
                    DriveLogUploader.createForConversation(context)?.upload(UploadReason.CONVERSATION)
                    refresh()
                    working = false
                }
            },
            enabled = !working,
            modifier = Modifier.heightIn(min = AriaDimens.driveTouchMin),
        ) { Text(if (working) "UPLOADING..." else "UPLOAD LOGS NOW") }
    }
}

private fun formatTime(ms: Long): String =
    SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(ms))

private fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "nothing waiting"
    bytes < 1024L -> "$bytes B waiting"
    bytes < 1024L * 1024L -> "${bytes / 1024} KB waiting"
    else -> "${"%.1f".format(Locale.US, bytes / (1024.0 * 1024.0))} MB waiting"
}
