package com.kevin.legion.ui.bought

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.kevin.legion.R
import com.kevin.legion.purchases.Purchase
import com.kevin.legion.purchases.PurchaseWording
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors

/**
 * The search-first screen (variant C): a box that asks "When did we last buy...?", the answer in a
 * sentence that names the entry it matched, the rest of the matches beneath it, and a way to log by
 * hand. With the box empty it shows the newest entries.
 *
 * **Never an empty list standing in for "could not read"** (ticket 04): [BoughtView.Unavailable]
 * draws the sentence and a Retry, and the log is not drawn at all. Every price on the screen says it
 * was entered by hand, and an entry whose logger was never stored says "Logged by: not recorded".
 */
@Composable
fun BoughtSearchContent(state: BoughtUiState, callbacks: BoughtCallbacks) {
    Column(Modifier.fillMaxSize().background(SoftColors.ground)) {
        BoughtTopBar(onBack = callbacks.onBack)
        OutlinedTextField(
            value = state.query,
            onValueChange = callbacks.onQueryChange,
            label = { Text("When did we last buy...?") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        state.savedMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = SoftColors.good,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
            )
        }
        state.problem?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = SoftColors.onAlert,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
            )
        }
        state.pendingDelete?.let { DeleteConfirm(it, callbacks) }
        val logLabel = if (state.query.isBlank()) "Log something by hand" else "Log \"${state.query.trim()}\" by hand"
        TextButton(
            onClick = { callbacks.onLogIt(state.query.ifBlank { null }) },
            modifier = Modifier.padding(horizontal = 8.dp),
        ) { Text(logLabel) }
        BoughtBody(state, callbacks)
    }
}

@Composable
private fun BoughtTopBar(onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            MsIcon(res = R.drawable.ms_arrow_back, contentDescription = "Back", tint = SoftColors.text)
        }
        Text(
            "Bought log",
            style = MaterialTheme.typography.titleLarge,
            color = SoftColors.text,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun BoughtBody(state: BoughtUiState, callbacks: BoughtCallbacks) {
    when (val view = state.view) {
        BoughtView.Loading -> Note("Looking...")
        is BoughtView.Unavailable -> UnavailableBlock(view.sentence, callbacks.onRetry)
        is BoughtView.Recent -> {
            if (view.entries.isEmpty()) {
                Note(view.emptyMessage ?: "Nothing has been logged yet.")
            } else {
                EntryList(view.entries, state.today, header = "Recent", callbacks = callbacks)
            }
        }
        is BoughtView.Answer -> Column {
            Text(
                view.headline,
                style = MaterialTheme.typography.bodyLarge,
                color = SoftColors.text,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
            )
            // Even a single match is listed: its row carries the Edit and Delete buttons.
            if (view.matches.isNotEmpty()) {
                val header = if (view.matches.size > 1) "Matches, newest first" else "Match"
                EntryList(view.matches.map { it.entry }, state.today, header = header, callbacks = callbacks)
            }
        }
    }
}

/** The log could not be read: the sentence, why it is not an empty log, and a way to try again. */
@Composable
private fun UnavailableBlock(sentence: String, onRetry: () -> Unit) {
    Column(Modifier.padding(16.dp)) {
        Text(sentence, style = MaterialTheme.typography.bodyMedium, color = SoftColors.onAlert)
        Text(
            "This is not an empty log; it just can't be read right now.",
            style = MaterialTheme.typography.labelMedium,
            color = SoftColors.text2,
            modifier = Modifier.padding(top = 6.dp),
        )
        TextButton(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = SoftColors.text2,
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun EntryList(entries: List<Purchase>, today: Int, header: String, callbacks: BoughtCallbacks) {
    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") {
            Text(header, style = MaterialTheme.typography.titleSmall, color = SoftColors.text2)
        }
        items(entries, key = { it.id }) { EntryRow(it, today, callbacks) }
    }
}

@Composable
private fun EntryRow(entry: Purchase, today: Int, callbacks: BoughtCallbacks) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SoftColors.card).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(entry.item, style = MaterialTheme.typography.titleSmall, color = SoftColors.text)
        Text(
            "Bought ${PurchaseWording.date(entry.boughtOn, today)}. Logged by: ${PurchaseWording.who(entry)}",
            style = MaterialTheme.typography.labelMedium,
            color = SoftColors.text2,
        )
        val detail = listOfNotNull(entry.store, PurchaseWording.price(entry), entry.quantityNote)
            .joinToString(" · ")
        if (detail.isNotEmpty()) {
            Text(detail, style = MaterialTheme.typography.labelMedium, color = SoftColors.text2)
        }
        if (entry.isPrivate) {
            Text("Only you can see this", style = MaterialTheme.typography.labelMedium, color = SoftColors.text3)
        }
        if (entry.mayChange) {
            Row {
                TextButton(onClick = { callbacks.onEditEntry(entry) }) { Text("Edit") }
                TextButton(onClick = { callbacks.onDeleteEntry(entry) }) { Text("Delete") }
            }
        } else {
            Text(
                "Logged by someone else, so read-only",
                style = MaterialTheme.typography.labelMedium,
                color = SoftColors.text3,
            )
        }
    }
}

/** "Delete this?" - the entry named in words, and what Delete does. Nothing is deleted until Delete. */
@Composable
private fun DeleteConfirm(entry: Purchase, callbacks: BoughtCallbacks) {
    AlertDialog(
        onDismissRequest = callbacks.onCancelDelete,
        title = { Text("Delete this entry?") },
        text = { Text("\"${entry.item}\" will be removed from the bought log for everyone who can see it.") },
        confirmButton = { TextButton(onClick = callbacks.onConfirmDelete) { Text("Delete") } },
        dismissButton = { TextButton(onClick = callbacks.onCancelDelete) { Text("Keep it") } },
    )
}
