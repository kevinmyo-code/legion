package com.kevin.legion.ui.companions

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import android.app.Application
import com.kevin.legion.memory.FakeMemberMemoryBackend
import com.kevin.legion.memory.FakeMemberMemoryBackend.Companion.NOW
import com.kevin.legion.memory.FakeMemberMemoryBackend.Companion.memory
import com.kevin.legion.memory.InMemoryMemoryCache
import com.kevin.legion.memory.MemoryController
import com.kevin.legion.ui.theme.soft.SoftTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * memory-and-vault ticket 11's Compose test: Forget asks first and deletes only on Forget, through
 * the same controller the voice tool uses, and says "Deleted for good."; the shared switch changes
 * the engine's row and appears only on a memory about you.
 */
@RunWith(RobolectricTestRunner::class)
class MemoryContentTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val backend = FakeMemberMemoryBackend(
        mutableListOf(
            memory("mine", "Prefers flat whites"),
            memory("theirs", "Mia's birthday is in May", aboutYou = false, shared = true),
        ),
    )

    private fun viewModel() = MemoryViewModel(
        RuntimeEnvironment.getApplication() as Application,
        MemoryController(backend, InMemoryMemoryCache(), now = { NOW }),
    )

    private fun show(vm: MemoryViewModel) {
        compose.setContent {
            val state by vm.state.collectAsState()
            SoftTheme {
                MemoryContent(
                    state,
                    MemoryCallbacks(
                        onForget = vm::askForget,
                        onConfirmForget = vm::confirmForget,
                        onDismiss = vm::dismissDialog,
                        onShared = vm::setShared,
                    ),
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `forget asks, then deletes for good`() {
        val vm = viewModel()
        show(vm)
        compose.onNodeWithTag("forget-mine").performClick()
        compose.onNodeWithText("Forget this for good?").assertExists()
        assertEquals(2, backend.store.size) // nothing deleted by the first tap
        compose.onNodeWithTag("confirm-forget").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Deleted for good.").assertExists()
        assertEquals(listOf("theirs"), backend.store.map { it.id })
        assertTrue(backend.calls.contains("delete:mine"))
    }

    @Test
    fun `the shared switch writes the engine and shows only on your own memory`() {
        val vm = viewModel()
        show(vm)
        compose.onNodeWithTag("share-theirs").assertDoesNotExist()
        compose.onNodeWithTag("share-mine").assertIsOff().performClick()
        compose.waitForIdle()
        assertTrue(backend.store.first { it.id == "mine" }.shared)
        compose.onNodeWithText("Shared with the household.").assertExists()
    }
}

