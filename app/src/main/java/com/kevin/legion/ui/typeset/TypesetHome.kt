package com.kevin.legion.ui.typeset

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kevin.legion.R
import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.ui.assistant.ConversationCardView
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.TypesetType

/**
 * The Typeset front page (ADR 0081, `proto-typeset.html`): a ruled column. Masthead, then either the
 * glance (dateline, the time as the headline, three figures, the contents line) or, once Alfred has
 * answered, his cards newest first under the orb. The orb sits at the foot with the state in words,
 * then the dock of six monogram tiles and "All apps".
 *
 * Stateless and not yet wired into the shell: `HomeScreen` and the shell's bottom bar
 * (`AssistantStrip`) still draw today's home. Wiring is the next step of Phase B.
 */
@Composable
@Suppress("LongParameterList")
fun TypesetHomeContent(
    glance: TypesetGlance,
    phase: OrbPhase,
    status: String,
    /** Off-normal shell words under the Controls link (not synced, OBD, quiet, alarms); null draws nothing. */
    statusWords: String?,
    night: Boolean,
    cards: List<ConversationCard>,
    actions: TypesetHomeActions,
    modifier: Modifier = Modifier,
    dock: List<TypesetDockTile> = TypesetHomeDerive.DOCK,
) {
    val answering = cards.isNotEmpty()
    Column(modifier.fillMaxWidth().padding(horizontal = Gutter)) {
        Masthead(answering, night, statusWords, actions)
        if (!answering) Glance(glance, actions)
        Stage(phase, status, large = phase != OrbPhase.IDLE && !answering, actions)
        // Newest answer directly under the orb.
        cards.asReversed().forEach { card ->
            ConversationCardView(card, onDismiss = actions.onDismissCard, modifier = Modifier.padding(bottom = 8.dp))
        }
        Spacer(Modifier.heightIn(min = 8.dp))
        Dock(dock, actions)
    }
}

@Composable
private fun Masthead(answering: Boolean, night: Boolean, statusWords: String?, actions: TypesetHomeActions) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            statusWords?.let {
                Text(it, style = TypesetType.small, color = SoftColors.text2, modifier = Modifier.padding(end = 8.dp))
            }
            Text(
                "CONTROLS",
                style = TypesetType.caps.copy(textDecoration = TextDecoration.Underline),
                color = SoftColors.text,
                modifier = Modifier.tap("Controls", actions.onControls),
            )
        }
        Box(
            Modifier.size(MinTarget).clickable(role = Role.Button, onClick = actions.onToggleEdition)
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
private fun Glance(g: TypesetGlance, actions: TypesetHomeActions) {
    Column(Modifier.padding(top = 16.dp)) {
        Text(g.dateline, style = TypesetType.smallItalic, color = SoftColors.text2)
        Row(
            Modifier.tap("Open the clock", actions.onOpenClock).padding(bottom = 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(g.time, style = TypesetType.time, color = SoftColors.text)
        }
        HorizontalDivider(thickness = 1.dp, color = SoftColors.text)
        Figure(g.next, actions.onOpenToday)
        Figure(g.due, actions.onOpenToday)
        Figure(g.left, actions.onOpenMoney)
        // The contents line: the screens behind, Field and Breathe (Kevin, 2026-11-10), and More
        // (today's sheet: Body, News, Reports, Media, Recordings, Lists, Games, Books keep a way in).
        // Two rows of four, the prototype's grid.
        val contents = listOf(
            "Today" to actions.onOpenToday, "Money" to actions.onOpenMoney, "Car" to actions.onOpenCar,
            "Clock" to actions.onOpenClock, "Field" to actions.onOpenField, "Breathe" to actions.onBreathe,
            "More" to actions.onMore,
        )
        contents.chunked(CONTENTS_PER_ROW).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (name, go) ->
                    Box(Modifier.weight(1f).tap(name, go), contentAlignment = Alignment.CenterStart) {
                        Text(
                            name,
                            style = TypesetType.small.copy(textDecoration = TextDecoration.Underline),
                            color = SoftColors.text,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Figure(f: TypesetFigure, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().tap(f.label, onOpen)) {
        Row(Modifier.padding(vertical = 12.dp)) {
            Column(Modifier.width(FigureColumn), horizontalAlignment = Alignment.End) {
                Text(f.number, style = TypesetType.fig, color = SoftColors.text, textAlign = TextAlign.End)
                f.unit?.let { Text(it, style = TypesetType.caps, color = SoftColors.text2) }
            }
            Column(Modifier.padding(start = 16.dp, top = 3.dp).weight(1f)) {
                Text(f.label, style = TypesetType.bodyStrong, color = SoftColors.text)
                f.lines.forEach { Text(it, style = TypesetType.small, color = SoftColors.text2) }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = SoftColors.barRule)
    }
}

@Composable
private fun Stage(phase: OrbPhase, status: String, large: Boolean, actions: TypesetHomeActions) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        val size: Dp = if (large) 200.dp else 88.dp
        Box(
            Modifier.size(size).clip(CircleShape).clickable(role = Role.Button, onClick = actions.onTalk)
                .semantics { contentDescription = "Talk to Alfred" },
        ) {
            TypesetOrb(phase, Modifier.size(size))
        }
        Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(status, style = TypesetType.lead, color = SoftColors.text)
            Text(
                "or type",
                style = TypesetType.small.copy(textDecoration = TextDecoration.Underline, fontStyle = FontStyle.Italic),
                color = SoftColors.text2,
                modifier = Modifier.tap("Type a question", actions.onType),
            )
        }
    }
}

@Composable
private fun Dock(tiles: List<TypesetDockTile>, actions: TypesetHomeActions) {
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
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            tiles.forEach { t ->
                Column(
                    Modifier.tap(t.name) { actions.onOpenApp(t) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(44.dp).border(1.dp, SoftColors.outline),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(t.monogram, style = TypesetType.bodyStrong, color = SoftColors.text)
                    }
                    Text(t.name, style = TypesetType.small, color = SoftColors.text2)
                }
            }
        }
    }
}

/** A text control: a 48dp target with a button role and its words as the label. */
private fun Modifier.tap(label: String, onClick: () -> Unit): Modifier =
    this.heightIn(min = MinTarget).clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
        .wrapContentHeight()
        .padding(horizontal = 2.dp)

private const val CONTENTS_PER_ROW = 4
private val Gutter = 16.dp
private val MinTarget = 48.dp
private val FigureColumn = 100.dp
