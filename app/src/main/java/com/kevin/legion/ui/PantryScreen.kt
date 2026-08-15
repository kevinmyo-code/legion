package com.kevin.legion.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.data.PantryPhotoStore
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.PantryCurrencyTotal
import com.kevin.legion.data.local.PantryLineItem
import com.kevin.legion.data.local.PantryReceipt
import com.kevin.legion.data.local.PantryReceiptSummary
import com.kevin.legion.pantry.PantryController
import com.kevin.legion.ui.pantry.PantryOpsStatusRow
import com.kevin.legion.ui.pantry.PantryReceiptSection
import com.kevin.legion.ui.pantry.PantrySpendPanel
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics

/**
 * `pantry` tab. Ticket 09 resolution §2 (TREATMENT B, SEGREGATED): the most
 * recent receipts, each rendered by [PantryReceiptSection] - the receipt's
 * own line-item prices under `ON THE RECEIPT`, LLM-estimated macros
 * physically separated under `ESTIMATED, NOT ON THE RECEIPT`. This ticket
 * settles no receipt-capture flow or spend aggregation (CLAUDE.md §10) -
 * [onOpenImport] is the one existing action, unchanged from the pre-ticket-09
 * placeholder.
 *
 * Split per the repo's vendored `compose-state-holder-ui-split` skill, same
 * shape as [LedgerScreen]/[FleetScreen]: [PantryScreen] is the state holder
 * (talks to [PantryController], owns the one side effect - the load),
 * [PantryContent] is plain UI state plus callbacks and is what the
 * `@Preview`s below exercise.
 *
 * Reskinned under cyberdeck-ui ticket 19 (ticket 10 answer #1: "inherit the panels, skip the
 * charts"): [PantryOpsStatusRow] reads [PantryUiState.receipts] - already loaded, no new query -
 * and every receipt below renders through [PantryReceiptSection]'s own [com.kevin.legion.ui.common.DeckPane].
 * [onOpenImport] is unchanged - the only tap this screen offers, same as pre-ticket-19.
 */
data class PantryUiState(
    val loading: Boolean = true,
    val receipts: List<Pair<PantryReceipt, List<PantryLineItem>>> = emptyList(),
    // Quant-viz ticket 07: the SPEND panel's own reads, batched into this same load rather than a
    // second LaunchedEffect - [currencyTotals] backs the per-currency rows, [allReceiptSummaries]
    // (deliberately NOT [receipts], which is capped) backs the monthly bar chart.
    val currencyTotals: List<PantryCurrencyTotal> = emptyList(),
    val allReceiptSummaries: List<PantryReceiptSummary> = emptyList(),
)

@Composable
fun PantryScreen(onOpenImport: () -> Unit) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(PantryUiState()) }

    LaunchedEffect(Unit) {
        state = PantryUiState(
            loading = false,
            receipts = PantryController.recentReceiptsWithItems(context),
            currencyTotals = PantryController.totalSpendCentsByCurrency(context),
            allReceiptSummaries = PantryController.allReceiptSummaries(context),
        )
    }

    PantryContent(state = state, onOpenImport = onOpenImport)
}

/** Plain UI: [state] plus callbacks, no controller reference - see the file doc comment. */
@Composable
fun PantryContent(state: PantryUiState, onOpenImport: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("PANTRY", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                TextButton(onClick = onOpenImport) {
                    Text("IMPORT", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                }
            }
            PantryOpsStatusRow(receiptCount = if (state.loading) null else state.receipts.size)
            if (!state.loading) {
                PantrySpendPanel(
                    currencyTotals = state.currencyTotals,
                    allReceipts = state.allReceiptSummaries,
                )
            }

            when {
                state.loading -> Text(
                    "Loading...",
                    style = LegionType.stamp,
                    color = sem.ghost,
                    modifier = Modifier.padding(12.dp),
                )
                state.receipts.isEmpty() -> Column(Modifier.padding(12.dp)) {
                    Text("No receipts yet", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Import a photo of a grocery receipt to get started.",
                        style = MaterialTheme.typography.bodySmall,
                        color = sem.faint,
                    )
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.receipts, key = { (receipt, _) -> receipt.id }) { (receipt, items) ->
                        PantryReceiptSection(receipt, items)
                        Spacer(Modifier.height(18.dp))
                    }
                }
            }
        }
    }
}

/**
 * `pantry/import` - absorbed from the deleted `PantryImportActivity`.
 * Content unchanged (take or pick a photo, [PantryPhotoStore] saves it,
 * [PantryController] extracts and reconciles it); only the hosting changed.
 * `decodeUri` moved from an Activity method (needed `contentResolver`) to a
 * private top-level function taking `Context`, since there is no Activity
 * instance here to hang it off. Untouched by ticket 09 - out of scope per
 * its resolution.
 */
@Composable
fun PantryImportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Take or pick a photo of a grocery receipt.") }
    var pendingBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { pendingBitmap = decodePantryUri(context, it) }
    }
    val cameraLauncher = rememberCameraCaptureLauncher(context) { pendingBitmap = it }

    LaunchedEffect(pendingBitmap) {
        val current = pendingBitmap ?: return@LaunchedEffect
        status = "Importing..."
        val file = PantryPhotoStore.save(context, current)
        status = PantryController.importReceipt(context, file).message
        pendingBitmap = null
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Button(onClick = onBack) {
                Text("< Back")
            }
            Text(status)
            if (hasCamera(context)) {
                Button(onClick = cameraLauncher) {
                    Text("Take photo")
                }
            }
            Button(onClick = { pickImage.launch("image/*") }) {
                Text("Pick from gallery")
            }
        }
    }
}

private fun decodePantryUri(context: android.content.Context, uri: Uri): Bitmap? = try {
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
} catch (e: Exception) {
    null
}

// ------------------------------------------------------------------------ previews

@Preview(name = "Pantry: loading", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewPantryLoading() = LegionTheme {
    PantryContent(PantryUiState(loading = true), onOpenImport = {})
}

@Preview(name = "Pantry: empty, no receipts yet", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewPantryEmpty() = LegionTheme {
    PantryContent(PantryUiState(loading = false), onOpenImport = {})
}

@Preview(name = "Pantry: one receipt, segregated macros", widthDp = 360, heightDp = 1200)
@Composable
private fun PreviewPantryPopulated() = LegionTheme {
    val receipt = PantryReceipt(
        id = 1,
        store = "TRADER JOES",
        purchaseDate = System.currentTimeMillis(),
        currency = LedgerCurrency.USD,
        totalCents = 3368,
        sourceImagePath = "",
    )
    val lineItems = listOf(
        PantryLineItem(
            receiptId = 1, name = "ORGANIC WHOLE MILK 1 GAL", totalPriceCents = 649,
            caloriesKcal = 610, proteinG = 32.0, carbsG = 48.0, fatG = 32.0,
        ),
        PantryLineItem(
            receiptId = 1, name = "CHICKEN BREAST BONELESS 2.1LB", totalPriceCents = 1287,
            caloriesKcal = 1090, proteinG = 205.0, carbsG = 0.0, fatG = 24.0,
        ),
        PantryLineItem(receiptId = 1, name = "SOURDOUGH LOAF", totalPriceCents = 399),
    )
    PantryContent(
        PantryUiState(loading = false, receipts = listOf(receipt to lineItems)),
        onOpenImport = {},
    )
}
