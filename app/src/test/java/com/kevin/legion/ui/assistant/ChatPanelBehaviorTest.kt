package com.kevin.legion.ui.assistant

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.kevin.legion.service.ChatEntry
import com.kevin.legion.service.Phase
import com.kevin.legion.ui.theme.soft.SoftTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The tap-to-open chat panel's contract (Kevin, 2026-10-05): the strip never holds a text field, so
 * nothing on it can take focus; opening the panel focuses the field; closing clears focus.
 * The sheet's own window is not exercised here - [ChatPanelContent] is the body it hosts.
 */
@RunWith(RobolectricTestRunner::class)
class ChatPanelBehaviorTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun chat(companion: String = "Dorothy", unread: Boolean = false, onOpen: () -> Unit = {}) = TypedChatUi(
        companionName = companion,
        entries = listOf(ChatEntry(1, ChatEntry.Kind.USER, "hi", ChatEntry.Via.TYPED)),
        pendingReply = null,
        onSend = {},
        onNewConversation = {},
        unreadReply = unread,
        onOpenChat = onOpen,
    )

    private val idle = AssistantStripResolver.resolve(Phase.IDLE, "", null, micGranted = true, silenced = false)

    @Composable
    private fun Strip(typed: TypedChatUi) = SoftTheme {
        CompositionLocalProvider(LocalTypedChat provides typed) { AssistantStripContent(idle, onTap = {}) }
    }

    @Test
    fun `the strip has no text field and so nothing to focus`() {
        rule.setContent { Strip(chat()) }
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        rule.onNodeWithContentDescription("Chat with Dorothy").assertIsNotFocused()
    }

    @Test
    fun `the chat button carries the companion's name and opens the panel on tap`() {
        var opened = 0
        rule.setContent { Strip(chat(onOpen = { opened++ })) }
        rule.onNodeWithContentDescription("Chat with Dorothy").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `an unread reply says so in the button's label`() {
        rule.setContent { Strip(chat(unread = true)) }
        rule.onNode(hasContentDescription("Chat with Dorothy, new reply")).assertExists()
    }

    @Test
    fun `opening the panel focuses its field and closing clears focus`() {
        var closed = 0
        rule.setContent {
            SoftTheme {
                var open by remember { mutableStateOf(false) }
                // The panel stays composed after close here, so "focus cleared" is observable
                // rather than implied by the field vanishing.
                if (open) {
                    ChatPanelContent(chat(), onTalk = {}, onClose = { closed++ })
                } else {
                    Strip(chat(onOpen = { open = true }))
                }
            }
        }
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        rule.onNodeWithContentDescription("Chat with Dorothy").performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).assertIsFocused()
        rule.onNodeWithContentDescription("Close chat").performClick()
        rule.waitForIdle()
        assertEquals(1, closed)
        rule.onNode(hasSetTextAction()).assertIsNotFocused()
    }

    @Test
    fun `the panel placeholder follows the companion`() {
        var companion by mutableStateOf("Dorothy")
        rule.setContent {
            SoftTheme { ChatPanelContent(chat(companion), onTalk = {}, onClose = {}, autoFocus = false) }
        }
        rule.onNode(hasContentDescription("Type to Dorothy")).assertExists()
        companion = "Rose"
        rule.waitForIdle()
        rule.onNode(hasContentDescription("Type to Rose")).assertExists()
    }
}
