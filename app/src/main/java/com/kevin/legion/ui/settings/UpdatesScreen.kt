package com.kevin.legion.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kevin.legion.ui.SettingsNavRow
import com.kevin.legion.ui.VoiceTurnRow
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics

/**
 * Settings, Updates (alfred-ota ticket 06, ADR 0072): the hands path of the `app_update` voice
 * tool. Check now and Install call the same [com.kevin.legion.update.UpdateController] the tool
 * does (ADR 0035). Install is Kevin's tap, the consent decision 3 asks for every time.
 */
@Composable
fun UpdatesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: UpdatesViewModel = viewModel(factory = UpdatesViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshGrants() }
    UpdatesContent(
        state = state,
        onBack = onBack,
        onCheck = viewModel::checkNow,
        onInstall = viewModel::install,
        onAllowMobileData = viewModel::setAllowMobileData,
        onAllowInstalls = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        },
    )
}

/** Stateless, so it renders with any [UpdatesUiState]. */
@Composable
fun UpdatesContent(
    state: UpdatesUiState,
    onBack: () -> Unit,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
    onAllowMobileData: (Boolean) -> Unit,
    onAllowInstalls: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DeckScreenHeader(title = "Updates", onBack = onBack)
            Column(
                Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(state.installed, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(state.status, style = MaterialTheme.typography.bodyMedium)
                state.notes?.let {
                    Spacer(Modifier.height(4.dp))
                    Text("What it changes: $it", style = LegionType.stamp, color = sem.faint)
                }
                state.note?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    DeckButton(text = "Check now", onClick = onCheck, enabled = !state.working)
                    Spacer(Modifier.width(8.dp))
                    DeckButton(text = "Install", onClick = onInstall, enabled = state.canInstall)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Install asks nothing more: tapping it is your yes. LEGION closes for about a minute and " +
                        "comes back on its own. Never during a conversation, in Matrix mode or with the car connected.",
                    style = LegionType.stamp,
                    color = sem.faint,
                )

                Spacer(Modifier.height(16.dp))
                VoiceTurnRow(
                    title = "Allow mobile data",
                    explanation = "an update is about 200 MB, so it downloads on Wi-Fi only unless this is on.",
                    enabled = state.allowMobileData,
                    onToggle = onAllowMobileData,
                )
                Spacer(Modifier.height(8.dp))
                SettingsNavRow(
                    label = "Allow installs",
                    status = if (state.mayInstallPackages) {
                        "Allowed: LEGION may install its own updates."
                    } else {
                        "Not allowed yet: tap, then switch on \"Allow from this source\". Needed once."
                    },
                    onClick = onAllowInstalls,
                    attention = !state.mayInstallPackages,
                )

                Spacer(Modifier.height(16.dp))
                Text(state.packLine, style = LegionType.stamp, color = sem.faint)
            }
        }
    }
}
