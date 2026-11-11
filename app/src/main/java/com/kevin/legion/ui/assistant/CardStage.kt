package com.kevin.legion.ui.assistant

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kevin.legion.R
import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.assistant.cards.PhoneCards
import com.kevin.legion.ui.theme.deckMotionEnabled
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.TypesetType
import kotlinx.coroutines.launch
import java.time.ZoneId

/** Test tag on each card page. */
internal const val CARD_PAGE_TAG = "card-page"

/** Test tag on the stage: the slot the cards fill. */
internal const val CARD_STAGE_TAG = "card-stage"

/** The full screen behind a card: what its "Open" link leads to, in words. */
enum class CardDestination(val words: String) {
    TODAY("Open Today"),
    MONEY("Open Money"),
    CAR("Open Car"),
    LISTS("Open Lists"),
}

/**
 * The screen a card opens. A spend figure in dollars opens Money, a day or a week opens Today, a
 * car opens Car, the to-do list opens Today and any other list opens Lists. A run of steps and a
 * bar chart that is not money have no screen behind them: null, no link.
 */
fun cardDestination(card: ConversationCard): CardDestination? = when (card) {
    is ConversationCard.Timeline -> CardDestination.TODAY
    is ConversationCard.Bars -> if (card.unit == "usd") CardDestination.MONEY else null
    is ConversationCard.Car -> CardDestination.CAR
    is ConversationCard.Checklist ->
        if (card.listName == PhoneCards.TODO_LIST_NAME) CardDestination.TODAY else CardDestination.LISTS
    is ConversationCard.Steps -> null
}

/**
 * Alfred's answer cards filling the slot they are given (Kevin, 2026-11-10: "i want them like how
 * midcon dash does it"). One card to a page, newest first, each drawn whole: no collapsed one-liner,
 * long content scrolls inside its own card while the page around it stays put. Several cards page
 * sideways, with "1 of 3" and Newer / Older in words for a hand that does not swipe. Each card has
 * its own Close and, where a screen sits behind it, an Open link; "Close all" puts the stack away.
 *
 * [cards] is the store's order, oldest first. A new card turns the stage back to page one, and the
 * stage arrives with a short fade and rise, none with animations off.
 */
@Composable
fun CardStage(
    cards: List<ConversationCard>,
    onClose: (ConversationCard) -> Unit,
    modifier: Modifier = Modifier,
    onOpen: ((CardDestination) -> Unit)? = null,
    onCloseAll: (() -> Unit)? = null,
) {
    if (cards.isEmpty()) return
    val pages = cards.asReversed()
    val pager = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val motion = deckMotionEnabled()
    val newest = pages.first()
    val appear = remember { Animatable(1f) }
    LaunchedEffect(newest) {
        if (pager.currentPage != 0) pager.scrollToPage(0)
        if (motion) {
            appear.snapTo(0f)
            appear.animateTo(1f, tween(ARRIVE_MS))
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .testTag(CARD_STAGE_TAG)
            .graphicsLayer {
                alpha = appear.value
                translationY = (1f - appear.value) * RISE.toPx()
            },
    ) {
        if (pages.size > 1) {
            StageBar(
                at = pager.currentPage,
                of = pages.size,
                onNewer = { scope.launch { if (motion) pager.animateScrollToPage(it) else pager.scrollToPage(it) } },
                onCloseAll = onCloseAll,
            )
        }
        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            pageSpacing = 16.dp,
            verticalAlignment = Alignment.Top,
            key = { System.identityHashCode(pages[it]) },
        ) { i ->
            CardPage(pages[i], onClose = { onClose(pages[i]) }, onOpen = onOpen, modifier = Modifier.fillMaxHeight())
        }
    }
}

/** "1 of 3" and the two ways to turn the page, in words; "Close all" when the caller offers it. */
@Composable
private fun StageBar(at: Int, of: Int, onNewer: (Int) -> Unit, onCloseAll: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${at + 1} of $of",
            style = TypesetType.smallItalic,
            color = SoftColors.text2,
            modifier = Modifier.semantics { contentDescription = "Card ${at + 1} of $of, newest first" },
        )
        Spacer(Modifier.weight(1f))
        if (at > 0) Link("Newer") { onNewer(at - 1) }
        if (at < of - 1) Link("Older") { onNewer(at + 1) }
        onCloseAll?.let { Link("Close all", it) }
    }
}

@Composable
private fun Link(words: String, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = TARGET).clickable(role = Role.Button, onClickLabel = words, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(words, style = TypesetType.small.copy(textDecoration = TextDecoration.Underline), color = SoftColors.text)
    }
}

/**
 * One card, whole: a rule above (Typeset matter, no box), the title as a lead with its period, scope
 * and source under it, the body in ruled rows at body size, its note, a rule below, and the Open
 * link. The body takes the height left and scrolls inside it; a short card ends where it ends.
 */
@Composable
internal fun CardPage(
    card: ConversationCard,
    onClose: () -> Unit,
    onOpen: ((CardDestination) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().testTag(CARD_PAGE_TAG)) {
        HorizontalDivider(thickness = 1.dp, color = SoftColors.text)
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 10.dp, bottom = 6.dp)) {
                Text(
                    card.title,
                    style = TypesetType.lead,
                    color = SoftColors.text,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                CardWords.subtitle(card)?.let { Text(it, style = TypesetType.small, color = SoftColors.text2) }
                // Source and age only when the card's data carries them (ADR 0081, 2026-11-10).
                CardProvenanceWords.provenance(card, System.currentTimeMillis(), ZoneId.systemDefault())?.let {
                    Text(it, style = TypesetType.smallItalic, color = SoftColors.text2)
                }
            }
            Box(
                Modifier.size(TARGET).clickable(role = Role.Button, onClick = onClose)
                    .semantics { contentDescription = "Close card" },
                contentAlignment = Alignment.Center,
            ) {
                MsIcon(R.drawable.ms_close, contentDescription = null, tint = SoftColors.text2, size = 20.dp)
            }
        }
        HorizontalDivider(thickness = 1.dp, color = SoftColors.barRule)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            CardBody(card)
            card.note?.let {
                Text(
                    it,
                    style = TypesetType.smallItalic,
                    color = SoftColors.text2,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp), thickness = 1.dp, color = SoftColors.text)
        val open = cardDestination(card)
        if (open != null && onOpen != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    Modifier.heightIn(min = TARGET)
                        .clickable(role = Role.Button, onClickLabel = open.words) { onOpen(open) }
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        open.words.uppercase(),
                        style = TypesetType.caps.copy(textDecoration = TextDecoration.Underline),
                        color = SoftColors.text,
                    )
                }
            }
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
                items.forEach { TimeRow(timelineTime(it), timelineTitle(it)) }
            }
            MoreLine(card.more)
        }
        is ConversationCard.Car -> CardWords.car(card).forEach { RuledLine(it) }
        is ConversationCard.Steps -> {
            card.items.forEach { RuledLine(CardWords.step(it)) }
            card.totalMs?.let { RuledLine("Total: ${CardWords.seconds(it)}") }
        }
        is ConversationCard.Checklist -> {
            card.items.forEach { RuledLine(CardWords.check(it), done = it.done) }
            MoreLine(card.more)
        }
    }
}

@Composable
private fun DayHeading(text: String) {
    Text(
        text.uppercase(),
        style = TypesetType.caps,
        color = SoftColors.text2,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
    )
}

/** A ruled row of words; a done item is set in the meta ink, its "Done:" still in words. */
@Composable
private fun RuledLine(text: String, done: Boolean = false) {
    Text(
        text,
        style = TypesetType.body,
        color = if (done) SoftColors.text2 else SoftColors.text,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
    HorizontalDivider(thickness = 1.dp, color = SoftColors.barRule)
}

/** A timeline row: the time in a narrow meta column, the words beside it. */
@Composable
private fun TimeRow(time: String, words: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(time, style = TypesetType.small, color = SoftColors.text2, modifier = Modifier.weight(TIME_COLUMN))
        Text(words, style = TypesetType.body, color = SoftColors.text, modifier = Modifier.weight(1f - TIME_COLUMN))
    }
    HorizontalDivider(thickness = 1.dp, color = SoftColors.barRule)
}

@Composable
private fun MoreLine(count: Int) {
    if (count > 0) {
        Text(
            "And $count more.",
            style = TypesetType.smallItalic,
            color = SoftColors.text2,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * Bars as ruled rows: the label and its figure on one line, the bar under them in ink on the rule
 * colour. A flagged figure says "(unverified)" or "(estimate)" in words; its bar takes the accent,
 * which ADR 0081 spends only on flags.
 */
@Composable
private fun BarsBody(card: ConversationCard.Bars) {
    val top = card.points.maxOfOrNull { it.value }?.takeIf { it > 0 } ?: 1L
    card.points.forEach { p ->
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    p.label,
                    style = TypesetType.body,
                    color = SoftColors.text,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                Text(
                    CardWords.barValue(p, card.unit),
                    style = TypesetType.bodyStrong,
                    color = SoftColors.text,
                    textAlign = TextAlign.End,
                )
            }
            Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(BAR).background(SoftColors.barRule)) {
                Box(
                    Modifier.fillMaxWidth((p.value.coerceAtLeast(0).toFloat() / top).coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(if (p.flag == null) SoftColors.text else SoftColors.caution),
                )
            }
        }
        HorizontalDivider(thickness = 1.dp, color = SoftColors.barRule)
    }
    MoreLine(card.more)
}

private val TARGET = 48.dp
private val BAR = 6.dp
private val RISE = 12.dp
private const val ARRIVE_MS = 220
private const val TIME_COLUMN = 0.34f
