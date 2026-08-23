package com.kevin.legion.ui.ledger

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kevin.legion.ledger.AccountBalance
import com.kevin.legion.ledger.LedgerController
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * `log_pending_transaction` by hand (command-center ticket 11, ADR 0035). Traced the tool's own
 * dispatch in `service/LiveToolbox.kt` (`logPendingTransaction`) before writing this: account
 * resolution and cents conversion are pure functions in `ledger/LedgerPendingLog.kt`, and the
 * actual write is [LedgerController.logPendingTransaction] - this dialog calls THAT function
 * directly, same as the voice path, never a second write.
 *
 * **Cents-precise, no `Double` (CLAUDE.md §4 rule 3).** The voice tool's own pure helper
 * ([com.kevin.legion.ledger.pendingAmountCents]) takes a `Double` because a spoken number arrives
 * as one; a TYPED dollar amount has no such excuse, so this dialog reuses
 * [parseDollarsToCents] - the same dollars-text-to-`Long`-cents parser
 * `ui/ledger/LedgerCategoryDrilldown.kt`'s SET TARGET row already established for this exact
 * screen - rather than parsing to `Double` and rounding. The direction (`debit`/`credit`) is then
 * applied as a sign on the already-exact `Long`, matching [com.kevin.legion.ledger.pendingAmountCents]'s
 * own sign convention (credit positive, debit negative) without going through its `Double` input.
 *
 * **Account is picked from what is already on file, never typed free text.** `resolveAccountForPending`
 * (the voice path's own resolver) exists because a SPOKEN account name is ambiguous and needs
 * fuzzy matching; a screen can instead offer the exact list `LedgerController.accountBalances`
 * already returned for this screen's own BALANCES section, so this dialog picks the `accountId`
 * directly and reads its currency straight off the same [AccountBalance] row - no ambiguity to
 * resolve, no fuzzy match, no separate call to `resolveAccountForPending` needed.
 */
@Composable
fun AddPendingTransactionDialog(accounts: List<AccountBalance>, onDismiss: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var description by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var direction by remember { mutableStateOf("debit") }
    var selectedAccountId by remember { mutableStateOf(accounts.firstOrNull()?.accountId ?: "") }
    var accountMenuOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val sem = LocalLegionSemantics.current

    val amountCents = parseDollarsToCents(amountText)
    val selectedAccount = accounts.firstOrNull { it.accountId == selectedAccountId }
    val valid = accounts.isNotEmpty() && amountCents != null && amountCents > 0L &&
        description.isNotBlank() && selectedAccount != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log a pending charge") },
        text = {
            Column {
                if (accounts.isEmpty()) {
                    // Same refusal `resolveAccountForPending`'s NoMatch branch speaks on the voice
                    // path - "No ledger accounts on file yet - import a statement first."
                    Text(
                        "No ledger accounts on file yet - import a statement first.",
                        style = LegionType.stamp,
                        color = sem.faint,
                    )
                } else {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Text(
                            selectedAccount?.accountId ?: "Pick an account",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.clickable(enabled = !busy) { accountMenuOpen = true },
                        )
                        DropdownMenu(expanded = accountMenuOpen, onDismissRequest = { accountMenuOpen = false }) {
                            for (account in accounts) {
                                DropdownMenuItem(
                                    text = { Text(account.accountId) },
                                    onClick = { selectedAccountId = account.accountId; accountMenuOpen = false },
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("What was it for") },
                        enabled = !busy,
                    )
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text("Amount, e.g. 42.50") },
                        enabled = !busy,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Row(Modifier.padding(top = 8.dp)) {
                        DirectionChoice("Charge", direction == "debit", enabled = !busy) { direction = "debit" }
                        DirectionChoice("Credit", direction == "credit", enabled = !busy, modifier = Modifier.padding(start = 16.dp)) { direction = "credit" }
                    }
                    if (amountText.isNotBlank() && amountCents == null) {
                        Text(dollarsParseErrorMessage(), style = LegionType.stamp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                result?.let { Text(it, style = LegionType.stamp, color = sem.data, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            if (result == null) {
                TextButton(
                    enabled = !busy && valid,
                    onClick = {
                        val account = selectedAccount ?: return@TextButton
                        val cents = amountCents ?: return@TextButton
                        busy = true
                        scope.launch {
                            // Same call log_pending_transaction's dispatch makes:
                            // LedgerController.logPendingTransaction(...). Sign matches
                            // pendingAmountCents's own convention: credit positive, debit negative.
                            val signedCents = signedPendingCents(cents, direction)
                            // Same device-clock-to-UTC-midnight convention the tool's own
                            // dispatch uses for a blank/omitted spoken date - "today" reads off
                            // the DEVICE's zone, then stores as UTC midnight.
                            val today = LocalDate.now(ZoneId.systemDefault())
                                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                            LedgerController.logPendingTransaction(
                                context = context,
                                accountId = account.accountId,
                                currency = account.currency,
                                description = description.trim(),
                                amountCents = signedCents,
                                txnDate = today,
                            )
                            result = "Logged as pending, not yet confirmed by the bank."
                            busy = false
                        }
                    },
                ) { Text("Log") }
            } else {
                TextButton(onClick = onDone) { Text("Done") }
            }
        },
        dismissButton = { if (result == null) TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Applies a `debit`/`credit` direction to an already-exact, already-positive `Long` cents
 * magnitude - the SAME sign convention [com.kevin.legion.ledger.pendingAmountCents] uses (credit
 * positive, debit negative), reached without going through that function's `Double` input since a
 * typed dollar amount parses straight to `Long` via [parseDollarsToCents]. `internal`, not
 * `private`, so a plain JUnit test can pin it without Robolectric or a Composable host, matching
 * [parseDollarsToCents]'s own visibility for the same reason.
 */
internal fun signedPendingCents(magnitudeCents: Long, direction: String): Long =
    if (direction.equals("credit", ignoreCase = true)) magnitudeCents else -magnitudeCents

@Composable
private fun DirectionChoice(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Text(
        label.uppercase(),
        style = LegionType.stamp,
        color = if (selected) sem.data else sem.faint,
        modifier = modifier.clickable(enabled = enabled) { onClick() },
    )
}
