package com.kevin.legion.ui.news

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kevin.legion.data.local.FeedSubscription
import com.kevin.legion.news.FeedFetchResult
import com.kevin.legion.news.FeedFetcher
import com.kevin.legion.news.FeedSubscriptionController
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.ui.world.NewsDigestCard
import com.kevin.legion.util.clockTime
import kotlinx.coroutines.launch

/**
 * "A news feed page" (Kevin, 2026-09-10) - one-home ticket 07, on ticket 06's resolution. Its own
 * route ([com.kevin.legion.ui.LegionRoute.NEWS]), reached from a row on HOME, the exact shape
 * `ui/ask/AskScreen.kt` got (ticket 06 resolution point 4: "the feed is a row on HOME opening its
 * own route... NOT a tab").
 *
 * Two sections, because they are governed by different rules (ticket 06's whole point):
 * - **Newsletters** - [NewsDigestCard] reused verbatim, unmodified, exactly as it was on HOME
 *   (this ticket's own instruction: "do not touch the Gmail path's posture... do not rewrite it").
 *   Only its CALLER moved - HOME no longer renders it inline, this screen does.
 * - **Feeds** - RSS, new in this ticket. A subscription list ([FeedSubscription], persisted) and,
 *   per feed, a tap-to-check row with its own three-state outcome
 *   ([FeedFetchResult]) - never fetched until tapped ("refresh is a tap, never a poll").
 *
 * **Nothing here is stored beyond the subscription URLs.** A fetched [FeedFetchResult] lives only
 * in [checkStates] (`remember`, no Room row, no cache file) - navigating away and back starts
 * blank again, same posture [NewsDigestCard]'s own doc comment describes for Gmail.
 */
@Composable
fun NewsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sem = LocalLegionSemantics.current

    val subscriptions by remember { FeedSubscriptionController.observeAll(context) }
        .collectAsState(initial = emptyList())

    // Per-subscription id -> its own check state. A [FeedSubscription.id] rather than the URL as
    // the key, matching [subscriptions]' own identity, so a renamed/re-added feed does not
    // silently inherit a stale sibling's state.
    val checkStates = remember { mutableStateMapOf<Long, FeedCheckState>() }

    var newUrl by remember { mutableStateOf("") }
    var newTitle by remember { mutableStateOf("") }
    var addRefusal by remember { mutableStateOf<String?>(null) }

    fun check(subscription: FeedSubscription) {
        checkStates[subscription.id] = FeedCheckState.Loading
        scope.launch {
            val result = FeedFetcher.fetch(subscription.url)
            checkStates[subscription.id] = FeedCheckState.Done(result, System.currentTimeMillis())
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 10.dp)) {
        DeckPane(header = "Newsletters", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            NewsDigestCard()
        }

        DeckPane(header = "Feeds", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            for (subscription in subscriptions) {
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            subscription.title ?: subscription.url,
                            style = MaterialTheme.typography.bodySmall,
                            color = sem.data,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { check(subscription) }) { Text("CHECK") }
                            TextButton(
                                onClick = {
                                    scope.launch { FeedSubscriptionController.remove(context, subscription.id) }
                                    checkStates.remove(subscription.id)
                                },
                            ) { Text("REMOVE") }
                        }
                    }
                    FeedCheckBody(checkStates[subscription.id], sem)
                }
            }
            if (subscriptions.isEmpty()) {
                Text(
                    "No feeds yet - add one below.",
                    style = LegionType.stamp,
                    color = sem.faint,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }

            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                OutlinedTextField(
                    value = newUrl,
                    onValueChange = { newUrl = it; addRefusal = null },
                    label = { Text("Feed URL") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    label = { Text("Name (optional)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                TextButton(
                    modifier = Modifier.padding(top = 4.dp),
                    onClick = {
                        scope.launch {
                            when (val result = FeedSubscriptionController.add(context, newUrl, newTitle.ifBlank { null })) {
                                is FeedSubscriptionController.AddResult.Added -> {
                                    newUrl = ""
                                    newTitle = ""
                                    addRefusal = null
                                }
                                is FeedSubscriptionController.AddResult.Refused -> addRefusal = result.reason
                            }
                        }
                    },
                ) { Text("ADD FEED") }
                addRefusal?.let { reason ->
                    Text(reason, style = LegionType.stamp, color = sem.estimated, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

private sealed class FeedCheckState {
    object Loading : FeedCheckState()
    data class Done(val result: FeedFetchResult, val checkedAtMs: Long) : FeedCheckState()
}

@Composable
private fun FeedCheckBody(state: FeedCheckState?, sem: com.kevin.legion.ui.theme.LegionSemantics) {
    when (state) {
        null -> Unit
        is FeedCheckState.Loading -> Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            Text("Checking...", style = LegionType.stamp, color = sem.faint)
        }
        is FeedCheckState.Done -> Column(Modifier.padding(top = 4.dp)) {
            when (val result = state.result) {
                is FeedFetchResult.Success -> for (headline in result.items) {
                    Text("- ${headline.title}", style = MaterialTheme.typography.bodySmall, color = sem.data)
                }
                is FeedFetchResult.Empty ->
                    Text("No new items right now.", style = LegionType.stamp, color = sem.faint)
                is FeedFetchResult.Unreachable ->
                    Text("Could not reach this feed (${result.detail}).", style = LegionType.stamp, color = sem.estimated)
                is FeedFetchResult.Unparseable ->
                    Text(
                        "This feed replied, but its content could not be read as RSS or Atom (${result.detail}).",
                        style = LegionType.stamp,
                        color = sem.estimated,
                    )
            }
            Text(
                "checked ${clockTime(state.checkedAtMs)}",
                style = LegionType.stamp,
                color = sem.faint,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
