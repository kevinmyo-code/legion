package com.kevin.legion.ui.claudesession

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kevin.legion.claude.ClaudeFileIntents
import com.kevin.legion.claude.ClaudeFileStore
import com.kevin.legion.claude.ClaudeWording
import com.kevin.legion.claude.LocalClaudeFile
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The files a Claude task brought to this phone (files to phone, ADR 0080), opened from the result
 * (straight away when LEGION is in front), the notification tap, or the Claude session screen's
 * Open files button. Images show here; a PDF or document opens through ACTION_VIEW. Every file has
 * Share, Android's own share sheet: Kevin sends it to WhatsApp or anywhere else with his own tap.
 * Nothing here sends anything by itself.
 *
 * Not exported: it carries only a task id, and only LEGION's own intents reach it. It reads the
 * app-private copies [ClaudeFileStore] keeps; it never touches the engine.
 */
class ClaudeFileActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val taskId = intent.getIntExtra(EXTRA_TASK_ID, 0)
        if (taskId <= 0) {
            finish()
            return
        }
        val files = ClaudeFileStore(this).list(taskId)
        // One document and nothing else: open it in its reader at once, this screen stays behind it
        // with Share. Not again on a rotation or a return.
        val single = files.singleOrNull()
        if (savedInstanceState == null && single != null && !single.isImage) launch(ClaudeFileIntents.view(this, single))
        setContent {
            SoftTheme {
                ClaudeFilesContent(
                    files = files,
                    onOpen = { launch(ClaudeFileIntents.view(this, it)) },
                    onShare = { launch(ClaudeFileIntents.share(this, it)) },
                    onClose = ::finish,
                )
            }
        }
    }

    private fun launch(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, NO_APP, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val EXTRA_TASK_ID = "claude_task_id"
        private const val REQUEST_BASE = 0x4C46_0000
        const val NO_APP = "No app on this phone opens that kind of file. Share it instead."

        fun intent(context: Context, taskId: Int): Intent =
            Intent(context, ClaudeFileActivity::class.java)
                .putExtra(EXTRA_TASK_ID, taskId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun pendingIntent(context: Context, taskId: Int): PendingIntent =
            PendingIntent.getActivity(
                context,
                REQUEST_BASE + taskId,
                intent(context, taskId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}

/** Stateless, so a test renders it with made-up files. */
@Composable
fun ClaudeFilesContent(
    files: List<LocalClaudeFile>,
    onOpen: (LocalClaudeFile) -> Unit,
    onShare: (List<LocalClaudeFile>) -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(SoftColors.ground).padding(16.dp)) {
        Text("From your Claude session", style = MaterialTheme.typography.titleLarge, color = SoftColors.text)
        if (files.isEmpty()) {
            Text(GONE, color = SoftColors.text, modifier = Modifier.padding(vertical = 12.dp).testTag(FILES_GONE_TAG))
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(files, key = { it.fileId }) { f -> FileCard(f, onOpen, onShare) }
            }
            if (files.size > 1) {
                Button(onClick = { onShare(files) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Share all ${files.size}")
                }
            }
        }
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Close") }
    }
}

@Composable
private fun FileCard(f: LocalClaudeFile, onOpen: (LocalClaudeFile) -> Unit, onShare: (List<LocalClaudeFile>) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag(FILE_ROW_TAG)) {
        if (f.isImage) Picture(f)
        Text(
            "${f.name} (${ClaudeWording.size(f.file.length())})",
            color = SoftColors.text,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onOpen(f) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (f.isImage) "Open with" else "Open")
            }
            Button(onClick = { onShare(listOf(f)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Share") }
        }
    }
}

@Composable
private fun Picture(f: LocalClaudeFile) {
    val bitmap by produceState<Bitmap?>(initialValue = null, f.file) {
        value = withContext(Dispatchers.IO) { decode(f) }
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = f.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
        )
    }
}

/** Scaled down to about [MAX_EDGE] px on its long side, so a 50 MP photo does not take the heap. */
private fun decode(f: LocalClaudeFile): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.file.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
    return BitmapFactory.decodeFile(f.file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

private const val MAX_EDGE = 2048
private const val GONE =
    "These files are not on this phone. The engine deletes its copy once a phone has them, and after " +
        "24 hours at the latest, so ask Claude for them again."
const val FILE_ROW_TAG = "claude-file-row"
const val FILES_GONE_TAG = "claude-files-gone"
