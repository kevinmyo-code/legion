package com.kevin.legion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.kevin.legion.ai.ActiveCompanionProfile
import com.kevin.legion.ai.BUILT_IN_PERSONAS
import com.kevin.legion.ai.CompanionProfileStore
import com.kevin.legion.data.local.CompanionProfileEntity
import com.kevin.legion.ui.common.Hairline
import com.kevin.legion.ui.common.SectionHeader
import com.kevin.legion.ui.companions.CompanionEditorDialog
import com.kevin.legion.ui.companions.CompanionEditorState
import com.kevin.legion.ui.companions.CompanionRow
import com.kevin.legion.ui.companions.DeleteCompanionDialog
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * `settings/companions` - the companion profile picker (Part 2 of the
 * multi-companion feature; Part 1 built the data model/sync, Part 3 wrote
 * Alfred/Dorothy's copy in [com.kevin.legion.ai.Personas]). Reached from
 * [SettingsScreen]'s "Companions" button, not a bottom-nav tab - same shape as
 * `settings/key`.
 *
 * **Shows the roster, marks the ACTIVE one as device-local** (task wording):
 * the roster syncs to every device sharing the Google account, but which
 * profile is active does not ([ActiveCompanionProfile]'s doc comment), so a
 * user seeing their partner's profile in the list must not read the app as
 * confused about who it's talking to. [com.kevin.legion.ui.companions.CompanionRow]
 * spells out "ACTIVE ON THIS PHONE" rather than a bare marker for exactly that
 * reason.
 *
 * **Tap-to-switch is two calls, in order** (task wording, and
 * [ActiveCompanionProfile.setActiveProfileId]'s own doc comment): write
 * [ActiveCompanionProfile] first, then [CompanionProfileStore.materializeActive] -
 * both live inside [CompanionProfileStore.switchActive] so this screen only
 * ever calls the one function.
 *
 * Split per `compose-state-holder-ui-split`: [CompanionsScreen] is the state
 * holder (owns the roster load, the two dialogs' open/closed state, and every
 * write), [CompanionsContent] is plain UI plus callbacks and is what the
 * `@Preview`s below exercise.
 */
data class CompanionsUiState(
    val loading: Boolean = true,
    val roster: List<CompanionProfileEntity> = emptyList(),
    val activeProfileId: String? = null,
)

@Composable
fun CompanionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(CompanionsUiState()) }
    // Bumped after any write (create/edit/delete/switch) commits, to key the
    // reload below - the same shape as LedgerScreen's reloadNonce.
    var reloadNonce by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<CompanionEditorState?>(null) }
    var pendingDelete by remember { mutableStateOf<CompanionProfileEntity?>(null) }

    LaunchedEffect(reloadNonce) {
        state = CompanionsUiState(
            loading = false,
            roster = CompanionProfileStore.roster(context),
            activeProfileId = ActiveCompanionProfile.activeProfileId(context),
        )
    }

    CompanionsContent(
        state = state,
        onBack = onBack,
        onActivate = { profileId ->
            scope.launch {
                CompanionProfileStore.switchActive(context, profileId)
                reloadNonce++
            }
        },
        onCreate = {
            // Defaults to the first built-in persona; the dialog itself keeps
            // name/voice synced to whichever persona is picked until the
            // user types/picks their own - see CompanionEditorDialog's doc.
            val first = BUILT_IN_PERSONAS.first()
            editing = CompanionEditorState(
                profileId = null,
                name = first.defaultName,
                personaKey = first.key,
                voice = first.suggestedVoice,
            )
        },
        onEdit = { profile ->
            editing = CompanionEditorState(
                profileId = profile.profileId,
                name = profile.assistantName,
                personaKey = profile.persona,
                voice = profile.voice,
            )
        },
        onRequestDelete = { profile -> pendingDelete = profile },
    )

    editing?.let { form ->
        CompanionEditorDialog(
            editing = form,
            onDismiss = { editing = null },
            onSave = { saved ->
                scope.launch {
                    val existing = state.roster.firstOrNull { it.profileId == saved.profileId }
                    val entity = CompanionProfileEntity(
                        profileId = saved.profileId ?: UUID.randomUUID().toString(),
                        assistantName = saved.name.trim(),
                        persona = saved.personaKey,
                        // The picker built-ins have no PersonaSelection traits to carry
                        // (task scope: no questionnaire wiring) - blank, same as every
                        // other field the picker doesn't touch.
                        traits = existing?.traits.orEmpty(),
                        voice = saved.voice,
                        voiceStyle = existing?.voiceStyle.orEmpty(),
                        voiceStyleTraits = existing?.voiceStyleTraits.orEmpty(),
                        // Bumped to now on every save, create or edit - the sync clock
                        // this task's spec calls out: an edit that doesn't bump it
                        // silently loses to the other device's copy of the row.
                        updatedAt = System.currentTimeMillis(),
                    )
                    CompanionProfileStore.saveProfile(context, entity)
                    editing = null
                    reloadNonce++
                }
            },
        )
    }

    pendingDelete?.let { toDelete ->
        DeleteCompanionDialog(
            profile = toDelete,
            onConfirm = {
                scope.launch {
                    CompanionProfileStore.deleteProfile(context, toDelete.profileId)
                    pendingDelete = null
                    reloadNonce++
                }
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/** Plain UI: [state] plus callbacks, no [CompanionProfileStore]/Room reference - see the file doc comment. */
@Composable
fun CompanionsContent(
    state: CompanionsUiState,
    onBack: () -> Unit,
    onActivate: (profileId: String) -> Unit,
    onCreate: () -> Unit,
    onEdit: (CompanionProfileEntity) -> Unit,
    onRequestDelete: (CompanionProfileEntity) -> Unit,
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
                Text("COMPANIONS", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                TextButton(onClick = onCreate) {
                    Text("NEW", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                }
            }
            Hairline()

            when {
                state.loading -> Text(
                    "Loading...",
                    style = LegionType.stamp,
                    color = sem.ghost,
                    modifier = Modifier.padding(12.dp),
                )
                else -> {
                    SectionHeader("PROFILES", state.roster.size.toString())
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(state.roster, key = { it.profileId }) { profile ->
                            CompanionRow(
                                profile = profile,
                                isActive = profile.profileId == state.activeProfileId,
                                canDelete = state.roster.size > 1,
                                onActivate = { onActivate(profile.profileId) },
                                onEdit = { onEdit(profile) },
                                onDelete = { onRequestDelete(profile) },
                            )
                            Hairline()
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------ previews

private val previewRoster = listOf(
    CompanionProfileEntity(
        profileId = "p1", assistantName = "Alfred", persona = "alfred", traits = "",
        voice = "Charon", voiceStyle = "", voiceStyleTraits = "", updatedAt = 2_000L,
    ),
    CompanionProfileEntity(
        profileId = "p2", assistantName = "Dorothy", persona = "dorothy", traits = "",
        voice = "Vindemiatrix", voiceStyle = "", voiceStyleTraits = "", updatedAt = 1_000L,
    ),
)

@Preview(name = "Companions: loading", widthDp = 360, heightDp = 640)
@Composable
private fun PreviewCompanionsLoading() = LegionTheme {
    CompanionsContent(
        CompanionsUiState(loading = true),
        onBack = {}, onActivate = {}, onCreate = {}, onEdit = {}, onRequestDelete = {},
    )
}

@Preview(name = "Companions: two-profile roster", widthDp = 360, heightDp = 640)
@Composable
private fun PreviewCompanionsPopulated() = LegionTheme {
    CompanionsContent(
        CompanionsUiState(loading = false, roster = previewRoster, activeProfileId = "p1"),
        onBack = {}, onActivate = {}, onCreate = {}, onEdit = {}, onRequestDelete = {},
    )
}
