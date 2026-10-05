package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.data.local.Checklist
import com.kevin.legion.purchases.Purchase
import com.kevin.legion.purchases.PurchaseMatch
import com.kevin.legion.ui.bought.BoughtCallbacks
import com.kevin.legion.ui.bought.BoughtSearchContent
import com.kevin.legion.ui.bought.BoughtUiState
import com.kevin.legion.ui.bought.BoughtView
import com.kevin.legion.ui.bought.LogCallbacks
import com.kevin.legion.ui.bought.LogFormState
import com.kevin.legion.ui.bought.LogPurchaseContent
import com.kevin.legion.ui.checklists.ListCardUi
import com.kevin.legion.ui.checklists.ListProgress
import com.kevin.legion.ui.checklists.ListsContent
import com.kevin.legion.ui.checklists.ListsPageCallbacks
import com.kevin.legion.ui.checklists.ListsPageState
import com.kevin.legion.ui.checklists.listVisual
import com.kevin.legion.ui.theme.soft.SoftTheme
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Purchase-log ticket 08's screens, at the A25's own size (variant C, `research/05-prototypes`):
 * the search answer naming its entry, the log being unreachable (in words, never an empty list), no
 * match (an absent record), and the "Log it" form with a refusal. Every price on these screens says
 * it was entered by hand; a backfilled entry says "Logged by: not recorded".
 *
 * Stateless content composables are rendered directly with made-up data - no engine, no ViewModel.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
class BoughtScreenshotTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val today = LocalDate.of(2026, 10, 4).toEpochDay().toInt()
    private val sep20 = LocalDate.of(2026, 9, 20).toEpochDay().toInt()

    private fun entry(
        item: String,
        by: String? = "Mia",
        day: Int = sep20,
        price: Long? = null,
        store: String? = null,
        priv: Boolean = false,
        mine: Boolean = false,
    ) = Purchase(
        id = "$item$day", item = item, boughtOn = day, loggedBy = by, loggedByMe = mine, store = store,
        priceCents = price, priceNote = price?.let { "entered by hand" }, quantityNote = null,
        isPrivate = priv, source = "MANUAL",
    )

    private val callbacks = BoughtCallbacks(onBack = {}, onQueryChange = {}, onRetry = {}, onLogIt = {})

    @Test
    fun `search answer names the matched entry and lists the other matches`() {
        val matches = listOf(
            PurchaseMatch(
                entry("Head & Shoulders shampoo", price = 899, store = "Target"),
                exact = false,
                timesLogged = 3,
            ),
            PurchaseMatch(entry("shampoo", by = null, day = sep20 - 40), exact = true, timesLogged = 1),
        )
        capture(
            "bought-search-answer.png",
            BoughtUiState(
                query = "shampoo",
                today = today,
                view = BoughtView.Answer(
                    query = "shampoo",
                    headline = "You logged \"Head & Shoulders shampoo\" on Sep 20 (Mia). Other matches: " +
                        "\"shampoo\" on Aug 11 (who logged it was not recorded).",
                    matches = matches,
                ),
            ),
        )
    }

    @Test
    fun `search with no match says there is no record`() {
        capture(
            "bought-search-no-record.png",
            BoughtUiState(
                query = "conditioner",
                today = today,
                view = BoughtView.Answer(
                    "conditioner",
                    "I have no record of buying conditioner. That is an absent record, not proof you never did.",
                    emptyList(),
                ),
            ),
        )
    }

    @Test
    fun `an unreachable log is said in words and is not drawn as an empty list`() {
        capture(
            "bought-search-unreachable.png",
            BoughtUiState(
                today = today,
                view = BoughtView.Unavailable(
                    "I can't reach the bought log right now, so I couldn't read the entries. " +
                        "That is not the same as nothing being logged.",
                ),
            ),
        )
    }

    @Test
    fun `recent entries with a hand-entered price, a private one and a not-recorded one`() {
        capture(
            "bought-search-recent.png",
            BoughtUiState(
                today = today,
                savedMessage = "Logged \"razors\" as bought on Oct 4. Only you can see it.",
                view = BoughtView.Recent(
                    listOf(
                        entry("razors", by = "Kevin", day = today, price = 1299, priv = true, mine = true),
                        entry("Shampoo", price = 899, store = "Target"),
                        entry("dish soap", by = null, day = sep20 - 40),
                    ),
                    truncated = false,
                    emptyMessage = null,
                ),
            ),
        )
    }

    @Test
    fun `my own and backfilled rows offer Edit and Delete, someone elses is read-only`() {
        // "razors" is mine, "dish soap" is a backfill (logger not recorded), "Shampoo" is Mia's.
        assertTrue(entry("razors", mine = true, by = "Kevin").mayChange)
        assertTrue(entry("dish soap", by = null).mayChange)
        assertFalse(entry("Shampoo").mayChange)
        capture(
            "bought-search-edit-delete.png",
            BoughtUiState(
                today = today,
                savedMessage = "Deleted \"test conditioner\" from the bought log.",
                view = BoughtView.Recent(
                    listOf(
                        entry("razors", by = "Kevin", day = today, price = 1299, mine = true),
                        entry("Shampoo", price = 899, store = "Target"),
                        entry("dish soap", by = null, day = sep20 - 40),
                    ),
                    truncated = false,
                    emptyMessage = null,
                ),
            ),
        )
    }

    @Test
    fun `a delete that did not happen says nothing was deleted`() {
        capture(
            "bought-search-delete-failed.png",
            BoughtUiState(
                today = today,
                problem = "I can't reach the bought log right now, so I didn't delete \"razors\". " +
                    "Nothing was deleted.",
                view = BoughtView.Recent(
                    listOf(entry("razors", by = "Kevin", day = today, mine = true)),
                    truncated = false,
                    emptyMessage = null,
                ),
            ),
        )
    }

    @Test
    fun `the delete confirm names the entry and offers Keep it`() {
        val razors = entry("razors", by = "Kevin", day = today, mine = true)
        composeTestRule.setContent {
            Framed {
                BoughtSearchContent(
                    state = BoughtUiState(
                        today = today,
                        pendingDelete = razors,
                        view = BoughtView.Recent(listOf(razors), truncated = false, emptyMessage = null),
                    ),
                    callbacks = callbacks,
                )
            }
        }
        composeTestRule.onNodeWithText("Delete this entry?").assertExists()
        composeTestRule.onNodeWithText("Keep it").assertExists()
        composeTestRule.onNodeWithText("\"razors\" will be removed from the bought log for everyone who can see it.")
            .assertExists()
    }

    @Test
    fun `the edit form is filled from the entry and saves as changes`() {
        composeTestRule.setContent {
            Framed {
                LogPurchaseContent(
                    form = LogFormState(
                        item = "test conditioner",
                        dateText = "2026-10-04",
                        store = "Target",
                        price = "8.99",
                        editing = entry("test conditioner", by = "Kevin", day = today, mine = true),
                    ),
                    today = today,
                    callbacks = LogCallbacks(onBack = {}, onEdit = {}, onSave = {}),
                )
            }
        }
        composeTestRule.onNodeWithText("Save changes").assertExists()
        composeTestRule.onRoot().captureRoboImage("bought-edit-form.png")
    }

    @Test
    fun `the log form with a refusal that says nothing was logged`() {
        composeTestRule.setContent {
            Framed {
                LogPurchaseContent(
                    form = LogFormState(
                        item = "shampoo",
                        dateText = "2026-10-04",
                        store = "Target",
                        price = "8.99",
                        isPrivate = true,
                        error = "I can't reach the bought log right now, so I didn't log shampoo.",
                    ),
                    today = today,
                    callbacks = LogCallbacks(onBack = {}, onEdit = {}, onSave = {}),
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage("bought-log-form.png")
    }

    @Test
    fun `the Lists page carries the Bought log button when it is wired`() {
        val groceries = Checklist(id = 3, name = "Groceries")
        val card = ListCardUi(groceries, listVisual("Groceries", 3), ListProgress(6f / 8f, "6/8"))
        composeTestRule.setContent {
            Framed {
                ListsContent(
                    state = ListsPageState(loading = false, plainLists = listOf(card)),
                    callbacks = ListsPageCallbacks(
                        onBack = {},
                        onOpenList = {},
                        onToggleArchived = {},
                        onShowCreateDialog = {},
                        onCreate = { _, _, _ -> },
                        onOpenBought = {},
                    ),
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage("lists-page-bought-button.png")
    }

    private fun capture(fileName: String, state: BoughtUiState) {
        composeTestRule.setContent { Framed { BoughtSearchContent(state = state, callbacks = callbacks) } }
        composeTestRule.onRoot().captureRoboImage(fileName)
    }

    @Composable
    private fun Framed(content: @Composable () -> Unit) {
        SoftTheme {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
        }
    }
}
