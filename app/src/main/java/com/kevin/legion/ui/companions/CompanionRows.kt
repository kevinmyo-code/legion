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
import androidx.compose.material3.RadioButton
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
import com.kevin.legion.ai.BUILT_IN_PERSONAS
import com.kevin.legion.ai.CURATED_VOICES
import com.kevin.legion.ai.Persona
import com.kevin.legion.ai.personaFor
import com.kevin.legion.data.local.CompanionProfileEntity
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics

/**
 * Companion-picker-specific rows and dialogs for `ui/CompanionsScreen.kt`. The
 * shared, aspect-agnostic furniture ([com.kevin.legion.ui.common.SectionHeader],
 * [com.kevin.legion.ui.common.Hairline]) lives in `ui/common/CommonRows.kt` and
 * IS reused by the screen directly - what lives here is a genuinely different
 * shape: a roster row needs a tap-to-switch target plus two independent
 * actions (EDIT/DELETE) rather than [com.kevin.legion.ui.common.ReadingRow]'s
 * plain label/value, the same way ledger's own [com.kevin.legion.ui.ledger.QuarantineRow]
 * earned its own row rather than reusing `ReadingRow`.
 */

// ------------------------------------------------------------------- roster row

/**
 * One roster row. **"Active on this phone" is spelled out, not a checkmark or
 * a colour** (task wording) - the roster SYNCS across both of Kevin's and his
 * wife's phones, but the active selection is device-local
 * ([com.kevin.legion.ai.ActiveCompanionProfile]'s doc comment), so a bare dot
 * or tint risks reading as "this is the profile" rather than "this is the one
 * THIS PHONE currently uses". Tapping a non-active row switches to it; tapping
 * the already-active row does nothing (there is nothing to switch to).
 */
@Composable
fun CompanionRow(
    profile: CompanionProfileEntity,
    isActive: Boolean,
    canDelete: Boolean,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    val persona = personaFor(profile.persona)
    val voice = CURATED_VOICES.firstOrNull { it.name == profile.voice }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !isActive, onClick = onActivate)
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
            if (isActive) {
                Spacer(Modifier.padding(top = 2.dp))
                Text("ACTIVE ON THIS PHONE", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
            }
        }
        TextButton(onClick = onEdit) {
            Text("EDIT", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
        }
        // Disabled rather than hidden when it's the last profile - a user
        // who taps a disabled DELETE at least learns why, instead of silently
        // wondering where the button went.
        TextButton(onClick = onDelete, enabled = canDelete) {
            Text("DELETE", style = LegionType.stamp, color = if (canDelete) sem.quarantined else sem.ghost)
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
    val personaKey: String,
    val voice: String,
)

/**
 * The create/edit form. Persona and voice are independent pickers (task
 * wording: rename, change voice, change persona are each their own edit) -
 * changing persona in EDIT mode never touches the typed name or chosen voice.
 *
 * **In CREATE mode only**, picking a persona re-defaults the name and voice to
 * that [Persona]'s [Persona.defaultName]/[Persona.suggestedVoice], but ONLY
 * while the user hasn't touched either field yet ([nameTouched]/
 * [voiceTouched]) - so tapping through personas before typing anything always
 * shows a sensible starting point, but a user who already typed a custom
 * name and then changes their mind on persona doesn't lose it.
 */
@Composable
fun CompanionEditorDialog(
    editing: CompanionEditorState,
    onDismiss: () -> Unit,
    onSave: (CompanionEditorState) -> Unit,
) {
    val isCreate = editing.profileId == null
    var name by remember(editing.profileId) { mutableStateOf(editing.name) }
    var personaKey by remember(editing.profileId) { mutableStateOf(editing.personaKey) }
    var voice by remember(editing.profileId) { mutableStateOf(editing.voice) }
    var nameTouched by remember(editing.profileId) { mutableStateOf(false) }
    var voiceTouched by remember(editing.profileId) { mutableStateOf(false) }
    var voiceMenuExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isCreate) "New companion" else "Edit companion") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; nameTouched = true },
                    label = { Text("Name") },
                )
                Spacer(Modifier.padding(top = 12.dp))
                Text("Persona", style = MaterialTheme.typography.titleMedium)
                BUILT_IN_PERSONAS.forEach { persona ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                personaKey = persona.key
                                if (isCreate && !nameTouched) name = persona.defaultName
                                if (isCreate && !voiceTouched) voice = persona.suggestedVoice
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = personaKey == persona.key, onClick = {
                            personaKey = persona.key
                            if (isCreate && !nameTouched) name = persona.defaultName
                            if (isCreate && !voiceTouched) voice = persona.suggestedVoice
                        })
                        Column {
                            Text(persona.defaultName, style = MaterialTheme.typography.bodyMedium)
                            Text(persona.blurb, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
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
                                    voiceTouched = true
                                    voiceMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(editing.copy(name = name, personaKey = personaKey, voice = voice)) },
                enabled = name.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Confirms a delete before it fires - destructive, and it removes the row from every synced device. */
@Composable
fun DeleteCompanionDialog(profile: CompanionProfileEntity, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${profile.assistantName}?") },
        text = { Text("This removes the profile everywhere it syncs, not just on this phone.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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

@Preview(name = "Companion row: active", widthDp = 360)
@Composable
private fun PreviewCompanionRowActive() = LegionTheme {
    Surface { CompanionRow(previewAlfredProfile, isActive = true, canDelete = true, onActivate = {}, onEdit = {}, onDelete = {}) }
}

@Preview(name = "Companion row: inactive, last profile (delete disabled)", widthDp = 360)
@Composable
private fun PreviewCompanionRowInactiveLast() = LegionTheme {
    Surface {
        CompanionRow(
            previewAlfredProfile.copy(assistantName = "Dorothy", persona = "dorothy", voice = "Vindemiatrix"),
            isActive = false,
            canDelete = false,
            onActivate = {}, onEdit = {}, onDelete = {},
        )
    }
}

@Preview(name = "New companion dialog", widthDp = 360, heightDp = 640)
@Composable
private fun PreviewCompanionEditorCreate() = LegionTheme {
    CompanionEditorDialog(
        editing = CompanionEditorState(profileId = null, name = "Alfred", personaKey = "alfred", voice = "Charon"),
        onDismiss = {}, onSave = {},
    )
}

@Preview(name = "Delete confirmation", widthDp = 360)
@Composable
private fun PreviewDeleteDialog() = LegionTheme {
    DeleteCompanionDialog(previewAlfredProfile, onConfirm = {}, onDismiss = {})
}
