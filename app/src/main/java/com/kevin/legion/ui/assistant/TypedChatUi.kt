package com.kevin.legion.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.R
import com.kevin.legion.service.ChatEntry
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme

/**
 * Everything [AssistantStripContent] needs to grow a typed box and a reply panel, handed down by a
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
) {
    val hasConversation: Boolean get() = entries.isNotEmpty() || pendingReply != null
}

/** Null = no typed box (the strip exactly as before). Provided by [AssistantStrip]. */
val LocalTypedChat = compositionLocalOf<TypedChatUi?> { null }

private val PillShape = RoundedCornerShape(percent = 50)

/**
 * The typed box beside the talk pill (the Android mock in
 * `.scratch/web-assistant/research/05-prototypes/assistant-prototypes.html`): a 52dp pill field
 * reading "Type to <companion>", the keyboard's Send action and a send button that appears once
 * there is something to send. The draft survives rotation and process death (`rememberSaveable`)
 * and is cleared only when a message is handed off.
 */
@Composable
internal fun TypedMessageField(companionName: String, onSend: (String) -> Unit, modifier: Modifier = Modifier) {
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
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint },
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

/**
 * The session's turns above the strip: typed and spoken, each tagged IN WORDS ("shown, not spoken"
 * / "spoken", "typed" / "spoken" - never an icon or colour alone, CLAUDE.md sec 7). Session-only:
 * the header says "Not saved." because it is true. Follows the newest line as it arrives.
 */
@Composable
internal fun AssistantReplyPanel(
    chat: TypedChatUi,
    modifier: Modifier = Modifier,
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
                .heightIn(max = PANEL_MAX_HEIGHT)
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
        ChatEntry.Kind.ASSISTANT -> ChatLine(companionName, viaTag(entry.via, assistant = true), entry.text, entry.note)
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

private const val INPUT_MAX_LINES = 3
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
private fun PreviewTypedField() = SoftTheme { TypedMessageField("Dorothy", onSend = {}) }
