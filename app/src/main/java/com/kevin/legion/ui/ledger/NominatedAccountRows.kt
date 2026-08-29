package com.kevin.legion.ui.ledger

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.ledger.AccountBalance
import com.kevin.legion.ledger.maskedAccountLabel
import com.kevin.legion.ui.common.HelpRow
import com.kevin.legion.ui.common.SectionHeader
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics

/**
 * **Extracted from the now-deleted `ui/ledger/AccountMappingRows.kt`, backend-erp ticket 25**
 * ("statement ingestion leaves the phone entirely"). That file's other export,
 * `AccountMappingSection` (Drive-subfolder-to-account mapping), went with the rest of phone-side
 * statement ingestion - a folder mapping has no meaning once the phone never scans a folder. This
 * one survives unchanged: it is not part of ingestion at all, it is a plain account-picker over
 * whatever accounts already exist in `ledger_transactions`, whichever process put them there.
 *
 * The nomination picker (2026-08-18, Kevin): which ONE [AccountBalance.accountId] HOME's CRED
 * tile shows a balance for - see [com.kevin.legion.ledger.LedgerNominatedAccountPreferences]'s own
 * doc comment for why this is a driver-picked account rather than a guessed default.
 *
 * **Every account is shown WITH whether it prints a balance, never hidden** - a driver who
 * nominates an account that never prints one (Bank of America's card layout) is allowed to; the
 * point is he can't do it blind. The exact words match [com.kevin.legion.ui.ledger.LedgerRows]'
 * `BalancesSection` disclosure line, never a second phrasing for the same fact.
 */
@Composable
fun NominatedAccountSection(
    balances: List<AccountBalance>,
    nominatedAccountId: String?,
    onNominate: (accountId: String?) -> Unit,
) {
    if (balances.isEmpty()) return
    val sem = LocalLegionSemantics.current
    Column(Modifier.fillMaxWidth()) {
        SectionHeader("NOMINATED ACCOUNT")
        // This explains a RULE (why LEGION asks rather than guessing), it does not disclose the
        // trustworthiness of any figure, so it collapses. The per-account "no balance ever printed
        // for this account" line on each row below is the opposite case and stays permanently visible.
        HelpRow(
            "HOME's CRED tile shows this one account's balance - LEGION can't tell a cash account " +
                "from a card, so it never guesses which one.",
            label = "WHY NOMINATE ONE",
        )
        balances.forEach { balance ->
            NominatedAccountRow(
                balance = balance,
                isNominated = balance.accountId == nominatedAccountId,
                onNominate = { onNominate(balance.accountId) },
            )
        }
        if (nominatedAccountId != null) {
            TextButton(
                onClick = { onNominate(null) },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) { Text("CLEAR NOMINATION", style = LegionType.stamp, color = sem.faint) }
        }
    }
}

@Composable
private fun NominatedAccountRow(
    balance: AccountBalance,
    isNominated: Boolean,
    onNominate: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onNominate).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(maskedAccountLabel(balance.accountId), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            // Same exact words BalancesSection's own "as of" line uses for this state - see
            // NominatedAccountSection's own doc comment for why this is a reuse, not a second phrasing.
            Text(
                if (balance.balanceCents != null) "prints a balance" else "no balance ever printed for this account",
                style = LegionType.stamp,
                color = sem.faint,
            )
        }
        if (isNominated) {
            Text("NOMINATED", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
        } else {
            TextButton(onClick = onNominate) {
                Text("SET", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ------------------------------------------------------------------------ previews

@Preview(name = "Nominated account: one printed, one never printed, none picked yet", widthDp = 360)
@Composable
private fun PreviewNominatedAccountSectionUnset() = LegionTheme {
    Surface {
        NominatedAccountSection(
            balances = listOf(
                AccountBalance("BOFA-CHECKING", com.kevin.legion.data.local.LedgerCurrency.USD, 381_200L, asOfMs = 0L),
                AccountBalance("BOFA ****4471", com.kevin.legion.data.local.LedgerCurrency.USD, null, asOfMs = null),
            ),
            nominatedAccountId = null,
            onNominate = {},
        )
    }
}

@Preview(name = "Nominated account: one already picked", widthDp = 360)
@Composable
private fun PreviewNominatedAccountSectionSet() = LegionTheme {
    Surface {
        NominatedAccountSection(
            balances = listOf(
                AccountBalance("BOFA-CHECKING", com.kevin.legion.data.local.LedgerCurrency.USD, 381_200L, asOfMs = 0L),
                AccountBalance("BOFA ****4471", com.kevin.legion.data.local.LedgerCurrency.USD, null, asOfMs = null),
            ),
            nominatedAccountId = "BOFA-CHECKING",
            onNominate = {},
        )
    }
}
