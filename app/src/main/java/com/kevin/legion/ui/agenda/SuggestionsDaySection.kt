package com.kevin.legion.ui.agenda

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.kevin.legion.calendar.EventSuggestions
import com.kevin.legion.calendar.SuggestionMeta
import com.kevin.legion.data.local.Event
import com.kevin.legion.ui.common.DeckSectionRule
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.ui.theme.soft.AreaAccent
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.util.clockTime
import com.kevin.legion.util.documentDateCompact
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The accent a suggestion wears everywhere on the calendar: NEWS's orange, a hue this screen uses
 * for nothing else (events are CALENDAR blue, todos LISTS yellow). Never the only signal - every
 * row carries [EventSuggestions.LABEL] in words. */
val SUGGESTION_ACCENT = AreaAccent.NEWS

/**
 * The calendar day view's SUGGESTIONS section (Kevin, 2026-10-09). Loads its own rows for
 * `[dayStart, dayStart + 1 day)` and renders nothing at all on a day with none - an empty
 * "no suggestions" card on every day would be noise. A read that FAILED is said in words.
 *
 * Kept out of [com.kevin.legion.ui.CalendarScreen] (already at the 1000-line hook) and out of its
 * SCHEDULE section on purpose: a suggestion is not a plan, so it never sits among plans. Each row
 * offers the only two actions there are ([EventSuggestions.addToPlans], [EventSuggestions.notInterested])
 * and shows what the tap did in words, "queued" included. [onChanged] lets the screen reload, since
 * an added suggestion now belongs in SCHEDULE. A row whose `structured_meta.url` is http(s) also
 * opens that page in the browser when its body is tapped (Kevin, 2026-10-09); the buttons do not.
 */
@Suppress("FunctionNaming") // @Composable convention is PascalCase; detekt's rule does not know it.
@Composable
fun SuggestionsDaySection(dayStart: Long, dayEndExclusive: Long, zone: ZoneId, reloadKey: Int, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf(emptyList<Event>()) }
    var failed by remember { mutableStateOf(false) }
    val outcomes = remember(dayStart) { mutableStateMapOf<Long, String>() }

    LaunchedEffect(dayStart, reloadKey) {
        try {
            rows = EventSuggestions.inLocalWindow(context, dayStart, dayEndExclusive - 1, zone)
            failed = false
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            // Said in words below ("Couldn't load suggestions"), never an empty-looking day.
            rows = emptyList()
            failed = true
        }
    }

    if (!failed && rows.isEmpty() && outcomes.isEmpty()) return
    DeckSectionRule("Suggestions - not plans")
    Column(
        Modifier
            .fillMaxWidth()
            .background(SoftColors.card, MaterialTheme.shapes.large)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        SectionSentences(failed, outcomes.values)
        rows.forEach { row ->
            SuggestionRow(
                row = row,
                onOpen = { url ->
                    openEventPage(context, url)?.let { outcomes[row.id] = "${row.title}: $it" }
                },
                onAdd = {
                    scope.launch {
                        outcomes[row.id] = "${row.title}: ${EventSuggestions.addToPlans(context, row).sentence}"
                        onChanged()
                    }
                },
                onDrop = {
                    scope.launch {
                        outcomes[row.id] = "${row.title}: ${EventSuggestions.notInterested(context, row).sentence}"
                        onChanged()
                    }
                },
            )
        }
    }
}

/** What the section has to say in words: a failed read, then what each tap did (or did not do). */
@Suppress("FunctionNaming") // @Composable convention is PascalCase; detekt's rule does not know it.
@Composable
private fun SectionSentences(failed: Boolean, outcomes: Collection<String>) {
    if (failed) {
        Text(
            "Couldn't load suggestions for this day.",
            style = MaterialTheme.typography.bodyMedium,
            color = LocalLegionSemantics.current.estimated,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
        )
    }
    outcomes.forEach { sentence ->
        Text(
            sentence,
            style = MaterialTheme.typography.bodySmall,
            color = SoftColors.text2,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

/**
 * Opens [url] in the browser. Null when the browser was asked; otherwise what did NOT happen, in
 * words, for the section to show (§7: no outcome is implied when nothing could open it).
 */
private fun openEventPage(context: Context, url: String): String? = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    null
} catch (@Suppress("SwallowedException") e: ActivityNotFoundException) {
    "Couldn't open the event page: no browser on this phone can open it."
}

@Suppress("FunctionNaming") // @Composable convention is PascalCase; detekt's rule does not know it.
@Composable
private fun SuggestionRow(row: Event, onOpen: (String) -> Unit, onAdd: () -> Unit, onDrop: () -> Unit) {
    val meta = SuggestionMeta.parse(row.structuredMeta)
    // Only an http(s) page makes the row tappable; with none, nothing on the row pretends it is.
    val pageUrl = SuggestionMeta.openablePageUrl(meta?.url)
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
        // The colour bar: the accent on the row's leading edge, beside the words that say what it is.
        Box(
            Modifier
                .padding(top = 4.dp, end = 10.dp)
                .width(4.dp)
                .height(40.dp)
                .background(SUGGESTION_ACCENT.onContainer, MaterialTheme.shapes.small),
        )
        Column(Modifier.weight(1f)) {
            // The row body is the tap target for the event page. The two action buttons sit OUTSIDE
            // it, below, so a tap on either can never also open the browser.
            val body = if (pageUrl == null) {
                Modifier.fillMaxWidth()
            } else {
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable(
                        onClickLabel = "Open event page for ${row.title}",
                        role = Role.Button,
                        onClick = { onOpen(pageUrl) },
                    )
            }
            Column(body) {
                SuggestionDetails(row, meta, pageUrl)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onAdd) {
                    Text(
                        "Add to my plans",
                        style = MaterialTheme.typography.labelLarge,
                        color = SUGGESTION_ACCENT.onContainer,
                    )
                }
                TextButton(onClick = onDrop) {
                    Text("Not interested", style = MaterialTheme.typography.labelLarge, color = SoftColors.text2)
                }
            }
        }
    }
}

@Suppress("FunctionNaming") // @Composable convention is PascalCase; detekt's rule does not know it.
@Composable
private fun SuggestionDetails(row: Event, meta: SuggestionMeta?, pageUrl: String?) {
    Text(
        EventSuggestions.LABEL,
        style = MaterialTheme.typography.labelMedium,
        color = SUGGESTION_ACCENT.onContainer,
    )
    Text(row.title, style = MaterialTheme.typography.bodyLarge, color = SoftColors.text)
    val start = row.startsAt
    val whenText = when {
        start == null -> null
        row.allDay -> "${documentDateCompact(start)}, all day"
        else -> clockTime(start)
    }
    listOfNotNull(whenText, meta?.price).joinToString(" - ").takeIf { it.isNotEmpty() }?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = SoftColors.text2)
    }
    (row.location ?: listOfNotNull(meta?.venue, meta?.city).joinToString(", ").ifEmpty { null })?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = SoftColors.text2)
    }
    if (pageUrl != null) {
        // The visible half of the tap target, so the row says it opens something before anyone tries.
        Text(
            "Open event page",
            style = MaterialTheme.typography.labelLarge,
            color = SUGGESTION_ACCENT.onContainer,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.padding(top = 4.dp),
        )
    } else {
        // A link that is not http(s), or an unstructured row's notes: shown as plain text, not tappable.
        (meta?.url ?: row.notes.takeIf { meta == null })?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = SoftColors.text3)
        }
    }
}
