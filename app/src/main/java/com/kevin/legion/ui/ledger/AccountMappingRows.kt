package com.kevin.legion.ui.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import com.kevin.legion.ledger.DiscoveredAccountFolder
import com.kevin.legion.ui.common.SectionHeader
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics

/**
 * The minimum UI the account-mapping ticket asks for: "a list of folder
 * names each with an account selector, not a wizard." Only rendered while a
 * folder is connected and at least one subfolder was found directly under
 * its root - a flat connected folder with no subfolders renders nothing
 * here, matching [com.kevin.legion.ui.common.NotBuiltRow]'s "say plainly
 * what applies" posture rather than showing an empty section.
 *
 * [knownAccountIds] are account ids already seen from a PARSED PDF (via
 * [com.kevin.legion.ledger.LedgerController.accountBalances], threaded down
 * from `LedgerScreen`'s balances load) - offered as one-tap chips so mapping
 * a CSV's folder to the SAME account a PDF already uses is a tap, not a
 * typo-prone retype of a printed account number that a mismatch (see
 * [com.kevin.legion.ledger.parsers.StatementDispatcher]'s `accountConflict`
 * check) would then quarantine.
 */
@Composable
fun AccountMappingSection(
    folders: List<DiscoveredAccountFolder>,
    mapping: Map<String, String>,
    knownAccountIds: List<String>,
    onAssign: (folderId: String, accountId: String?) -> Unit,
) {
    if (folders.isEmpty()) return
    val sem = LocalLegionSemantics.current
    Column(Modifier.fillMaxWidth()) {
        SectionHeader("ACCOUNT FOLDERS")
        Text(
            "A file that doesn't state its own account (a CSV export) takes the account mapped to " +
                "the folder it's in. A file's own printed account always wins over this.",
            style = MaterialTheme.typography.bodySmall,
            color = sem.faint,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        )
        folders.forEach { folder ->
            AccountFolderRow(
                folder = folder,
                mappedAccountId = mapping[folder.folderId],
                knownAccountIds = knownAccountIds,
                onAssign = { accountId -> onAssign(folder.folderId, accountId) },
            )
        }
    }
}

@Composable
private fun AccountFolderRow(
    folder: DiscoveredAccountFolder,
    mappedAccountId: String?,
    knownAccountIds: List<String>,
    onAssign: (String?) -> Unit,
) {
    val sem = LocalLegionSemantics.current
    // Local-only "type a new account id" affordance - never persisted itself,
    // onAssign(typed) is what actually writes it via LedgerAccountMappingPreferences.
    var editing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(folder.displayName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    mappedAccountId ?: "Not mapped",
                    style = LegionType.stamp,
                    // Unmapped reads as ADVISORY, not ALARM (ticket 13 re-home): it is a blocked
                    // capability today - a CSV in this folder will quarantine on import until this
                    // is set, per StatementDispatcher's UnmappedAccountException - but the mapping
                    // itself has not failed anything yet.
                    color = if (mappedAccountId == null) sem.estimated else sem.faint,
                )
            }
            if (mappedAccountId != null) {
                TextButton(onClick = { onAssign(null) }) {
                    Text("CLEAR", style = LegionType.stamp, color = sem.faint)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        if (editing) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Account id", style = LegionType.stamp) },
                )
                TextButton(onClick = {
                    if (typed.isNotBlank()) onAssign(typed.trim())
                    editing = false
                    typed = ""
                }) { Text("SET", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary) }
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(knownAccountIds) { accountId ->
                    TextButton(onClick = { onAssign(accountId) }) {
                        Text(accountId, style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                    }
                }
                item {
                    TextButton(onClick = { editing = true }) {
                        Text("+ NEW", style = LegionType.stamp, color = sem.faint)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------ previews

@Preview(name = "Account folders: one mapped, one not", widthDp = 360)
@Composable
private fun PreviewAccountMappingSection() = LegionTheme {
    Surface {
        AccountMappingSection(
            folders = listOf(
                DiscoveredAccountFolder("f1", "checking"),
                DiscoveredAccountFolder("f2", "credit"),
            ),
            mapping = mapOf("f1" to "BOFA ****4471"),
            knownAccountIds = listOf("BOFA ****4471", "DBS ****8802"),
            onAssign = { _, _ -> },
        )
    }
}

@Preview(name = "Account folders: none discovered", widthDp = 360)
@Composable
private fun PreviewAccountMappingSectionEmpty() = LegionTheme {
    Surface {
        AccountMappingSection(
            folders = emptyList(),
            mapping = emptyMap(),
            knownAccountIds = emptyList(),
            onAssign = { _, _ -> },
        )
    }
}
