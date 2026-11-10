package com.kevin.legion.ui.companions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kevin.legion.memory.MemberMemory
import com.kevin.legion.memory.ReviewChange
import com.kevin.legion.ui.theme.soft.SoftColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The daily memory review on the Memory screen (memory-review ticket 09, ruling 02): **To check**,
 * the pending changes grouped by the memories they touch, each with Accept and Dismiss and Accept
 * all for a group; and the **Retired** list, each row with why it left recall and, where the review
 * retired it, Restore. Every state is said in words, never by colour alone. Nothing here raises a
 * suggestion on its own: it waits on this screen until Kevin opens it.
 */
@Composable
internal fun FilterRow(filter: MemoryFilter, onFilter: (MemoryFilter) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = filter == MemoryFilter.Live,
            onClick = { onFilter(MemoryFilter.Live) },
            label = { Text("Remembered") },
            modifier = Modifier.testTag("filter-live"),
        )
        FilterChip(
            selected = filter == MemoryFilter.Retired,
            onClick = { onFilter(MemoryFilter.Retired) },
            label = { Text("Retired") },
            modifier = Modifier.testTag("filter-retired"),
        )
    }
}

@Composable
internal fun ReviewSection(review: ReviewState, busy: Boolean, callbacks: MemoryCallbacks) {
    Column(Modifier.fillMaxWidth().testTag("to-check"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("To check", style = MaterialTheme.typography.titleSmall, color = SoftColors.text)
        Text(
            "The nightly review could not check these on its own. Nothing changes until you accept.",
            style = MaterialTheme.typography.labelMedium,
            color = SoftColors.text2,
        )
        review.note?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = SoftColors.onAlert) }
        for (group in review.clusters) {
            ClusterCard(group, enabled = !busy && !review.offline, callbacks)
        }
    }
}

@Composable
private fun ClusterCard(group: List<ReviewChange>, enabled: Boolean, callbacks: MemoryCallbacks) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SoftColors.card).padding(14.dp)
            .testTag("cluster-${group.first().cluster}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (change in group) ChangeBlock(change, enabled, callbacks)
        if (group.size > 1) {
            TextButton(
                onClick = { callbacks.onAcceptCluster(group.first().cluster) },
                enabled = enabled,
                modifier = Modifier.testTag("accept-all-${group.first().cluster}"),
            ) { Text("Accept all ${group.size}") }
        }
    }
}

@Composable
private fun ChangeBlock(change: ReviewChange, enabled: Boolean, callbacks: MemoryCallbacks) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(change.actionWords, style = MaterialTheme.typography.labelLarge, color = SoftColors.text)
        for (before in change.before) {
            Text(
                "Now: ${before.text ?: "(no longer there)"}",
                style = MaterialTheme.typography.bodyMedium,
                color = SoftColors.text3,
            )
        }
        Text(
            "${afterLabel(change.action)}: ${change.afterWords}",
            style = MaterialTheme.typography.bodyMedium,
            color = SoftColors.text,
        )
        if (change.reason.isNotBlank()) {
            Text("Why: ${change.reason}", style = MaterialTheme.typography.labelMedium, color = SoftColors.text2)
        }
        evidenceWords(change.evidenceDate)?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = SoftColors.text2)
        }
        Row {
            TextButton(
                onClick = { callbacks.onAcceptChange(change) },
                enabled = enabled,
                modifier = Modifier.testTag("accept-${change.id}"),
            ) { Text("Accept") }
            TextButton(
                onClick = { callbacks.onDismissChange(change) },
                enabled = enabled,
                modifier = Modifier.testTag("dismiss-${change.id}"),
            ) { Text("Dismiss") }
        }
    }
}

@Composable
internal fun RetiredBody(view: MemoryView, busy: Boolean, callbacks: MemoryCallbacks) {
    when (view) {
        MemoryView.Loading -> RetiredNote("Reading what the memory review retired...")
        is MemoryView.Unavailable -> RetiredNote(view.sentence)
        is MemoryView.Listed -> if (view.memories.isEmpty()) {
            RetiredNote("Nothing has been retired by the memory review.")
        } else {
            LazyColumn(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(view.memories, key = { it.id }) { RetiredRow(it, busy, callbacks) }
            }
        }
    }
}

@Composable
private fun RetiredRow(memory: MemberMemory, busy: Boolean, callbacks: MemoryCallbacks) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SoftColors.card).padding(14.dp)
            .testTag("retired-${memory.id}"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(memory.text, style = MaterialTheme.typography.bodyLarge, color = SoftColors.text2)
        Text(
            retiredWords(memory.retiredReason),
            style = MaterialTheme.typography.labelMedium,
            color = SoftColors.text3,
        )
        if (memory.retiredChange != null) {
            TextButton(
                onClick = { callbacks.onRestore(memory) },
                enabled = !busy,
                modifier = Modifier.testTag("restore-${memory.id}"),
            ) { Text("Restore") }
        }
    }
}

@Composable
private fun RetiredNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = SoftColors.text2,
        modifier = Modifier.padding(16.dp),
    )
}

private fun afterLabel(action: String): String = when (action) {
    "merge", "correct" -> "Becomes"
    else -> "Then"
}

/** "Merged", "Date passed", "Replaced by a newer fact": why a row left recall, in words. */
internal fun retiredWords(reason: String?): String = when (reason) {
    "merged" -> "Merged with the same fact said another way"
    "contradicted" -> "Replaced by a newer fact"
    "expired" -> "Date passed"
    "relabelled" -> "Kept again with new labels"
    "not_about_person" -> "Not about you"
    else -> "Replaced by a newer version"
}

private val DAY = DateTimeFormatter.ofPattern("MMMM d", Locale.US)

/** "From your conversation on November 7", or null without a date. */
internal fun evidenceWords(date: String?): String? = date?.let {
    runCatching { "From your conversation on ${LocalDate.parse(it).format(DAY)}" }.getOrNull()
}
