package com.kevin.legion.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.service.ChatEntry
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme

/**
 * Everything [AssistantStripContent] needs to grow a chat button, and the chat panel ([ChatPanelSheet])
 * its transcript, handed down by a
 * CompositionLocal ([LocalTypedChat]) rather than as parameters. That is deliberate: it leaves
 * `AssistantStrip`, `AssistantStripContent` and the screenshot tests' call sites byte-for-byte
 * unchanged (null = the strip as it was), and `AssistantStrip` (the state holder) is the one place
 * that provides it.
 */
data class TypedChatUi(
    val companionName: String,
    val entries: List<ChatEntry>,
    val pendingReply: String?,
    val onSend: (String) -> Unit,
    val onNewConversation: () -> Unit,
    /** A typed reply arrived while the chat panel was closed: the chat button wears a dot. */
    val unreadReply: Boolean = false,
    /** The chat button's tap. The default keeps the pre-panel call sites compiling. */
    val onOpenChat: () -> Unit = {},
) {
    val hasConversation: Boolean get() = entries.isNotEmpty() || pendingReply != null
}

/** Null = no typed box (the strip exactly as before). Provided by [AssistantStrip]. */
val LocalTypedChat = compositionLocalOf<TypedChatUi?> { null }

internal val PillShape = RoundedCornerShape(percent = 50)

/**
 * The session's turns inside the chat panel: typed and spoken, each tagged IN WORDS ("shown, not spoken"
 * / "spoken", "typed" / "spoken" - never an icon or colour alone, CLAUDE.md sec 7). Session-only:
 * the header says "Not saved." because it is true. Follows the newest line as it arrives.
 */
@Composable
internal fun AssistantReplyPanel(
    chat: TypedChatUi,
    modifier: Modifier = Modifier,
    /** The transcript's own height cap; null = it takes the room the caller gives it (the sheet). */
    maxTranscriptHeight: Dp? = PANEL_MAX_HEIGHT,
) {
    val scroll = rememberScrollState()
    val lastKey = chat.entries.lastOrNull()?.id
    LaunchedEffect(lastKey, chat.pendingReply) { scroll.animateScrollTo(scroll.maxValue) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SoftColors.card)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "This conversation",
                style = MaterialTheme.typography.titleSmall,
                color = SoftColors.text,
                modifier = Modifier.weight(1f),
            )
            Text(
                "New conversation",
                style = MaterialTheme.typography.labelLarge,
                color = SoftColors.primary,
                modifier = Modifier
                    .clip(PillShape)
                    .clickable(role = Role.Button, onClick = chat.onNewConversation)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
        Text(
            "Not saved. Typed replies are shown, not spoken.",
            style = MaterialTheme.typography.bodySmall,
            color = SoftColors.text2,
        )
        Column(
            modifier = Modifier
                .padding(top = 8.dp)
                .then(if (maxTranscriptHeight != null) Modifier.heightIn(max = maxTranscriptHeight) else Modifier.weight(1f))
                .fillMaxWidth()
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            chat.entries.forEach { ChatEntryRow(it, chat.companionName) }
            chat.pendingReply?.let { partial ->
                ChatLine(
                    who = chat.companionName,
                    tag = "replying, shown not spoken",
                    text = partial.ifBlank { "Replying…" },
                )
            }
        }
    }
}

@Composable
private fun ChatEntryRow(entry: ChatEntry, companionName: String) {
    when (entry.kind) {
        ChatEntry.Kind.USER -> ChatLine("You", viaTag(entry.via, assistant = false), entry.text)
        ChatEntry.Kind.ASSISTANT -> ChatLine(
            entry.speaker ?: companionName,
            viaTag(entry.via, assistant = true),
            entry.text,
            entry.note,
        )
        ChatEntry.Kind.TOOL -> Text(
            if (entry.note == null) "Done: ${entry.text}" else "${entry.text}: ${entry.note}",
            style = MaterialTheme.typography.bodySmall,
            color = if (entry.note == null) SoftColors.text2 else SoftColors.caution,
        )
        ChatEntry.Kind.SYSTEM -> Text(
            entry.text,
            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
            color = SoftColors.text3,
        )
        ChatEntry.Kind.NOT_SENT -> Column {
            if (entry.text.isNotBlank()) {
                Text(entry.text, style = MaterialTheme.typography.bodyMedium, color = SoftColors.text2)
            }
            Text(entry.note.orEmpty(), style = MaterialTheme.typography.bodySmall, color = SoftColors.caution)
        }
    }
}

private fun viaTag(via: ChatEntry.Via, assistant: Boolean): String? = when (via) {
    ChatEntry.Via.TYPED -> if (assistant) "shown, not spoken" else "typed"
    ChatEntry.Via.SPOKEN -> "spoken"
    ChatEntry.Via.NONE -> null
}

@Composable
private fun ChatLine(who: String, tag: String?, text: String, note: String? = null) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(who, style = MaterialTheme.typography.labelMedium, color = SoftColors.text2)
            if (tag != null) Text(tag, style = MaterialTheme.typography.labelSmall, color = SoftColors.text3)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = SoftColors.text)
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = SoftColors.caution)
    }
}

private val PANEL_MAX_HEIGHT = 220.dp

// --- previews: SoftTheme, 384dp, same as the strip's own ------------------------------------------

// Ids are placeholders (0): the panel keys nothing off them in a preview.
private val PreviewChat = TypedChatUi(
    companionName = "Dorothy",
    entries = listOf(
        ChatEntry(0, ChatEntry.Kind.USER, "When did we last buy shampoo?", ChatEntry.Via.TYPED),
        ChatEntry(0, ChatEntry.Kind.TOOL, "Search bought log"),
        ChatEntry(0, ChatEntry.Kind.ASSISTANT, "You logged shampoo on Sep 28.", ChatEntry.Via.TYPED),
        ChatEntry(0, ChatEntry.Kind.USER, "what is on the calendar", ChatEntry.Via.SPOKEN),
        ChatEntry(0, ChatEntry.Kind.ASSISTANT, "Soccer practice at five.", ChatEntry.Via.SPOKEN),
        ChatEntry(0, ChatEntry.Kind.SYSTEM, "Stopped speaking because you typed."),
    ),
    pendingReply = null,
    onSend = {},
    onNewConversation = {},
)

@Preview(name = "Reply panel", widthDp = 384)
@Composable
private fun PreviewReplyPanel() = SoftTheme { AssistantReplyPanel(PreviewChat) }

@Preview(name = "Typed field", widthDp = 384)
@Composable
private fun PreviewTypedField() = SoftTheme { TypedMessageField("Dorothy", onSend = {}, focusRequester = remember { FocusRequester() }) }
