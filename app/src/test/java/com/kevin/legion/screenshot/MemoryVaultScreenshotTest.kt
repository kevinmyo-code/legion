package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.memory.MemberMemory
import com.kevin.legion.memory.ReviewBefore
import com.kevin.legion.memory.ReviewChange
import com.kevin.legion.ui.companions.MemoryCallbacks
import com.kevin.legion.ui.companions.MemoryContent
import com.kevin.legion.ui.companions.MemoryDialog
import com.kevin.legion.ui.companions.MemoryFilter
import com.kevin.legion.ui.companions.MemoryUiState
import com.kevin.legion.ui.companions.MemoryView
import com.kevin.legion.ui.companions.ReviewState
import com.kevin.legion.ui.theme.soft.SoftTheme
import com.kevin.legion.ui.vault.Shown
import com.kevin.legion.ui.vault.VaultCallbacks
import com.kevin.legion.ui.vault.VaultContent
import com.kevin.legion.ui.vault.VaultUiState
import com.kevin.legion.ui.vault.VaultView
import com.kevin.legion.vault.VaultItem
import com.kevin.legion.vault.VaultSecret
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * memory-and-vault tickets 11 and 12, at the A25's size: the Memory screen read from the engine (a
 * distilled memory, a shared one, one about someone else), its offline copy, its forget confirm, and
 * the ID vault's list and one revealed item. Made-up memories; the vault value is a placeholder
 * shaped like no real identifier.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
class MemoryVaultScreenshotTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun at(day: Int): Long =
        LocalDate.of(2026, 11, day).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val memories = listOf(
        MemberMemory(
            "m1", "Jeep needs an LS swap eventually", "fact", 6, "durable", "consolidation", false, true, at(4),
            fromConversation = "c1",
        ),
        MemberMemory("m2", "Home address is on Elm Street", "fact", 7, "durable", "manual", true, true, at(2)),
        MemberMemory("m3", "Mia's birthday is in May", "fact", 6, "durable", "web", true, false, at(1)),
        MemberMemory("m4", "Prefers flat whites, no sugar", "preference", 4, "durable", "phone", false, true, at(1)),
    )

    @Test
    fun `memory list read from the engine`() = captureMemory(
        "memory-v2-list.png",
        MemoryUiState(view = MemoryView.Listed(memories), message = "Deleted for good."),
    )

    @Test
    fun `memory offline shows the phone's copy and says so`() = captureMemory(
        "memory-v2-offline.png",
        MemoryUiState(
            view = MemoryView.Listed(
                memories.take(2),
                cachedNote = "The engine is out of reach, so this is the phone's copy as of November 5, 2026; " +
                    "anything saved or forgotten since may be missing.",
            ),
        ),
    )

    @Test
    fun `memory forget asks first`() = captureMemory(
        "memory-v2-forget-confirm.png",
        MemoryUiState(view = MemoryView.Listed(memories), dialog = MemoryDialog.ConfirmForget(memories[0])),
    )

    @Test
    fun `vault list and one revealed item`() {
        val passport = VaultItem("i1", "Passport", "passport")
        val items = listOf(passport, VaultItem("i2", "SSN", "ssn"), VaultItem("i3", "BofA checking", "bank_account"))
        val state = VaultUiState(
            view = VaultView.Listed(items),
            shown = Shown("i1", VaultSecret("X0000000"), hideAtMs = 0L),
            focusId = "i1",
        )
        composeTestRule.setContent { Framed { VaultContent(state, VaultCallbacks()) } }
        composeTestRule.onRoot().captureRoboImage("vault-list-revealed.png")
    }

    @Test
    fun `memory review to check sits above the list`() = captureMemory(
        "memory-review-to-check.png",
        MemoryUiState(
            view = MemoryView.Listed(memories),
            review = ReviewState(
                listOf(
                    ReviewChange(
                        1, "merge", "pending", "3.1",
                        listOf(
                            ReviewBefore("m2", "Home address is on Elm Street"),
                            ReviewBefore("m9", "Lives on Elm St"),
                        ),
                        "Home address is on Elm Street", "Two rows give the same address.",
                        "These say the same thing; keep one.",
                    ),
                    ReviewChange(
                        2, "expire", "pending", "3.1", listOf(ReviewBefore("m9", "Lives on Elm St")),
                        "the date has passed, so it would be retired", "The move date has passed.",
                        "The date has passed.", evidenceDate = "2026-11-07",
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `memory review retired list says why and offers restore`() = captureMemory(
        "memory-review-retired.png",
        MemoryUiState(
            view = MemoryView.Listed(memories),
            filter = MemoryFilter.Retired,
            retired = MemoryView.Listed(
                listOf(
                    memories[0].copy(id = "r1", retiredReason = "merged", retiredChange = 4L),
                    memories[3].copy(id = "r2", text = "Dentist on August 3", retiredReason = "expired"),
                ),
            ),
        ),
    )

    private fun captureMemory(fileName: String, state: MemoryUiState) {
        composeTestRule.setContent { Framed { MemoryContent(state, MemoryCallbacks()) } }
        composeTestRule.onRoot().captureRoboImage(fileName)
    }

    @Composable
    private fun Framed(content: @Composable () -> Unit) {
        SoftTheme {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
        }
    }
}
