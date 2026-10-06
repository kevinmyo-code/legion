package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.service.ChatEntry
import com.kevin.legion.service.Phase
import com.kevin.legion.ui.assistant.AssistantStripContent
import com.kevin.legion.ui.assistant.AssistantStripResolver
import com.kevin.legion.ui.assistant.ChatPanelContent
import com.kevin.legion.ui.assistant.LocalTypedChat
import com.kevin.legion.ui.assistant.TypedChatUi
import com.kevin.legion.ui.theme.soft.SoftTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Web-assistant ticket 09 (panel rework 2026-10-05): the assistant strip WITH its chat button, and
 * the chat panel's body. The existing [AssistantStripScreenshotTest] baselines are the no-regression
 * half - they render the strip with no [LocalTypedChat] and must still match byte for byte. Baselines
 * recorded before the rework showed a typed box on the strip; they were re-recorded on purpose.
 * Same runner, graphics mode and device qualifier as that test; the strip is bottom-aligned here as
 * it is in the app's Scaffold.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
class AssistantTypedStripScreenshotTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun chat(entries: List<ChatEntry> = emptyList(), pending: String? = null) = TypedChatUi(
        companionName = "Dorothy",
        entries = entries,
        pendingReply = pending,
        onSend = {},
        onNewConversation = {},
    )

    private val conversation = listOf(
        ChatEntry(1, ChatEntry.Kind.USER, "When did we last buy shampoo?", ChatEntry.Via.TYPED),
        ChatEntry(2, ChatEntry.Kind.TOOL, "Search bought log"),
        ChatEntry(3, ChatEntry.Kind.ASSISTANT, "You logged shampoo on Sep 28.", ChatEntry.Via.TYPED),
        ChatEntry(4, ChatEntry.Kind.USER, "What is on the calendar?", ChatEntry.Via.SPOKEN),
        ChatEntry(5, ChatEntry.Kind.ASSISTANT, "Soccer practice at five.", ChatEntry.Via.SPOKEN),
        ChatEntry(6, ChatEntry.Kind.SYSTEM, "Stopped speaking because you typed."),
    )

    @Test
    fun `idle shows the full width talk pill with a chat button at the end`() {
        capture("assistant-typed-idle.png", chat()) {
            AssistantStripContent(
                AssistantStripResolver.resolve(Phase.IDLE, "", null, micGranted = true, silenced = false),
                onTap = {},
            )
        }
    }

    @Test
    fun `an unread typed reply puts a dot on the chat button`() {
        capture("assistant-typed-unread.png", chat().copy(unreadReply = true)) {
            AssistantStripContent(
                AssistantStripResolver.resolve(Phase.IDLE, "", null, micGranted = true, silenced = false),
                onTap = {},
            )
        }
    }

    @Test
    fun `mic blocked keeps its words in the pill beside the chat button`() {
        capture("assistant-typed-mic-blocked.png", chat()) {
            AssistantStripContent(
                AssistantStripResolver.resolve(Phase.IDLE, "", null, micGranted = false, silenced = false),
                onTap = {},
            )
        }
    }

    @Test
    fun `the panel shows typed and spoken turns tagged in words`() {
        capturePanel("assistant-panel-conversation.png", chat(conversation, pending = ""), PANEL_TALL)
    }

    @Test
    fun `a shorter panel standing in for the keyboard keeps the composer visible`() {
        // Robolectric has no IME, so the keyboard's share of the screen is stood in for by a shorter
        // host: the transcript must give way and the composer stay on screen.
        capturePanel("assistant-panel-keyboard-space.png", chat(conversation.take(3)), PANEL_SHORT)
    }

    private fun capturePanel(fileName: String, typed: TypedChatUi, height: Dp) {
        composeTestRule.setContent {
            SoftTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        ChatPanelContent(typed, onTalk = {}, onClose = {}, Modifier.height(height), autoFocus = false)
                    }
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(fileName)
    }

    private fun capture(fileName: String, typed: TypedChatUi, content: @Composable () -> Unit) {
        composeTestRule.setContent {
            SoftTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        CompositionLocalProvider(LocalTypedChat provides typed) { content() }
                    }
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(fileName)
    }
}

private val PANEL_TALL = 640.dp
private val PANEL_SHORT = 300.dp
