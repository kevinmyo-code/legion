package com.kevin.legion.ui.assistant

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.R
import com.kevin.legion.assistant.cards.BarPoint
import com.kevin.legion.assistant.cards.CardFlag
import com.kevin.legion.assistant.cards.ChecklistLine
import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.assistant.cards.NextServiceDue
import com.kevin.legion.assistant.cards.PhoneCards
import com.kevin.legion.assistant.cards.StepItem
import com.kevin.legion.assistant.cards.StepStatus
import com.kevin.legion.assistant.cards.TimelineItem
import com.kevin.legion.assistant.cards.TimelineKind
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme

/** Test tag on the card's outer surface. */
internal const val CONVERSATION_CARD_TAG = "conversation-card"

private val CardShape = RoundedCornerShape(16.dp)
private val MinTarget = 48.dp
private val ExpandedMaxHeight = 320.dp
private val BarHeight = 8.dp
private val BarLabelWidth = 96.dp

/**
 * The latest conversation card (`assistant/cards/`), drawn above the talk pill while a voice
 * conversation runs. Collapsed it is the title and one line; a tap anywhere on it expands the full
 * body (scrolls past [ExpandedMaxHeight]). The close button dismisses it until the next card.
 * Soft Material (ADR 0051), 48dp targets, every status in words; colour is only reinforcement.
 */
@Composable
internal fun ConversationCardView(card: ConversationCard, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    // Keyed on the card, so a new card always arrives collapsed.
    var expanded by rememberSaveable(card) { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .testTag(CONVERSATION_CARD_TAG)
            .clip(CardShape)
            .background(SoftColors.card)
            .clickable(role = Role.Button, onClickLabel = if (expanded) "Show less" else "Show all") { expanded = !expanded }
            .animateContentSize()
            .padding(start = 14.dp, top = 4.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(top = 6.dp)) {
                Text(card.title, style = MaterialTheme.typography.titleSmall, color = SoftColors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                subtitle(card)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = SoftColors.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(
                Modifier
                    .size(MinTarget)
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .semantics { contentDescription = "Close card" },
                contentAlignment = Alignment.Center,
            ) {
                MsIcon(R.drawable.ms_close, contentDescription = null, tint = SoftColors.text2, size = 20.dp)
            }
        }
        Column(Modifier.padding(end = 14.dp)) {
            if (expanded) {
                Column(
                    Modifier.heightIn(max = ExpandedMaxHeight).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CardBody(card)
                    card.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SoftColors.text2) }
                }
            } else {
                Text(
                    collapsedLine(card),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SoftColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                if (expanded) "Show less" else "Tap to show all",
                style = MaterialTheme.typography.labelMedium,
                color = SoftColors.primary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun CardBody(card: ConversationCard) {
    when (card) {
        is ConversationCard.Bars -> BarsBody(card)
        is ConversationCard.Timeline -> {
            card.items.forEach { Line(timelineLine(it)) }
            MoreLine(card.more)
        }
        is ConversationCard.Car -> carLines(card).forEach { Line(it) }
        is ConversationCard.Steps -> {
            card.items.forEach { Line(stepLine(it)) }
            card.totalMs?.let { Line("Total: ${seconds(it)}") }
        }
        is ConversationCard.Checklist -> {
            card.items.forEach { Line(checkLine(it)) }
            MoreLine(card.more)
        }
    }
}

@Composable
private fun MoreLine(count: Int) {
    if (count > 0) Line("And $count more.")
}

@Composable
private fun Line(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = SoftColors.text)
}

@Composable
private fun BarsBody(card: ConversationCard.Bars) {
    val top = card.points.maxOfOrNull { it.value }?.takeIf { it > 0 } ?: 1L
    card.points.forEach { p ->
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.label, style = MaterialTheme.typography.bodyMedium, color = SoftColors.text, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.width(BarLabelWidth))
                Box(Modifier.weight(1f).height(BarHeight).clip(RoundedCornerShape(4.dp)).background(SoftColors.cardHighest)) {
                    Box(
                        Modifier
                            .fillMaxWidth((p.value.coerceAtLeast(0).toFloat() / top).coerceIn(0f, 1f))
                            .height(BarHeight)
                            .background(if (p.flag == null) SoftColors.primary else SoftColors.caution),
                    )
                }
            }
            Text(barValue(p, card.unit), style = MaterialTheme.typography.bodySmall, color = SoftColors.text2)
        }
    }
    MoreLine(card.more)
}

// --- words, pure so a test can pin them ------------------------------------------------------

internal fun subtitle(card: ConversationCard): String? =
    listOfNotNull(card.period, card.scope).joinToString(", ").ifBlank { null }

internal fun barValue(p: BarPoint, unit: String): String =
    PhoneCards.money(p.value, unit) + (p.flag?.let { " (${it.words})" } ?: "")

internal fun timelineLine(i: TimelineItem): String {
    val time = when {
        i.allDay -> "All day"
        i.start != null && i.end != null -> "${i.start} to ${i.end}"
        i.start != null -> i.start
        else -> "No time"
    }
    val kind = when (i.kind) {
        TimelineKind.EVENT -> ""
        TimelineKind.TODO -> " (to-do)"
        TimelineKind.SUGGESTION -> " (suggestion, not a plan)"
    }
    return "$time: ${i.title}$kind"
}

internal fun carLines(card: ConversationCard.Car): List<String> = buildList {
    add(
        card.mileage?.let { m -> "Mileage: ${"%,d".format(m)} mi" + (card.mileageAsOf?.let { " (last reading $it)" } ?: "") }
            ?: "Mileage: none on file",
    )
    add(card.nextService?.let(::serviceLine) ?: "Next service: nothing to time")
    add(
        when {
            card.recalls == null -> "Recalls: not checked"
            card.recalls.isEmpty() -> "Recalls: none on file"
            else -> "Recalls (${card.recalls.size}): " + card.recalls.joinToString("; ") { it.summary }
        },
    )
    add(
        when {
            card.codes == null -> "Trouble codes: not checked"
            card.codes.isEmpty() -> "Trouble codes: none stored"
            else -> "Trouble codes: " + card.codes.joinToString("; ") { c -> c.code + (c.meaning?.let { " $it" } ?: ", meaning unknown") }
        },
    )
}

private fun serviceLine(n: NextServiceDue): String {
    val at = listOfNotNull(n.dueMiles?.let { "at ${"%,d".format(it)} mi" }, n.dueDate?.let { "by $it" }).joinToString(" or ")
    return "Next service: ${n.label}" + (if (at.isBlank()) "" else " $at") + if (n.estimate) " (estimate)" else ""
}

internal fun stepLine(s: StepItem): String =
    "${s.status.words}: ${s.label}" + (s.ms?.let { ", ${seconds(it)}" } ?: if (s.status == StepStatus.SKIPPED) "" else ", time not reported")

internal fun checkLine(l: ChecklistLine): String = (if (l.done) "Done: " else "To do: ") + l.text

@Suppress("MagicNumber") // milliseconds in a second
private fun seconds(ms: Long): String = if (ms < 1_000) "$ms ms" else "%.1f s".format(ms / 1_000.0)

/** The one line a collapsed card shows. */
internal fun collapsedLine(card: ConversationCard): String = when (card) {
    is ConversationCard.Bars -> card.points.firstOrNull()?.let { "${it.label} ${barValue(it, card.unit)}" + others(card.points.size - 1 + card.more) }
        ?: (card.note ?: "Nothing to show.")
    is ConversationCard.Timeline -> card.items.firstOrNull()?.let { timelineLine(it) + others(card.items.size - 1 + card.more) }
        ?: (card.note ?: "Nothing on.")
    is ConversationCard.Car -> carLines(card).let { lines ->
        when {
            card.codes?.isNotEmpty() == true -> lines[3]
            card.recalls?.isNotEmpty() == true -> lines[2]
            card.nextService != null -> lines[1]
            card.recalls != null -> lines[2]
            card.codes != null -> lines[3]
            else -> lines[0]
        }
    }
    is ConversationCard.Steps -> {
        val failed = card.items.count { it.status == StepStatus.FAILED }
        "${card.items.count { it.status == StepStatus.OK }} of ${card.items.size} steps done" +
            (if (failed > 0) ", $failed failed" else "")
    }
    is ConversationCard.Checklist -> card.note ?: "${card.items.size} items"
}

private fun others(n: Int): String = if (n > 0) ", and $n more" else ""

// --- previews --------------------------------------------------------------------------------

@Preview(name = "Card: spending bars", widthDp = 384)
@Composable
private fun PreviewBarsCard() = SoftTheme {
    ConversationCardView(
        ConversationCard.Bars(
            "Spending, $1,128.44 in all", "usd",
            listOf(BarPoint("Gas", 99_999L, CardFlag.UNVERIFIED), BarPoint("Food", 12_345L), BarPoint("Fun", 500L, CardFlag.ESTIMATE)),
            period = "November 2026", scope = "US",
        ),
        onDismiss = {},
    )
}
