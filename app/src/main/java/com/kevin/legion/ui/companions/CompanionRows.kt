package com.kevin.legion.ui.companions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.ai.CURATED_VOICES
import com.kevin.legion.ai.GeminiVoice
import com.kevin.legion.ai.personaFor
import com.kevin.legion.data.local.CompanionProfileEntity
import com.kevin.legion.ui.theme.LegionSemantics
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics

/**
 * The row and the editor for `ui/CompanionsScreen.kt`, the assistant's own settings. The shared,
 * aspect-agnostic furniture ([com.kevin.legion.ui.common.Hairline]) lives in `ui/common/CommonRows.kt`
 * and IS reused by the screen directly - what lives here is the one row that needs an EDIT action
 * beside its label, rather than [com.kevin.legion.ui.common.ReadingRow]'s plain label/value.
 */

// --------------------------------------------------------------------- row

/**
 * The active profile: its name, the register's one-line description, and its voice, with EDIT.
 * There is one persona (ADR 0063), so there is nothing to tap to switch to and nothing to delete.
 */
@Composable
fun CompanionRow(
    profile: CompanionProfileEntity,
    onEdit: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    val persona = personaFor(profile.persona)
    val voice = CURATED_VOICES.firstOrNull { it.name == profile.voice }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(profile.assistantName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(persona.blurb, style = LegionType.stamp, color = sem.faint)
            Text(
                if (voice != null) "${voice.name} — ${voice.descriptor}" else profile.voice,
                style = LegionType.stamp,
                color = sem.faint,
            )
        }
        TextButton(onClick = onEdit) {
            Text("EDIT", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

// -------------------------------------------------------------------- editor

/**
 * One form for both create (`profileId == null`) and edit. Held by the
 * screen, not this file, so the screen's [androidx.compose.runtime.mutableStateOf]
 * survives dialog recomposition the same way `LedgerScreen`'s state does.
 */
data class CompanionEditorState(
    val profileId: String?,
    val name: String,
    val voice: String,
)

/**
 * The create/edit form: a name and a voice. There is no persona picker (one
 * persona, ADR 0063); the screen writes [com.kevin.legion.ai.ALFRED]'s key on
 * save. Create mode only happens on an install with no profile yet, and opens
 * on Alfred's defaults.
 *
 * **Each voice option in the dropdown carries an audition control** - a
 * play/stop glyph, backed by [rememberVoiceAuditionPlayer], that lets the
 * user hear the bundled clip for that voice before picking it. The player is
 * scoped to this dialog's composition, so it is released the moment the
 * dialog leaves the tree (cancel, save, or the screen navigating away) - see
 * `ui/companions/VoiceAudition.kt`.
 */
@Composable
fun CompanionEditorDialog(
    editing: CompanionEditorState,
    onDismiss: () -> Unit,
    onSave: (CompanionEditorState) -> Unit,
) {
    val sem = LocalLegionSemantics.current
    val isCreate = editing.profileId == null
    var name by remember(editing.profileId) { mutableStateOf(editing.name) }
    var voice by remember(editing.profileId) { mutableStateOf(editing.voice) }
    var voiceMenuExpanded by remember { mutableStateOf(false) }
    val auditionPlayer = rememberVoiceAuditionPlayer()
    val auditioning by auditionPlayer.playing

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isCreate) "Set up companion" else "Edit companion") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                )
                Spacer(Modifier.padding(top = 12.dp))
                Text("Voice", style = MaterialTheme.typography.titleMedium)
                val selectedVoiceLabel = CURATED_VOICES.firstOrNull { it.name == voice }
                    ?.let { "${it.name} — ${it.descriptor}" } ?: voice
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { voiceMenuExpanded = true }) { Text(selectedVoiceLabel) }
                    DropdownMenu(expanded = voiceMenuExpanded, onDismissRequest = { voiceMenuExpanded = false }) {
                        CURATED_VOICES.forEach { v ->
                            DropdownMenuItem(
                                text = { Text("${v.name} — ${v.descriptor}") },
                                onClick = {
                                    voice = v.name
                                    voiceMenuExpanded = false
                                },
                                // Its own clickable in trailingIcon's Text, distinct from
                                // the item Row's - a tap on the glyph is consumed there
                                // and never bubbles up into onClick, so auditioning a
                                // voice never also selects it.
                                trailingIcon = { VoiceAuditionGlyph(v, auditioning, auditionPlayer, sem) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(editing.copy(name = name, voice = voice)) },
                enabled = name.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * One voice's audition control, rendered in [CompanionEditorDialog]'s
 * dropdown as a [DropdownMenuItem] `trailingIcon`. Three states, text-coded
 * rather than colour-coded so playing state reads even without colour
 * (task requirement): no bundled clip ("—", disabled), idle ("PLAY"), and
 * this voice auditioning right now ("STOP"). [auditioning] is
 * [VoiceAuditionPlayer.playing]'s current value, hoisted by the caller so
 * every row in the menu recomposes from the same single source of truth
 * rather than each row polling the player itself.
 */
@Composable
private fun VoiceAuditionGlyph(
    voice: GeminiVoice,
    auditioning: String?,
    player: VoiceAuditionPlayer,
    sem: LegionSemantics,
) {
    val hasClip = player.hasClip(voice)
    val isPlaying = auditioning == voice.name
    Text(
        text = if (!hasClip) "—" else if (isPlaying) "STOP" else "PLAY",
        style = LegionType.stamp,
        color = when {
            !hasClip -> sem.ghost
            isPlaying -> MaterialTheme.colorScheme.primary
            else -> sem.faint
        },
        modifier = if (hasClip) Modifier.clickable { player.toggle(voice) }.padding(horizontal = 4.dp) else Modifier.padding(horizontal = 4.dp),
    )
}

// ------------------------------------------------------------------------ previews

private val previewAlfredProfile = CompanionProfileEntity(
    profileId = "p1",
    assistantName = "Alfred",
    persona = "alfred",
    traits = "",
    voice = "Charon",
    voiceStyle = "",
    voiceStyleTraits = "",
    updatedAt = System.currentTimeMillis(),
)

@Preview(name = "Companion row", widthDp = 360)
@Composable
private fun PreviewCompanionRow() = LegionTheme {
    Surface { CompanionRow(previewAlfredProfile, onEdit = {}) }
}

@Preview(name = "Edit companion dialog", widthDp = 360, heightDp = 640)
@Composable
private fun PreviewCompanionEditorEdit() = LegionTheme {
    CompanionEditorDialog(
        editing = CompanionEditorState(profileId = "p1", name = "Alfred", voice = "Charon"),
        onDismiss = {}, onSave = {},
    )
}
