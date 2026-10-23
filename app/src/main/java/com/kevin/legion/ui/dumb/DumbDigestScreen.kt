package com.kevin.legion.ui.dumb

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kevin.legion.dumb.DigestRowUi
import com.kevin.legion.dumb.DigestUi
import com.kevin.legion.dumb.DumbDigestViewModel

/**
 * "Held while you were away" (prototype `Digest.dc.html`, ADR 0062), shown when dumb mode ends and
 * anything was held. App name and count only: the notifications' words were never kept, so there is
 * no gist line. Tapping an app opens it (dumb mode is off now, so nothing bounces it).
 */
@Composable
fun DumbDigest() {
    val context = LocalContext.current
    val viewModel: DumbDigestViewModel = viewModel(factory = DumbDigestViewModel.Factory(context))
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    BackHandler(onBack = viewModel::dismiss)
    DumbDigestContent(
        ui = ui,
        onOpen = { row ->
            val intent = context.packageManager.getLaunchIntentForPackage(row.packageName)
            if (intent != null) {
                runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                viewModel.opened(row.packageName)
            }
        },
        onClearAll = viewModel::clearAll,
        onBackToDumb = viewModel::backToDumb,
    )
}

@Composable
fun DumbDigestContent(
    ui: DigestUi,
    onOpen: (DigestRowUi) -> Unit,
    onClearAll: () -> Unit,
    onBackToDumb: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(DumbColors.ground)
            .padding(start = 24.dp, end = 24.dp, top = 56.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.lastRun.isNotEmpty()) {
                Text(ui.lastRun, color = DumbColors.dim, fontSize = 13.sp, fontFamily = DumbMono)
            }
            Text("Held while you were away", color = DumbColors.text, fontSize = 28.sp, fontWeight = FontWeight.Medium)
            Text(
                "One summary instead of a flood. Tap an app to open it.",
                color = DumbColors.body,
                fontSize = 15.sp,
                lineHeight = 22.sp,
            )
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ui.rows.forEach { row -> DigestRow(row) { onOpen(row) } }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Clear all", onClearAll, primary = false, modifier = Modifier.fillMaxWidth())
            PillButton("Go back to matrix mode", onBackToDumb, primary = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun DigestRow(row: DigestRowUi, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(DumbColors.field)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.appName, color = DumbColors.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Text(row.count.toString(), color = DumbColors.body, fontSize = 15.sp, fontFamily = DumbMono)
    }
}
