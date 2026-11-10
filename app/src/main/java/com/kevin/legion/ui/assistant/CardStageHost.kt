package com.kevin.legion.ui.assistant

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.assistant.cards.ConversationCards
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme

/**
 * The answer cards on any route but HOME (Kevin, 2026-11-10: "i want them like how midcon dash
 * does it"). Mounted by the shell over the route's own content, under the StatusLine and above the
 * talk strip, so while a card is up it takes the screen between the two: the bar stays at the top,
 * the pill and its last-line caption stay at the bottom. The strip no longer draws a card of its own.
 *
 * Back puts the whole stack away until the next card (the conversation goes on); an Open link puts
 * it away and opens the screen behind the card. Draws nothing while there is no card to show.
 */
@Composable
fun CardStageOverlay(onOpen: (CardDestination) -> Unit, modifier: Modifier = Modifier) {
    val state by ConversationCards.state.collectAsStateWithLifecycle()
    val cards = state.visible
    if (cards.isEmpty()) return
    BackHandler { ConversationCards.dismiss() }
    SoftTheme {
        CardStageSurface(
            cards,
            onClose = ConversationCards::close,
            onOpen = { where ->
                ConversationCards.dismiss()
                onOpen(where)
            },
            onCloseAll = ConversationCards::dismiss,
            modifier = modifier,
        )
    }
}

/**
 * The stage on the edition's paper, opaque, and taking every touch that lands on it so nothing
 * reaches the screen underneath. Stateless, so it renders in a test without the store.
 */
@Composable
internal fun CardStageSurface(
    cards: List<ConversationCard>,
    onClose: (ConversationCard) -> Unit,
    onOpen: ((CardDestination) -> Unit)?,
    onCloseAll: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(SoftColors.ground)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Final).changes.forEach { it.consume() }
                }
            }
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp),
    ) {
        CardStage(cards, onClose = onClose, onOpen = onOpen, onCloseAll = onCloseAll)
    }
}
