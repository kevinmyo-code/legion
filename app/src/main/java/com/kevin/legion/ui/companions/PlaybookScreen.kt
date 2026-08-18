package com.kevin.legion.ui.companions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kevin.legion.advisor.PlaybookSaveResult
import com.kevin.legion.advisor.PlaybookStore
import com.kevin.legion.advisor.PrimingTopic
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.common.Hairline
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * `settings/playbooks` - the driver's own editor for [PrimingTopic]'s four bodies of doctrine
 * (2026-08-18). Every advisor exchange and every voice `ask_<domain>` dispatch is primed with
 * whatever [PlaybookStore.text] returns for its topic before it answers a word - this screen is
 * the only place that fact is visible and correctable, rather than being buried in a shipped
 * constant nobody but a rebuild could change.
 *
 * **List, then drill in - internal Compose state, not a nav destination.** Same shape
 * [com.kevin.legion.ui.NotesScreen] uses for its own list-to-editor step: [selected] holds which
 * [PrimingTopic] is open, and swapping it swaps the whole screen body between the row list and
 * [PlaybookEditor], with no second route in [com.kevin.legion.ui.LegionRoute]. A topic's doctrine
 * is a few hundred words at most, so a full nav push-and-pop for what is really "expand this row"
 * would be ceremony a nested state flip already gets for free.
 *
 * **Every [PlaybookStore] call is blocking file IO** (that object's own doc comment) and every
 * caller here wraps it in `Dispatchers.IO`, never runs it straight on the composition's default
 * dispatcher.
 */
@Composable
fun PlaybookScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // Keyed by topic rather than a plain Boolean map so a fresh read always reflects the file
    // system truth - bumped once on return from the editor (see [PlaybookEditor]'s own onBack),
    // never on every keystroke inside it.
    var customised by remember { mutableStateOf<Map<PrimingTopic, Boolean>>(emptyMap()) }
    var reloadNonce by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<PrimingTopic?>(null) }

    LaunchedEffect(reloadNonce) {
        customised = withContext(Dispatchers.IO) {
            PrimingTopic.entries.associateWith { PlaybookStore.isCustomised(context, it) }
        }
    }

    val topic = selected
    if (topic != null) {
        PlaybookEditor(
            topic = topic,
            onBack = {
                selected = null
                // A save or a revert just happened (or neither did, if the driver simply backed
                // out) - either way the EDITED marker on the list below can only be trusted after
                // a fresh disk read, not by guessing from what the editor did.
                reloadNonce++
            },
        )
    } else {
        val sem = LocalLegionSemantics.current
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                DeckScreenHeader(title = "Playbooks", onBack = onBack)
                Text(
                    "The assistant reads these before it answers - they're doctrine, not a record.",
                    style = LegionType.stamp,
                    color = sem.faint,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                LazyColumn(Modifier.fillMaxSize()) {
                    items(PrimingTopic.entries.toList(), key = { it.key }) { candidate ->
                        PlaybookRow(
                            topic = candidate,
                            edited = customised[candidate] == true,
                            onClick = { selected = candidate },
                        )
                        Hairline()
                    }
                }
            }
        }
    }
}

/** One topic row: title, blurb, an EDITED marker when [edited] is true, and a chevron - same
 * tappable-row shape as [com.kevin.legion.ui.SettingsNavRow], restated here rather than shared
 * because that composable is `internal` to `ui/SettingsRows.kt`'s package and carries an
 * `attention` boolean this row has no use for. */
@Composable
private fun PlaybookRow(topic: PrimingTopic, edited: Boolean, onClick: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        tonalElevation = 1.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(topic.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(topic.blurb, style = LegionType.stamp, color = sem.faint)
            }
            if (edited) {
                // ADVISORY (ticket 13's tiers, same posture SettingsNavRow's own doc comment
                // cites): a customised playbook is a fact worth flagging, not a fault.
                Text("EDITED", style = LegionType.stamp, color = sem.estimated, modifier = Modifier.padding(end = 8.dp))
            }
            Text(">", style = LegionType.stamp, color = sem.faint)
        }
    }
}

/**
 * The full-text editor for one [topic]. Loads [PlaybookStore.text] (the driver's edit if there is
 * one, else the shipped default - never blank, see that function's own doc comment) into local
 * Compose state on entry, and never touches disk again until Save or Revert is actually tapped.
 *
 * **Save never silently drops a keystroke.** [PlaybookStore.save] returns false on a genuine write
 * failure; [saveError] surfaces that in words and the driver's edit stays exactly as typed in
 * [text] rather than being discarded or replaced by a stale re-read. A successful save does not
 * navigate away on its own - the driver may want to keep editing - so [saved] is the only feedback,
 * cleared the moment the text changes again so it can never read as current when it is stale.
 *
 * **Revert is a two-tap confirm, inline, no system dialog** - same shape as
 * [com.kevin.legion.ui.PurgeLedgerRow]'s `armed` pattern: the first tap arms it, the button's own
 * label and [com.kevin.legion.ui.common.DeckButton]'s `confirming` fill say so, and a CANCEL
 * appears alongside it. Unlike Save, a successful revert DOES call [onBack] - there is nothing
 * left in this editor to keep looking at once the text reverts to the shipped default.
 */
@Composable
private fun PlaybookEditor(topic: PrimingTopic, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sem = LocalLegionSemantics.current
    var text by remember(topic) { mutableStateOf("") }
    var loaded by remember(topic) { mutableStateOf(false) }
    // The typed outcome of the last Save, or null when nothing has been attempted since the last
    // keystroke. A refusal (too long, a deleted referral boundary) is not a crash and not a
    // success - it has to be readable in words, which is why this holds the result rather than a
    // pair of booleans.
    var lastSave by remember(topic) { mutableStateOf<PlaybookSaveResult?>(null) }
    var revertArmed by remember(topic) { mutableStateOf(false) }

    LaunchedEffect(topic) {
        text = withContext(Dispatchers.IO) { PlaybookStore.text(context, topic) }
        loaded = true
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DeckScreenHeader(title = topic.title, onBack = onBack)
            Text(
                "Read by the assistant before it answers on this topic - doctrine, not a record.",
                style = LegionType.stamp,
                color = sem.faint,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            if (!loaded) {
                Text("Loading...", style = LegionType.stamp, color = sem.ghost, modifier = Modifier.padding(12.dp))
            } else {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        lastSave = null
                    },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp),
                    label = { Text(topic.title) },
                )
                when (val result = lastSave) {
                    null -> Unit
                    // ADVISORY tier, pointed at the driver's own typing rather than a background
                    // gate - in every branch below the text above is unchanged and safe to retry.
                    is PlaybookSaveResult.WriteFailed -> SaveNote(
                        "Save failed - your text is unchanged, try again.", sem.estimated,
                    )
                    is PlaybookSaveResult.TooLong -> SaveNote(
                        "Too long to save: ${result.actualChars} characters against a limit of " +
                            "${result.maxChars}. This text is sent on every question about this " +
                            "topic, so the limit is what it costs you to ask.",
                        sem.estimated,
                    )
                    is PlaybookSaveResult.MissingBoundaries -> SaveNote(
                        "Not saved. This doctrine has to keep the lines that send you to a " +
                            "professional. Missing: ${result.missing.joinToString(", ")}.",
                        sem.estimated,
                    )
                    is PlaybookSaveResult.Saved -> SaveNote("Saved.", sem.faint)
                    is PlaybookSaveResult.RevertedToDefault -> SaveNote(
                        "Back to the shipped playbook - your copy was blank or identical to it.",
                        sem.faint,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DeckButton(
                        text = "Save",
                        onClick = {
                            scope.launch {
                                lastSave = withContext(Dispatchers.IO) {
                                    PlaybookStore.save(context, topic, text)
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    DeckButton(
                        text = if (revertArmed) "CONFIRM REVERT" else "Revert to default",
                        onClick = {
                            if (revertArmed) {
                                scope.launch {
                                    withContext(Dispatchers.IO) { PlaybookStore.revertToDefault(context, topic) }
                                    onBack()
                                }
                            } else {
                                revertArmed = true
                            }
                        },
                        destructive = true,
                        confirming = revertArmed,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (revertArmed) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        DeckButton(text = "Cancel", onClick = { revertArmed = false }, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * One line of feedback under the editor - what the last Save did, in words. Its own composable so
 * every outcome renders identically and no branch can quietly render nothing, which is how a
 * refused save would come to look like a successful one.
 */
@Composable
private fun SaveNote(message: String, color: Color) {
    Text(
        message,
        style = LegionType.stamp,
        color = color,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )
}
