package com.kevin.legion.ui.assistant

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import com.kevin.legion.assistant.cards.Reading
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.assistant.cards.RowStatus
import com.kevin.legion.assistant.cards.TrendStyle
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.TypesetType

/** Test tags on a memory row's Forget link (suffixed with the row) and the dialog's confirm. */
internal const val CARD_FORGET_TAG = "card-forget"
internal const val CARD_CONFIRM_FORGET_TAG = "card-confirm-forget"

/**
 * The general kinds (2026-11-15) on a card page: readings, status rows and a trend. Every value,
 * status and series is said in words ([CardWords]); the trend's picture is decoration beside them,
 * hidden from a screen reader, and a status word in the accent repeats a flag it already says.
 */
@Composable
internal fun GeneralBody(card: ConversationCard) {
    when (card) {
        is ConversationCard.Readings -> ReadingsBody(card)
        is ConversationCard.StatusList -> StatusBody(card)
        is ConversationCard.Trend -> TrendBody(card)
        else -> Unit
    }
}

@Composable
internal fun MoreLine(count: Int) {
    if (count > 0) {
        Text(
            "And $count more.",
            style = TypesetType.smallItalic,
            color = SoftColors.text2,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun ReadingsBody(card: ConversationCard.Readings) {
    CardPictureWords.headline(card)?.let { HeadlineBlock(it) }
    AsOfLine(card.at)
    val forget = card.forgetIds?.let { LocalCardForget.current ?: rememberMemoryForget() }
    val outcomes = remember(card) { mutableStateMapOf<Int, ForgetResult>() }
    var asking by remember(card) { mutableStateOf<Int?>(null) }
    var busy by remember(card) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    card.items.forEachIndexed { i, r ->
        val canForget = forget != null && card.forgetIds?.getOrNull(i) != null
        ReadingItem(i, r, outcomes[i], canForget) { asking = i }
    }
    MoreLine(card.more)
    val at = asking
    val id = at?.let { card.forgetIds?.getOrNull(it) }
    if (at != null && id != null && forget != null) {
        ForgetDialog(card.items[at].text.orEmpty(), busy, onDismiss = { if (!busy) asking = null }) {
            busy = true
            scope.launch {
                outcomes[at] = forget.forget(id)
                busy = false
                asking = null
            }
        }
    }
}

/** One reading: its row, its pictures (none once forgotten), its note, and Forget when offered. */
@Composable
private fun ReadingItem(i: Int, r: Reading, outcome: ForgetResult?, canForget: Boolean, onForget: () -> Unit) {
    val gone = outcome?.done == true
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        ReadingRow(r, gone)
        if (!gone) ReadingPictures(r)
        r.note?.let { Text(it, style = TypesetType.smallItalic, color = SoftColors.text2) }
        outcome?.let { Text(it.words, style = TypesetType.smallItalic, color = SoftColors.text2) }
        if (canForget && !gone) ForgetLink(i, onForget)
    }
    HorizontalDivider(thickness = 1.dp, color = SoftColors.barRule)
}

@Composable
private fun ForgetLink(row: Int, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = TARGET).testTag("$CARD_FORGET_TAG-$row")
            .clickable(role = Role.Button, onClickLabel = "Forget this memory", onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            "Forget",
            style = TypesetType.small.copy(textDecoration = TextDecoration.Underline),
            color = SoftColors.text,
        )
    }
}

/** The Memory screen's own question before a hard delete; nothing is deleted until Forget. */
@Composable
private fun ForgetDialog(text: String, busy: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Forget this for good?") },
        text = {
            Text("\"$text\" will be deleted from the engine, with any older version of it. It cannot be brought back.")
        },
        confirmButton = {
            TextButton(enabled = !busy, modifier = Modifier.testTag(CARD_CONFIRM_FORGET_TAG), onClick = onConfirm) {
                Text("Forget")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Keep") } },
    )
}

/**
 * A reading's label and value on one line; a value in words too long for the column (a memory, an
 * address) sits under its label instead. A forgotten row is set in the meta ink.
 */
@Composable
private fun ReadingRow(r: Reading, gone: Boolean) {
    val words = CardGeneralWords.reading(r)
    val dim = gone || (r.value == null && r.text == null)
    if (r.value == null && r.text != null && r.text.length > LONG_TEXT) {
        Text(r.label, style = TypesetType.small, color = SoftColors.text2)
        Text(words, style = TypesetType.body, color = if (gone) SoftColors.text2 else SoftColors.text)
        return
    }
    Row(verticalAlignment = Alignment.Top) {
        Text(
            r.label,
            style = TypesetType.body,
            color = SoftColors.text,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        Text(
            words,
            style = TypesetType.bodyStrong,
            color = when {
                dim -> SoftColors.text2
                r.attention -> SoftColors.caution
                else -> SoftColors.text
            },
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

@Composable
private fun StatusBody(card: ConversationCard.StatusList) {
    CardPictureWords.headline(card)?.let { HeadlineBlock(it) }
    AsOfLine(card.at)
    card.items.forEach { row ->
        val flagged = row.status == RowStatus.ATTENTION || row.status == RowStatus.FAILED
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                StatusMark(row.status)
                Text(
                    row.name,
                    style = TypesetType.body,
                    color = SoftColors.text,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                Text(
                    row.status.words,
                    style = TypesetType.bodyStrong,
                    color = if (flagged) SoftColors.caution else SoftColors.text,
                    textAlign = TextAlign.End,
                )
            }
            row.level?.let { LevelStrip(it, row.status) }
            row.meter?.let { StatusMeterBar(it, row.status) }
            row.note?.let { Text(it, style = TypesetType.smallItalic, color = SoftColors.text2) }
        }
        HorizontalDivider(thickness = 1.dp, color = SoftColors.barRule)
    }
    MoreLine(card.more)
}

@Composable
private fun TrendBody(card: ConversationCard.Trend) {
    CardPictureWords.headline(card)?.let { HeadlineBlock(it) }
    val points = card.series.sumOf { it.points.size }
    if (points > 1 || (card.style == TrendStyle.BARS && points == 1)) TrendChart(card)
    card.series.forEach { RuledLine(CardGeneralWords.series(it, card.unit)) }
}

@Composable
private fun AsOfLine(at: String?) {
    CardGeneralWords.asOf(at)?.let {
        Text(it, style = TypesetType.smallItalic, color = SoftColors.text2, modifier = Modifier.padding(top = 6.dp))
    }
}

/** A value in words longer than this sits under its label. */
private const val LONG_TEXT = 28
