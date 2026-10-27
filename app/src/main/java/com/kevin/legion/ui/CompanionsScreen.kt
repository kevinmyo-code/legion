package com.kevin.legion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.ai.ALFRED
import com.kevin.legion.ai.CompanionProfileStore
import com.kevin.legion.data.local.CompanionProfileEntity
import com.kevin.legion.ui.common.Hairline
import com.kevin.legion.ui.companions.CompanionEditorDialog
import com.kevin.legion.ui.companions.CompanionEditorState
import com.kevin.legion.ui.companions.CompanionRow
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * `settings/companions` - the assistant's own settings: its name and its voice. Reached from
 * [com.kevin.legion.ui.settings.AssistantSettingsScreen], not a bottom-nav tab - same shape as
 * `settings/key`.
 *
 * **One persona, no roster (Kevin, 2026-10-27, ADR 0063).** The phone has exactly one register,
 * [ALFRED], so this screen edits the device's ACTIVE profile and nothing else: no picker, no
 * tap-to-switch, no create or delete. A save always writes `persona = ALFRED.key`, so a row left
 * over from a retired persona becomes an Alfred row the first time anyone edits it here.
 *
 * **A fresh install has no profile yet** ([CompanionProfileStore.ensureSeeded] seeds only a legacy
 * identity). EDIT then opens the editor on Alfred's defaults and the save creates the row;
 * [CompanionProfileStore.saveProfile] adopts it as active because nothing else is.
 *
 * Split per `compose-state-holder-ui-split`: [CompanionsScreen] is the state holder (owns the load,
 * the editor's open/closed state, and the write), [CompanionsContent] is plain UI plus callbacks and
 * is what the `@Preview`s below exercise.
 */
data class CompanionsUiState(
    val loading: Boolean = true,
    /** The device's active profile, or null on an install that has none yet. */
    val profile: CompanionProfileEntity? = null,
)

@Composable
fun CompanionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(CompanionsUiState()) }
    // Bumped after a save commits, to key the reload below - the same shape as LedgerScreen's
    // reloadNonce.
    var reloadNonce by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<CompanionEditorState?>(null) }

    LaunchedEffect(reloadNonce) {
        state = CompanionsUiState(loading = false, profile = CompanionProfileStore.activeProfile(context))
    }

    CompanionsContent(
        state = state,
        onBack = onBack,
        onEdit = {
            val profile = state.profile
            editing = CompanionEditorState(
                profileId = profile?.profileId,
                name = profile?.assistantName ?: ALFRED.defaultName,
                voice = profile?.voice ?: ALFRED.suggestedVoice,
            )
        },
    )

    editing?.let { form ->
        CompanionEditorDialog(
            editing = form,
            onDismiss = { editing = null },
            onSave = { saved ->
                scope.launch {
                    val existing = state.profile?.takeIf { it.profileId == saved.profileId }
                    val entity = CompanionProfileEntity(
                        profileId = saved.profileId ?: UUID.randomUUID().toString(),
                        assistantName = saved.name.trim(),
                        persona = ALFRED.key,
                        traits = existing?.traits.orEmpty(),
                        voice = saved.voice,
                        voiceStyle = existing?.voiceStyle.orEmpty(),
                        voiceStyleTraits = existing?.voiceStyleTraits.orEmpty(),
                        // Bumped to now on every save - the sync clock: an edit that doesn't bump
                        // it silently loses to the other device's copy of the row.
                        updatedAt = System.currentTimeMillis(),
                    )
                    CompanionProfileStore.saveProfile(context, entity)
                    editing = null
                    reloadNonce++
                }
            },
        )
    }
}

/** Plain UI: [state] plus callbacks, no [CompanionProfileStore]/Room reference - see the file doc comment. */
@Composable
fun CompanionsContent(
    state: CompanionsUiState,
    onBack: () -> Unit,
    onEdit: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) {
                    Text("< BACK", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    "COMPANION",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Hairline()

            val profile = state.profile
            when {
                state.loading -> Text(
                    "Loading...",
                    style = LegionType.stamp,
                    color = sem.ghost,
                    modifier = Modifier.padding(12.dp),
                )
                profile == null -> Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Not set up on this phone yet.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onEdit) {
                        Text("SET UP", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                    }
                }
                else -> {
                    CompanionRow(profile = profile, onEdit = onEdit)
                    Hairline()
                }
            }
        }
    }
}

// ------------------------------------------------------------------------ previews

private val previewProfile = CompanionProfileEntity(
    profileId = "p1", assistantName = "Alfred", persona = "alfred", traits = "",
    voice = "Charon", voiceStyle = "", voiceStyleTraits = "", updatedAt = 2_000L,
)

@Preview(name = "Companion: loading", widthDp = 360, heightDp = 640)
@Composable
private fun PreviewCompanionsLoading() = LegionTheme {
    CompanionsContent(CompanionsUiState(loading = true), onBack = {}, onEdit = {})
}

@Preview(name = "Companion: set up", widthDp = 360, heightDp = 640)
@Composable
private fun PreviewCompanionsPopulated() = LegionTheme {
    CompanionsContent(CompanionsUiState(loading = false, profile = previewProfile), onBack = {}, onEdit = {})
}

@Preview(name = "Companion: not set up yet", widthDp = 360, heightDp = 640)
@Composable
private fun PreviewCompanionsEmpty() = LegionTheme {
    CompanionsContent(CompanionsUiState(loading = false, profile = null), onBack = {}, onEdit = {})
}
