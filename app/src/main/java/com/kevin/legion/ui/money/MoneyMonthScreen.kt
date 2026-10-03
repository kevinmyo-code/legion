package com.kevin.legion.ui.money

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kevin.legion.R
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.ledger.AccountMonthResult
import com.kevin.legion.ledger.AccountMonthSpend
import com.kevin.legion.ledger.CategorySpend
import com.kevin.legion.ledger.formatMoney
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme

/** What the person has spent this calendar month, per account, and on what (Kevin, 2026-10-02). */
@Composable
fun MoneyMonthScreen(onOpenDetails: () -> Unit) {
    val viewModel: MoneyMonthViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    // Back from a drilldown returns to the month; with none open the nav stack handles it.
    BackHandler(enabled = state.drilldown != null) { viewModel.closeDrilldown() }
    MoneyMonthContent(
        state = state,
        onOpenCategory = viewModel::openCategory,
        onCloseDrilldown = viewModel::closeDrilldown,
        onOpenDetails = onOpenDetails,
    )
}

/** Stateless, so Roborazzi can render every state with fakes. */
@Composable
fun MoneyMonthContent(
    state: MoneyMonthUiState,
    onOpenCategory: (AccountMonthSpend, String?) -> Unit,
    onCloseDrilldown: () -> Unit,
    onOpenDetails: () -> Unit,
) {
    SoftTheme {
        val drilldown = state.drilldown
        if (drilldown != null) {
            MoneyDrilldownContent(drilldown, onBack = onCloseDrilldown)
            return@SoftTheme
        }
        Column(
            Modifier.fillMaxSize().background(SoftColors.ground).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                Text("Money", style = MaterialTheme.typography.headlineSmall, color = SoftColors.text)
                Text(state.monthTitle, style = MaterialTheme.typography.bodyMedium, color = SoftColors.text2)
                if (!state.loading && !state.readFailed) {
                    state.newestOverall?.let {
                        Text(
                            dataThroughLine(it, state.today),
                            style = MaterialTheme.typography.bodySmall,
                            color = SoftColors.text3,
                        )
                    }
                }
            }
            when {
                state.loading -> StatusCard("Loading this month.", alert = false)
                state.readFailed -> StatusCard("Couldn't read your accounts.", alert = true)
                state.accounts.isEmpty() -> StatusCard(
                    emptyMonthMessage(state.month, state.newestOverall, state.today),
                    alert = false,
                )
                else -> state.accounts.forEach { result ->
                    when (result) {
                        is AccountMonthResult.Spend -> AccountCard(
                            result.spend,
                            dataLine = perAccountLine(state, result.spend.name),
                            onOpenCategory = onOpenCategory,
                        )
                        is AccountMonthResult.Unreadable -> StatusCard("Couldn't read ${result.name}.", alert = true)
                    }
                }
            }
            DetailsRow(onOpenDetails)
        }
    }
}

@Composable
private fun StatusCard(text: String, alert: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = if (alert) SoftColors.onAlert else SoftColors.text2,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (alert) SoftColors.alertContainer else SoftColors.card, MaterialTheme.shapes.large)
            .padding(16.dp),
    )
}

@Composable
private fun AccountCard(
    spend: AccountMonthSpend,
    dataLine: String?,
    onOpenCategory: (AccountMonthSpend, String?) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(SoftColors.card, MaterialTheme.shapes.large).padding(vertical = 14.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(spend.name, style = MaterialTheme.typography.titleSmall, color = SoftColors.text2)
            dataLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SoftColors.text3) }
            Text(
                formatMoney(spend.totalCents, spend.currency),
                style = MaterialTheme.typography.headlineSmall,
                color = SoftColors.text,
            )
            Text("spent this month", style = MaterialTheme.typography.bodySmall, color = SoftColors.text3)
            currentPeriodLine(spend)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = SoftColors.text3,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (spend.hasNoSpend) {
            Text(
                "Nothing spent yet this month.",
                style = MaterialTheme.typography.bodyMedium,
                color = SoftColors.text2,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        } else {
            Spacer(Modifier.height(6.dp))
            spend.categories.forEach { CategoryRow(spend, it, onOpenCategory) }
            spend.uncategorized?.let { CategoryRow(spend, it, onOpenCategory) }
        }
        spend.disclosures.forEach {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = SoftColors.text3,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
            )
        }
    }
}

@Composable
private fun CategoryRow(
    spend: AccountMonthSpend,
    row: CategorySpend,
    onOpenCategory: (AccountMonthSpend, String?) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpenCategory(spend, row.category) }
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                drilldownCategoryName(row.category),
                style = MaterialTheme.typography.bodyLarge,
                color = SoftColors.text,
            )
            if (row.category == null) {
                Text(
                    "Not counted in the total",
                    style = MaterialTheme.typography.bodySmall,
                    color = SoftColors.text3,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            formatMoney(row.cents, spend.currency),
            style = MaterialTheme.typography.bodyLarge,
            color = SoftColors.text,
        )
    }
}

@Composable
private fun DetailsRow(onOpenDetails: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(SoftColors.card, MaterialTheme.shapes.large)
            .clickable(onClick = onOpenDetails)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MsIcon(R.drawable.ms_account_balance, contentDescription = null, tint = SoftColors.text2, size = 20.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            "Statements, budgets and accounts",
            style = MaterialTheme.typography.bodyLarge,
            color = SoftColors.text,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MoneyDrilldownContent(drilldown: MoneyDrilldownUiState, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(SoftColors.ground).padding(12.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onBack).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MsIcon(R.drawable.ms_arrow_back, contentDescription = "Back", tint = SoftColors.text2)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    drilldownCategoryName(drilldown.category),
                    style = MaterialTheme.typography.headlineSmall,
                    color = SoftColors.text,
                )
                Text(drilldown.accountName, style = MaterialTheme.typography.bodyMedium, color = SoftColors.text2)
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            drilldown.loading -> StatusCard("Loading.", alert = false)
            drilldown.failed -> StatusCard("Couldn't read these transactions.", alert = true)
            drilldown.rows.isEmpty() -> StatusCard("No transactions.", alert = false)
            else -> Column(
                Modifier.fillMaxWidth().background(SoftColors.card, MaterialTheme.shapes.large)
                    .verticalScroll(rememberScrollState()).padding(vertical = 6.dp),
            ) {
                drilldown.rows.forEach { DrilldownRow(it, drilldown.currency) }
            }
        }
    }
}

@Composable
private fun DrilldownRow(row: MoneyDrilldownRow, currency: LedgerCurrency) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.description, style = MaterialTheme.typography.bodyLarge, color = SoftColors.text, maxLines = 2)
            Text(row.date, style = MaterialTheme.typography.bodySmall, color = SoftColors.text3)
            if (row.unverified) {
                Text("Current period", style = MaterialTheme.typography.bodySmall, color = SoftColors.text3)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(formatMoney(row.cents, currency), style = MaterialTheme.typography.bodyLarge, color = SoftColors.text)
    }
}

/** The account's own freshness line, only when the accounts disagree (otherwise the top line says it all). */
private fun perAccountLine(state: MoneyMonthUiState, name: String): String? {
    if (!accountsDifferInFreshness(state.newestByAccount)) return null
    return state.newestByAccount[name]?.let { accountDataThroughLine(it, state.today) }
}
