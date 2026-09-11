package com.kevin.legion.ui.world

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kevin.legion.sitrep.SitrepBuilder
import com.kevin.legion.sitrep.SitrepModule
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.util.clockTime
import kotlinx.coroutines.launch

/**
 * Newsletters digest tile. **Rehomed verbatim a second time - one-home ticket 02, 2026-09-10 -
 * from the now-deleting `ui/MetersScreen.kt` (itself rehomed there verbatim, one-today ticket 07,
 * 2026-09-01, from the deleted `ui/TodayScreen.kt`).** Only the FILE and its visibility changed
 * (private inside a screen -> a public top-level composable other files can call); command-center
 * ticket 01's own build, no logic changed by either move. Wraps [SitrepBuilder.build] scoped to
 * [SitrepModule.NEWS] alone - the exact machinery the scheduled sitrep already uses for its own
 * NEWS section (`SitrepBuilder`'s own class doc: read-through, background Gmail fetch permitted
 * only inside a sitrep the user scheduled or explicitly asked for), never a second summarization
 * path.
 *
 * **Deliberately NO auto-fetch, and the tap is the demand.** This said "the one tile in the world
 * band" until 2026-09-10, when one-home ticket 07 moved it off HOME into `ui/news/NewsScreen.kt`
 * beside the RSS feeds - it is not in a band any more, and it is no longer the only thing here that
 * refuses to poll: the whole News surface does. **The posture is unchanged and is the reason this
 * file was extracted verbatim rather than rewritten** (ticket 02): read-through, nothing to Room,
 * not even the summary, and no fetch until someone asks. Every other reading in
 * that band ([AreaCard] included) fetches once on first compose, which the original ticket still
 * counted as "on demand" (opening the screen is the demand). Newsletters is different by that
 * ticket's own explicit instruction ("On-demand only (a tap)") - a newsletter check folds several
 * message bodies into one prompt and pays for a real LLM call, where the others are one metadata
 * search; the tap is what keeps that cost tied to an actual ask rather than every visit to HOME.
 *
 * In-memory only (`remember`, no Room row, no cache file) - navigating away and back starts blank
 * again - refresh is a user act, never a background poll.
 *
 * **No setup required (command-center ticket 12, Kevin: "take from my gmail > summarize").**
 * [SitrepBuilder.build] falls back to a no-config Gmail search when
 * [com.kevin.legion.sitrep.SitrepSettings.newsletterSenders] is empty
 * (`SitrepBuilder.NO_CONFIG_NEWSLETTER_QUERY`), so this card needs no setup of its own.
 */
@Composable
fun NewsDigestCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sem = LocalLegionSemantics.current
    var state by remember { mutableStateOf<NewsDigestState>(NewsDigestState.Idle) }

    fun check() {
        state = NewsDigestState.Loading
        scope.launch {
            // SitrepBuilder.build already returns every real outcome as its own worded sentence
            // (NewsOutcome's four failure/empty branches plus the happy path) - this card never
            // has to re-derive success/failure, only display what came back.
            val text = SitrepBuilder.build(context, setOf(SitrepModule.NEWS))
            state = NewsDigestState.Ready(text, System.currentTimeMillis())
        }
    }

    DeckPane(header = "Newsletters", modifier = modifier) {
        when (val s = state) {
            is NewsDigestState.Idle -> {
                Text(
                    "Not checked this session - a check reads newsletter-shaped mail from your Gmail and summarizes it.",
                    style = LegionType.stamp,
                    color = sem.faint,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
                TextButton(onClick = { check() }) { Text("CHECK NEWSLETTERS") }
            }
            is NewsDigestState.Loading -> Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                Text("Checking your newsletters...", style = LegionType.stamp, color = sem.faint)
            }
            is NewsDigestState.Ready -> {
                Text(s.text, style = MaterialTheme.typography.bodySmall, color = sem.data)
                Text(
                    "fetched ${clockTime(s.fetchedAtMs)}",
                    style = LegionType.stamp,
                    color = sem.faint,
                    modifier = Modifier.padding(top = 6.dp),
                )
                TextButton(onClick = { check() }) { Text("CHECK AGAIN") }
            }
        }
    }
}

/** [NewsDigestCard]'s own three states - a sealed type for the same reason every other on-demand
 * card in this file's package uses one ([AreaCard]'s own `AreaCardState`): "not yet asked", "asked,
 * waiting", and "asked, got an answer" are three different facts a nullable string cannot keep
 * apart. */
private sealed class NewsDigestState {
    object Idle : NewsDigestState()
    object Loading : NewsDigestState()
    data class Ready(val text: String, val fetchedAtMs: Long) : NewsDigestState()
}
