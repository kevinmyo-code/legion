@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.kevin.legion.ui.typeset

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.legion.R
import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.ui.assistant.CardStage
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.TypesetType

/**
 * The Typeset front page (ADR 0081, `proto-typeset.html`): a ruled column that fits one screen with
 * no scroll (Kevin, 2026-11-10). The shell's StatusLine sits above it, the same bar as on All apps
 * (sync, alarm, clock, Quiet, Apps, Claude, settings). Top to bottom: masthead; the glance
 * (dateline, the time as the headline, three figures); the orb, the page's hero, centred, with
 * "Type" under it; the contents line; the app groups; "All apps" and the pinned dock.
 *
 * Once Alfred has answered, his cards take the page ([Answering]), as the Mid-Continent phone voice
 * mode does (Kevin, 2026-11-10: "i want them like how midcon dash does it"): the masthead stays,
 * the orb steps back to a small one above the cards and stays the tap to talk, the cards fill the
 * height left, and the last line of the caption and "Type" sit at the foot. The glance, the groups
 * and the dock come back when the cards are put away; All apps stays on the StatusLine above.
 *
 * [FitTiers] picks how much the glance gives up to fit; the time takes the height left over.
 * Stateless: [TypesetHomeHost] wires it.
 */
@Composable
@Suppress("LongParameterList")
fun TypesetHomeContent(
    glance: TypesetGlance,
    phase: OrbPhase,
    status: String,
    night: Boolean,
    cards: List<ConversationCard>,
    actions: TypesetHomeActions,
    modifier: Modifier = Modifier,
    dock: List<TypesetDockTile> = TypesetHomeDerive.DOCK,
    /** The app groups row (Banking, Music, ...), stateful, from HOME; null draws nothing. */
    groups: (@Composable () -> Unit)? = null,
    /** The last line of what Alfred is saying; shown at the foot while his cards are up. */
    caption: String? = null,
) {
    if (cards.isNotEmpty()) {
        Answering(cards, phase, status, caption, night, actions, modifier)
        return
    }
    FitTiers(modifier.fillMaxSize().padding(horizontal = Gutter)) { tier, orb, measuring ->
        val outer = if (measuring) Modifier.fillMaxWidth() else Modifier.fillMaxSize()
        Column(outer) {
            Masthead(answering = false, night, actions)
            // The orb is the hero (Kevin, 2026-11-10: "make the orb more prominent... the orb stays
            // center and about 2.5x"): it sits where the contents line was, and the contents line
            // moves under it.
            Glance(glance, tier, measuring, actions)
            Voice(phase, status, tier, orb, measuring, actions)
            Contents(tier.contentsPerRow, actions)
            groups?.let {
                it()
                HorizontalDivider(thickness = 1.dp, color = SoftColors.barRule)
            }
            Dock(dock, tier, actions)
        }
    }
}

@Composable
private fun Masthead(answering: Boolean, night: Boolean, actions: TypesetHomeActions) {
    Row(Modifier.fillMaxWidth().heightIn(min = TapTarget), verticalAlignment = Alignment.CenterVertically) {
        if (answering) {
            Text(
                "Home",
                style = TypesetType.small.copy(fontWeight = TypesetType.bodyStrong.fontWeight),
                color = SoftColors.text,
                modifier = Modifier.tap("Home, end the conversation", actions.onHome),
            )
        } else {
            Text("LEGION", style = TypesetType.caps, color = SoftColors.text)
        }
        Spacer(Modifier.weight(1f))
        // Controls stays: the feed blocker and its ADR 0066 gate live in that panel, and the
        // StatusLine above has no Feed. The sync/OBD/alarm words are on the StatusLine now.
        Text(
            "CONTROLS",
            style = TypesetType.caps.copy(textDecoration = TextDecoration.Underline),
            color = SoftColors.text,
            maxLines = 1,
            modifier = Modifier.tap("Controls", actions.onControls),
        )
        Box(
            Modifier.size(TapTarget).clickable(role = Role.Button, onClick = actions.onToggleEdition)
                .semantics { contentDescription = "Switch between day and night paper" },
            contentAlignment = Alignment.Center,
        ) {
            val icon = if (night) R.drawable.ms_sunny else R.drawable.ms_dark_mode
            MsIcon(icon, contentDescription = null, tint = SoftColors.text2, size = 20.dp)
        }
    }
    HorizontalDivider(thickness = 1.dp, color = SoftColors.text)
}

@Composable
private fun ColumnScope.Glance(g: TypesetGlance, tier: FitTier, measuring: Boolean, actions: TypesetHomeActions) {
    if (tier.dateline) {
        Text(
            g.dateline,
            style = TypesetType.smallItalic,
            color = SoftColors.text2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = tier.gap),
        )
    }
    // The headline takes the height left over and shrinks to it; one line, never wrapped.
    val slot = if (measuring) Modifier.height(MinTime) else Modifier.weight(1f)
    Box(
        slot.fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Open the clock", onClick = actions.onOpenClock),
        contentAlignment = Alignment.BottomStart,
    ) {
        if (!measuring) {
            FitLine(g.time, TypesetType.time, SoftColors.text, min = 28.sp, modifier = Modifier.fillMaxWidth())
        }
    }
    Spacer(Modifier.height(tier.gap))
    HorizontalDivider(thickness = 1.dp, color = SoftColors.text)
    Figure(g.next, tier, actions.onOpenToday)
    Figure(g.due, tier, actions.onOpenTodos)
    Figure(g.left, tier, actions.onOpenMoney)
}

@Composable
private fun Figure(f: TypesetFigure, tier: FitTier, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().tap(f.label, onOpen)) {
        if (tier.stacked) {
            Row(Modifier.padding(vertical = tier.gap)) {
                Column(Modifier.width(FigureColumn), horizontalAlignment = Alignment.End) {
                    FitLine(
                        f.number,
                        TypesetType.fig,
                        SoftColors.text,
                        min = 17.sp,
                        align = TextAlign.End,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    f.unit?.let { Text(it, style = TypesetType.caps, color = SoftColors.text2) }
                }
                Column(Modifier.padding(start = 16.dp, top = 3.dp).weight(1f)) {
                    OneLine(f.label, TypesetType.bodyStrong, SoftColors.text)
                    f.lines.take(tier.figureLines).forEach { OneLine(it, TypesetType.small, SoftColors.text2) }
                }
            }
        } else {
            Row(Modifier.heightIn(min = TapTarget), verticalAlignment = Alignment.CenterVertically) {
                FitLine(
                    f.number + (f.unit?.let { " $it" } ?: ""),
                    TypesetType.lead,
                    SoftColors.text,
                    min = 14.sp,
                    align = TextAlign.End,
                    modifier = Modifier.width(FigureColumn),
                )
                Text(
                    f.label,
                    style = TypesetType.bodyStrong,
                    color = SoftColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 16.dp).weight(1f),
                )
            }
        }
        HorizontalDivider(thickness = 1.dp, color = SoftColors.barRule)
    }
}

/** The contents line: every place the page leads, in words, five to a row (seven when [FitTier.PACKED]). */
@Composable
private fun Contents(perRow: Int, actions: TypesetHomeActions) {
    val go: Map<String, () -> Unit> = mapOf(
        "Today" to actions.onOpenToday, "Money" to actions.onOpenMoney, "Car" to actions.onOpenCar,
        "Clock" to actions.onOpenClock, "Field" to actions.onOpenField, "Breathe" to actions.onBreathe,
        "Games" to actions.onOpenGames, "Books" to actions.onOpenBooks, "Lists" to actions.onOpenLists,
        "Ideas" to actions.onOpenIdeas, "Brief" to actions.onBrief, "Media" to actions.onOpenMedia,
        "More" to actions.onMore,
    )
    TypesetHomeDerive.CONTENTS.chunked(perRow).forEach { row ->
        Row(Modifier.fillMaxWidth()) {
            row.forEach { name ->
                // Packed rows weigh each cell by its word, so "Breathe" is not clipped beside "Car".
                val w = if (perRow > CONTENTS_PER_ROW) name.length.toFloat() else 1f
                Box(Modifier.weight(w).tap(name, go[name] ?: {}), contentAlignment = Alignment.CenterStart) {
                    FitLine(
                        name,
                        TypesetType.small.copy(textDecoration = TextDecoration.Underline),
                        SoftColors.text,
                        min = 10.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            val gap = if (perRow > CONTENTS_PER_ROW) row.sumOf { it.length }.toFloat() / row.size else 1f
            repeat(perRow - row.size) { Spacer(Modifier.weight(gap)) }
        }
    }
    HorizontalDivider(thickness = 1.dp, color = SoftColors.barRule)
}

/**
 * The page while Alfred's cards are up, laid out as the Mid-Continent phone voice mode lays out a
 * visual: the masthead; the orb, small ([answerOrb]), centred, still the tap to talk, with the state
 * in words under it; the cards in all the height left ([CardStage], long content scrolls inside a
 * card, the page never scrolls); then the caption's last line, two lines held so the cards do not
 * jump as it comes and goes, and "Type".
 */
@Composable
@Suppress("LongParameterList")
private fun Answering(
    cards: List<ConversationCard>,
    phase: OrbPhase,
    status: String,
    caption: String?,
    night: Boolean,
    actions: TypesetHomeActions,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize().padding(horizontal = Gutter)) {
        val orb = answerOrb(maxHeight)
        Column(Modifier.fillMaxSize()) {
            Masthead(answering = true, night, actions)
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(orb).clip(CircleShape).clickable(role = Role.Button, onClick = actions.onTalk)
                        .semantics { contentDescription = "Talk to Alfred" },
                ) {
                    TypesetOrb(phase, Modifier.size(orb))
                }
                Text(
                    status,
                    style = TypesetType.small.copy(fontWeight = TypesetType.bodyStrong.fontWeight),
                    color = SoftColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
            CardStage(
                cards,
                onClose = actions.onCloseCard,
                onOpen = actions.onOpenCard,
                onCloseAll = actions.onDismissCard,
                modifier = Modifier.weight(1f).padding(top = 8.dp),
            )
            Text(
                caption.orEmpty(),
                style = TypesetType.small,
                color = SoftColors.text,
                minLines = CAPTION_LINES,
                maxLines = CAPTION_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TypeButton(actions) }
        }
    }
}

/** The orb while cards are up: Mid-Continent's 96 on a tall page, 64 on a short one. */
internal fun answerOrb(pageHeight: Dp): Dp = if (pageHeight >= TALL_PAGE) ORB_TALL else ORB_SHORT

/** The orb, centred (Kevin, 2026-11-10), the state in words, and "Type" under it. */
@Composable
@Suppress("LongParameterList")
private fun Voice(
    phase: OrbPhase,
    status: String,
    tier: FitTier,
    orb: Dp,
    measuring: Boolean,
    actions: TypesetHomeActions,
) {
    Column(Modifier.fillMaxWidth().padding(top = tier.gap), horizontalAlignment = Alignment.CenterHorizontally) {
        val size: Dp = orb
        Box(
            Modifier.size(size).clip(CircleShape).clickable(role = Role.Button, onClick = actions.onTalk)
                .semantics { contentDescription = "Talk to Alfred" },
        ) {
            if (!measuring) TypesetOrb(phase, Modifier.size(size))
        }
        if (tier.statusOwnLine) {
            Text(
                status,
                style = TypesetType.bodyStrong,
                color = SoftColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            TypeButton(actions)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    status,
                    style = TypesetType.small.copy(fontWeight = TypesetType.bodyStrong.fontWeight),
                    color = SoftColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).padding(end = 12.dp),
                )
                TypeButton(actions)
            }
        }
    }
}

/** "Type": secondary to the orb, smaller, words not a glyph, a 48dp target. */
@Composable
private fun TypeButton(actions: TypesetHomeActions) {
    Box(
        Modifier.heightIn(min = TapTarget)
            .clickable(role = Role.Button, onClickLabel = "Type a question", onClick = actions.onType)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Type",
            style = TypesetType.small.copy(textDecoration = TextDecoration.Underline, fontStyle = FontStyle.Italic),
            color = SoftColors.text2,
        )
    }
}

@Composable
private fun Dock(tiles: List<TypesetDockTile>, tier: FitTier, actions: TypesetHomeActions) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            HorizontalDivider(thickness = 1.dp, color = SoftColors.barRule)
            Text(
                "ALL APPS",
                style = TypesetType.caps.copy(textDecoration = TextDecoration.Underline),
                color = SoftColors.text,
                modifier = Modifier.tap("All apps", actions.onAllApps),
            )
        }
        if (!tier.dockTiles || tiles.isEmpty()) return@Column
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            tiles.forEach { t -> DockTile(t, tier.dockNames, actions) }
        }
    }
}

/** A pinned app: tap opens it, long press offers the old dock's unpin and move, in words. */
@Composable
private fun DockTile(t: TypesetDockTile, named: Boolean, actions: TypesetHomeActions) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier.heightIn(min = TapTarget)
                .combinedClickable(onClick = { actions.onOpenApp(t) }, onLongClick = { menu = true })
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = t.name
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(44.dp).border(1.dp, SoftColors.outline), contentAlignment = Alignment.Center) {
                Text(t.monogram, style = TypesetType.bodyStrong, color = SoftColors.text)
            }
            if (named) OneLine(t.name, TypesetType.small, SoftColors.text2)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            listOf(
                "Unpin ${t.name}" to actions.onUnpin,
                "Move left" to actions.onMoveLeft,
                "Move right" to actions.onMoveRight,
            ).forEach { (words, go) ->
                DropdownMenuItem(
                    text = { Text(words, style = TypesetType.small) },
                    onClick = {
                        menu = false
                        go(t)
                    },
                )
            }
        }
    }
}

private const val CONTENTS_PER_ROW = 5
private val Gutter = 16.dp
private val FigureColumn = 128.dp
private val MinTime = 48.dp
private val TALL_PAGE = 600.dp
private val ORB_TALL = 96.dp
private val ORB_SHORT = 64.dp
private const val CAPTION_LINES = 2
