package com.kevin.legion.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kevin.legion.assistant.cards.BarPoint
import com.kevin.legion.assistant.cards.CardFlag
import com.kevin.legion.assistant.cards.CardProvenance
import com.kevin.legion.assistant.cards.ChecklistLine
import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.assistant.cards.TimelineItem
import com.kevin.legion.assistant.cards.TimelineKind
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.TypesetType
import com.kevin.legion.ui.typeset.TypesetFigure
import com.kevin.legion.ui.typeset.TypesetGlance

/**
 * Made-up answer cards for the card-surface renders (the repo is public): one short card, and a
 * stack of three where the newest is a long week that must scroll inside its card. Fixed dates only,
 * never the real clock, so a baseline does not rot.
 */
internal object CardFixtures {

    val DUE = ConversationCard.Checklist(
        title = "Due today",
        listName = "Due today",
        items = listOf(
            ChecklistLine("Essay draft", false),
            ChecklistLine("Pick up the parcel", false),
            ChecklistLine("Water the plants", true),
        ),
        provenance = CardProvenance(source = "Calendar", throughMs = 1_794_700_800_000L),
    )

    val SPEND = ConversationCard.Bars(
        title = "Spending, \$1,128.44 in all",
        unit = "usd",
        points = listOf(
            BarPoint("Groceries", 41_250L),
            BarPoint("Fuel", 28_900L, CardFlag.UNVERIFIED),
            BarPoint("Eating out", 19_844L),
            BarPoint("Books", 12_000L, CardFlag.ESTIMATE),
            BarPoint("Transit", 10_850L),
        ),
        period = "November 2026",
        scope = "US",
        note = "USD 12.00 is not assigned to a category.",
    )

    /** Twelve items over five days: longer than any phone's card slot, so it scrolls in the card. */
    val WEEK = ConversationCard.Timeline(
        title = "This week",
        date = "2026-11-09",
        zone = "UTC-06:00",
        items = listOf(
            TimelineItem("09:00", "10:15", false, "Lecture", TimelineKind.EVENT, date = "2026-11-09"),
            TimelineItem("12:30", null, false, "Lunch with the study group", TimelineKind.EVENT, date = "2026-11-09"),
            TimelineItem("23:59", null, false, "Problem set 7", TimelineKind.TODO, date = "2026-11-09"),
            TimelineItem("08:00", "09:00", false, "Gym", TimelineKind.EVENT, date = "2026-11-10"),
            TimelineItem("14:00", "15:30", false, "Lab", TimelineKind.EVENT, date = "2026-11-10"),
            TimelineItem(null, null, true, "Library book due", TimelineKind.TODO, date = "2026-11-11"),
            TimelineItem("10:00", "11:00", false, "Office hours", TimelineKind.EVENT, date = "2026-11-11"),
            TimelineItem("18:00", null, false, "Call home", TimelineKind.SUGGESTION, date = "2026-11-11"),
            TimelineItem("09:00", "10:15", false, "Lecture", TimelineKind.EVENT, date = "2026-11-12"),
            TimelineItem("16:00", "17:00", false, "Club meeting", TimelineKind.EVENT, date = "2026-11-12"),
            TimelineItem("11:00", null, false, "Dentist", TimelineKind.EVENT, date = "2026-11-13"),
            TimelineItem("23:59", null, false, "Essay final", TimelineKind.TODO, date = "2026-11-13"),
        ),
        more = 2,
        period = "Mon Nov 9 to Fri Nov 13",
    )

    /** Oldest first, as the store holds them: the newest (the week) is last. */
    val ONE = listOf(DUE)
    val THREE = listOf(DUE, SPEND, WEEK)

    val GLANCE = TypesetGlance(
        dateline = "Monday, 9 November",
        time = "14:20",
        next = TypesetFigure("15:30", null, "Study group", listOf("in 1 h 10 min, the library")),
        due = TypesetFigure("3", null, "due today", listOf("Essay draft", "Pick up the parcel", "and 1 more")),
        left = TypesetFigure("\$1,240", null, "left for November", listOf("of \$2,000, 21 days left")),
    )

    /** What Alfred's caption tail reads while he speaks. */
    const val CAPTION = "Three things are due today, and the essay draft is the big one."
}

/** A stand-in for a route other than HOME (a Money screen), so the card has something to cover. */
@Composable
internal fun FakeRoute() {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Money", style = TypesetType.lead, color = SoftColors.text)
        listOf("Groceries  \$412.50", "Fuel  \$289.00", "Eating out  \$198.44", "Books  \$120.00").forEach {
            HorizontalDivider(thickness = 1.dp, color = SoftColors.barRule)
            Text(it, style = TypesetType.body, color = SoftColors.text, modifier = Modifier.padding(vertical = 12.dp))
        }
    }
}
