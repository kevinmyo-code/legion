package com.kevin.legion.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.kevin.legion.R
import com.kevin.legion.service.ChatEntry
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors

/**
 * Whether the chat panel is open, and whether a typed reply landed while it was shut (web-assistant
 * ticket 09, reworked 2026-10-05: Kevin found an always-visible field kept raising the keyboard).
 *
 * Opening is the ONLY thing that ever focuses the text field or raises the keyboard; a reply that
 * arrives while closed sets [unread] and nothing else. Only a TYPED assistant reply counts: a spoken
 * one was heard, and "behaves as before".
 *
 * Plain state, no Android types, so the open/close/unread rules are unit-testable on the JVM.
 */
@Stable
class ChatPanelState {
    var open by mutableStateOf(false)
        private set

    /** A typed reply arrived since the panel was last open. Cleared by [show]. */
    var unread by mutableStateOf(false)
        private set

    /** Highest entry id already accounted for; null until the first [observe] sets the baseline. */
    private var seenId: Long? = null

    fun show() {
        open = true
        unread = false
    }

    fun hide() {
        open = false
    }

    /**
     * Feed every transcript change here. The first call only records a baseline, so a conversation
     * already on screen when the strip appears (assistant toggled off and on, say) is not "new".
     * A transcript that restarts its ids ("New conversation" clears it) resets the baseline.
     */
    fun observe(entries: List<ChatEntry>) {
        val last = entries.lastOrNull()?.id ?: 0L
        val seen = seenId
        seenId = last
        if (open) {
            unread = false
            return
        }
        if (seen == null || last < seen) return
        if (entries.any { it.id > seen && it.kind == ChatEntry.Kind.ASSISTANT && it.via == ChatEntry.Via.TYPED }) {
            unread = true
        }
    }
}

/** The chat button's accessible label, from the active companion's name (never a literal name). */
internal fun chatButtonLabel(companionName: String, unread: Boolean): String =
    "Chat with $companionName" + if (unread) ", new reply" else ""

private const val BUTTON_DP = 52

/**
 * The compact chat button at the strip's end. A dot marks an unread typed reply, and the label says
 * so in words ("new reply") so the dot is never the only carrier of the fact (CLAUDE.md sec 7).
 * It only ever calls [TypedChatUi.onOpenChat]; it never takes focus on its own.
 */
@Composable
internal fun ChatButton(chat: TypedChatUi, modifier: Modifier = Modifier) {
    val label = chatButtonLabel(chat.companionName, chat.unreadReply)
    Box(
        modifier = modifier
            .size(BUTTON_DP.dp)
            .clip(PillShape)
            .border(1.dp, SoftColors.outline, PillShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = chat.onOpenChat)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        MsIcon(R.drawable.ms_chat, contentDescription = null, tint = SoftColors.text)
        if (chat.unreadReply) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 10.dp)
                    .size(10.dp)
                    .clip(PillShape)
                    .background(SoftColors.primary),
            )
        }
    }
}

/**
 * The chat panel as a Material modal bottom sheet, expanded (no half-way stop). A sheet, not a
 * full-screen route: it is a mode laid over wherever the user is (the assistant is a mode, not a
 * place), swipe-down and back close it for free, and it is its own window, so the keyboard resizes
 * the sheet and never the strip underneath. It is modal, so the strip's talk pill is covered while
 * it is open; [onTalk] puts push-to-talk inside the panel's composer instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatPanelSheet(chat: TypedChatUi, onTalk: () -> Unit, onClose: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SoftColors.ground,
    ) {
        ChatPanelContent(chat, onTalk, onClose, Modifier.fillMaxHeight(PANEL_HEIGHT_FRACTION))
    }
}

/**
 * The panel's body, separate from the sheet window so it can be tested and screenshotted without
 * one. On entering composition it focuses the field and raises the keyboard - the only place that
 * ever happens. [onClose] runs after the focus is cleared and the keyboard hidden, so the keyboard
 * is gone before the sheet is.
 */
@Composable
internal fun ChatPanelContent(
    chat: TypedChatUi,
    onTalk: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    val close = {
        focusManager.clearFocus()
        keyboard?.hide()
        onClose()
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Chat with ${chat.companionName}",
                style = MaterialTheme.typography.titleMedium,
                color = SoftColors.text,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .size(48.dp)
                    .clip(PillShape)
                    .clickable(role = Role.Button, onClickLabel = "Close chat", onClick = close),
                contentAlignment = Alignment.Center,
            ) {
                MsIcon(R.drawable.ms_close, contentDescription = "Close chat", tint = SoftColors.text2)
            }
        }
        AssistantReplyPanel(chat, Modifier.weight(1f, fill = false), maxTranscriptHeight = null)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TypedMessageField(chat.companionName, chat.onSend, focusRequester, Modifier.weight(1f))
            Box(
                Modifier
                    .size(BUTTON_DP.dp)
                    .clip(PillShape)
                    .background(SoftColors.primaryContainer)
                    .clickable(role = Role.Button, onClickLabel = "Talk instead", onClick = onTalk),
                contentAlignment = Alignment.Center,
            ) {
                MsIcon(R.drawable.ms_mic, contentDescription = "Talk instead", tint = SoftColors.onPrimaryContainer)
            }
        }
    }
}

/**
 * The typed field: a 52dp pill reading "Type to <companion>", the keyboard's Send action and a send
 * button once there is something to send. The draft survives rotation and process death
 * (`rememberSaveable`) and is cleared only when a message is handed off. [focusRequester] is the
 * panel's, so only the panel decides when this takes focus.
 */
@Composable
internal fun TypedMessageField(
    companionName: String,
    onSend: (String) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val hint = "Type to $companionName"
    val submit = {
        val text = draft.trim()
        if (text.isNotEmpty()) {
            onSend(text)
            draft = ""
        }
    }
    Row(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clip(PillShape)
            .background(SoftColors.cardHigh)
            .border(1.dp, SoftColors.outline, PillShape)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .semantics { contentDescription = hint },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = SoftColors.text),
                cursorBrush = SolidColor(SoftColors.primary),
                maxLines = INPUT_MAX_LINES,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                decorationBox = { inner ->
                    Box {
                        if (draft.isEmpty()) {
                            Text(
                                hint,
                                style = MaterialTheme.typography.bodyLarge,
                                color = SoftColors.text3,
                                maxLines = 1,
                            )
                        }
                        inner()
                    }
                },
            )
        }
        if (draft.isNotBlank()) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(PillShape)
                    .clickable(role = Role.Button, onClickLabel = "Send", onClick = { submit() }),
                contentAlignment = Alignment.Center,
            ) {
                MsIcon(R.drawable.ms_send, contentDescription = "Send", tint = SoftColors.primary)
            }
        }
    }
}

private const val INPUT_MAX_LINES = 3
private const val PANEL_HEIGHT_FRACTION = 0.85f
