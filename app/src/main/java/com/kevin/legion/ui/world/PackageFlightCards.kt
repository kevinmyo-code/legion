package com.kevin.legion.ui.world

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kevin.legion.service.LiveToolbox
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckTag
import com.kevin.legion.ui.common.DeckTagStyle
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.util.clockTime
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The package and flight cards (command-center ticket 08). Both call THROUGH
 * [LiveToolbox.trackPackage]/[LiveToolbox.flightStatus] on demand - the exact functions
 * `track_package`/`flight_status` dispatch to (see those functions' own KDoc, updated when they
 * were widened from `private` to `internal` for this ticket) - never a second Gmail search or a
 * second extraction prompt. **In-memory only**: [MailCardState] lives in `remember`, nothing is
 * written to Room or a file, and navigating away and back starts blank again - "refresh is a user
 * act" per the ticket, so there is deliberately no cache to serve stale data from.
 *
 * Every card shows, on success, the plain-Kotlin answer line, the mandatory source-mail line
 * (`buildMailSourceLine`, CLAUDE.md's mail read-through rule made structural rather than trusted
 * to the model), an `EST` tag (§4 rule 5 - nothing here reconciles against a printed total, so it
 * is always an estimate, never a fact), and a fetched-at clock time so a card left open does not
 * read as live. On failure, one of four distinct worded states -
 * [LiveToolbox.MailCardFailure.NO_PERMISSION]/[NO_MATCH]/[UNREACHABLE]/[EXTRACTION_FAILED] - so
 * "you haven't connected Gmail" and "nothing matched" and "the network dropped" never collapse
 * into the same blank tile.
 */
private sealed class MailCardState {
    object Loading : MailCardState()
    data class Loaded(
        val answer: String,
        val sourceLine: String,
        val fetchedAtMs: Long,
    ) : MailCardState()
    data class Failed(val kind: LiveToolbox.MailCardFailure?, val rawMessage: String) : MailCardState()
}

/** One worded line per [LiveToolbox.MailCardFailure] - deliberately NOT the raw tool message in
 * three of the four cases, since that message is written to be SPOKEN ("I didn't find any
 * shipping or tracking email...") and this is a short card line read at a glance. [rawMessage] is
 * used only when [kind] is null - a defensive fallback that should never actually trigger, since
 * every failure branch in [LiveToolbox.mailExtraction] tags a kind, but a card must never go blank
 * just because a future failure branch forgets to. */
internal fun failureLine(kind: LiveToolbox.MailCardFailure?, rawMessage: String): String = when (kind) {
    LiveToolbox.MailCardFailure.NO_PERMISSION -> "Gmail isn't connected - grant access in Setup to see this."
    LiveToolbox.MailCardFailure.NO_MATCH -> "Nothing matching in your inbox recently."
    LiveToolbox.MailCardFailure.UNREACHABLE -> "Couldn't reach Gmail - check your connection."
    LiveToolbox.MailCardFailure.EXTRACTION_FAILED -> "Found the mail but couldn't read it through - try refreshing."
    null -> rawMessage.ifBlank { "Couldn't check right now." }
}

/**
 * Shared render for [PackageCard]/[FlightCard] - the same shape, differing only in [header] and
 * which [LiveToolbox] function [fetch] calls.
 */
@Composable
private fun MailInsightCard(header: String, modifier: Modifier = Modifier, fetch: suspend (Context) -> JSONObject) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sem = LocalLegionSemantics.current
    var state by remember { mutableStateOf<MailCardState>(MailCardState.Loading) }

    fun refresh() {
        state = MailCardState.Loading
        scope.launch {
            val result = fetch(context)
            state = if (result.optBoolean("success")) {
                MailCardState.Loaded(
                    answer = result.optString("answer"),
                    sourceLine = result.optString("source_line"),
                    fetchedAtMs = System.currentTimeMillis(),
                )
            } else {
                val kind = runCatching {
                    LiveToolbox.MailCardFailure.valueOf(result.optString(LiveToolbox.MAIL_CARD_FAILURE_KEY))
                }.getOrNull()
                MailCardState.Failed(kind, result.optString("message"))
            }
        }
    }

    // On-demand only (the ticket's own rule) - this LaunchedEffect(Unit) is the card's first
    // paint fetching once, not a poll. Nothing here re-fires on a timer or a recomposition of the
    // surrounding screen; the only other trigger is the REFRESH button below, a user act.
    LaunchedEffect(Unit) { refresh() }

    DeckPane(header = header, modifier = modifier) {
        when (val s = state) {
            is MailCardState.Loading -> Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                Text("Checking your mail...", style = LegionType.stamp, color = sem.faint)
            }
            is MailCardState.Loaded -> {
                Text(s.answer, style = MaterialTheme.typography.bodySmall, color = sem.data)
                Text(
                    s.sourceLine,
                    style = LegionType.stamp,
                    color = sem.faint,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DeckTag("EST", DeckTagStyle.OUTLINE_MUTED)
                    Text(
                        "fetched ${clockTime(s.fetchedAtMs)}",
                        style = LegionType.stamp,
                        color = sem.faint,
                    )
                }
            }
            is MailCardState.Failed -> Text(
                failureLine(s.kind, s.rawMessage),
                style = LegionType.stamp,
                color = sem.faint,
            )
        }
        TextButton(onClick = { refresh() }) { Text("REFRESH") }
    }
}

/** "Where's my package" on glass. Calls [LiveToolbox.trackPackage] directly - see this file's
 * header doc for why that is never a copy of the search-and-extract logic. */
@Composable
fun PackageCard(modifier: Modifier = Modifier) {
    MailInsightCard(header = "Package", modifier = modifier) { context -> LiveToolbox.trackPackage(context) }
}

/** "When's my flight" on glass. Calls [LiveToolbox.flightStatus] directly, same posture as
 * [PackageCard]. */
@Composable
fun FlightCard(modifier: Modifier = Modifier) {
    MailInsightCard(header = "Flight", modifier = modifier) { context -> LiveToolbox.flightStatus(context) }
}
