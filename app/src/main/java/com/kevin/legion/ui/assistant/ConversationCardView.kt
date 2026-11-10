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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.R
import com.kevin.legion.assistant.cards.BarPoint
import com.kevin.legion.assistant.cards.CardFlag
import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme
import java.time.ZoneId

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
            .clickable(role = Role.Button, onClickLabel = if (expanded) "Show less" else "Show all") {
                expanded = !expanded
            }
            .animateContentSize()
            .padding(start = 14.dp, top = 4.dp, bottom = 10.dp),
    ) {
        CardHeader(card, onDismiss)
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

/** Title, period and scope, and the 48dp close button. */
@Composable
private fun CardHeader(card: ConversationCard, onDismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(top = 6.dp)) {
            Text(
                card.title,
                style = MaterialTheme.typography.titleSmall,
                color = SoftColors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            CardWords.subtitle(card)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = SoftColors.text2, maxLines = 1)
            }
            // Source and age only when the card's data carries them (ADR 0081, 2026-11-10).
            CardProvenanceWords.provenance(card, System.currentTimeMillis(), ZoneId.systemDefault())?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                    color = SoftColors.text2,
                    maxLines = 1,
                )
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
}

@Composable
private fun CardBody(card: ConversationCard) {
    when (card) {
        is ConversationCard.Bars -> BarsBody(card)
        is ConversationCard.Timeline -> {
            card.items.groupBy { it.date }.forEach { (date, items) ->
                if (date != null) DayHeading(dayHeading(date))
                items.forEach { Line(CardWords.timeline(it)) }
            }
            MoreLine(card.more)
        }
        is ConversationCard.Car -> CardWords.car(card).forEach { Line(it) }
        is ConversationCard.Steps -> {
            card.items.forEach { Line(CardWords.step(it)) }
            card.totalMs?.let { Line("Total: ${CardWords.seconds(it)}") }
        }
        is ConversationCard.Checklist -> {
            card.items.forEach { Line(CardWords.check(it)) }
            MoreLine(card.more)
        }
    }
}

@Composable
private fun MoreLine(count: Int) {
    if (count > 0) Line("And $count more.")
}

@Composable
private fun DayHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = SoftColors.text2,
        modifier = Modifier.padding(top = 4.dp),
    )
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
                Text(
                    p.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SoftColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(BarLabelWidth),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(BarHeight)
                        .clip(RoundedCornerShape(4.dp))
                        .background(SoftColors.cardHighest),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth((p.value.coerceAtLeast(0).toFloat() / top).coerceIn(0f, 1f))
                            .height(BarHeight)
                            .background(if (p.flag == null) SoftColors.primary else SoftColors.caution),
                    )
                }
            }
            Text(CardWords.barValue(p, card.unit), style = MaterialTheme.typography.bodySmall, color = SoftColors.text2)
        }
    }
    MoreLine(card.more)
}

// --- previews --------------------------------------------------------------------------------

@Suppress("MagicNumber") // preview figures
@Preview(name = "Card: spending bars", widthDp = 384)
@Composable
private fun PreviewBarsCard() = SoftTheme {
    ConversationCardView(
        ConversationCard.Bars(
            "Spending, $1,128.44 in all", "usd",
            listOf(
                BarPoint("Gas", 99_999L, CardFlag.UNVERIFIED),
                BarPoint("Food", 12_345L),
                BarPoint("Fun", 500L, CardFlag.ESTIMATE),
            ),
            period = "November 2026", scope = "US",
        ),
        onDismiss = {},
    )
}
