package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.service.ChatEntry
import com.kevin.legion.service.Phase
import com.kevin.legion.ui.assistant.AssistantStripContent
import com.kevin.legion.ui.assistant.AssistantStripResolver
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
 * Web-assistant ticket 09: the assistant strip WITH the typed box (the Android mock in
 * `.scratch/web-assistant/research/05-prototypes/assistant-prototypes.html`). The existing
 * [AssistantStripScreenshotTest] baselines are the no-regression half - they render the strip with
 * no [LocalTypedChat] and must still match byte for byte. Same runner, graphics mode and device
 * qualifier as that test; the strip is bottom-aligned here as it is in the app's Scaffold.
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

    @Test
    fun `idle shows the typed box beside a narrower talk pill`() {
        capture("assistant-typed-idle.png", chat()) {
            AssistantStripContent(
                AssistantStripResolver.resolve(Phase.IDLE, "", null, micGranted = true, silenced = false),
                onTap = {},
            )
        }
    }

    @Test
    fun `typed and spoken turns are tagged in words above the strip`() {
        val entries = listOf(
            ChatEntry(1, ChatEntry.Kind.USER, "When did we last buy shampoo?", ChatEntry.Via.TYPED),
            ChatEntry(2, ChatEntry.Kind.TOOL, "Search bought log"),
            ChatEntry(3, ChatEntry.Kind.ASSISTANT, "You logged shampoo on Sep 28.", ChatEntry.Via.TYPED),
            ChatEntry(4, ChatEntry.Kind.USER, "What is on the calendar?", ChatEntry.Via.SPOKEN),
            ChatEntry(5, ChatEntry.Kind.ASSISTANT, "Soccer practice at five.", ChatEntry.Via.SPOKEN),
            ChatEntry(6, ChatEntry.Kind.SYSTEM, "Stopped speaking because you typed."),
        )
        capture("assistant-typed-conversation.png", chat(entries, pending = "")) {
            AssistantStripContent(
                AssistantStripResolver.resolve(Phase.THINKING, "", null, micGranted = true, silenced = false),
                onTap = {},
            )
        }
    }

    @Test
    fun `mic blocked says typing still works`() {
        val entries = listOf(
            ChatEntry(
                1, ChatEntry.Kind.NOT_SENT, "add milk", ChatEntry.Via.TYPED,
                "The assistant isn't set up: add a Gemini key in Setup.",
            ),
        )
        capture("assistant-typed-mic-blocked.png", chat(entries)) {
            AssistantStripContent(
                AssistantStripResolver.resolve(Phase.IDLE, "", null, micGranted = false, silenced = false),
                onTap = {},
            )
        }
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
