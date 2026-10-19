package com.kevin.legion.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.theme.soft.SoftTheme
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** All six HOME chip labels fit on one line at 384dp, at font 1.0 and 1.3: never truncated or wrapped. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w384dp-h120dp")
class HomePanelButtonsFitTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val labels = listOf("To-dos", "Lists", "Ideas", "Games", "Books", "More")

    private fun check(fontScale: Float) {
        val day = LocalDate.of(2026, 10, 9)
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                SoftTheme {
                    Box(Modifier.fillMaxSize().padding(12.dp)) {
                        HomePanelButtons(
                            HomeCalendarUiState(today = day, month = YearMonth.from(day), selectedDay = day),
                            HomeCalendarCallbacks(),
                        )
                    }
                }
            }
        }
        for (label in labels) {
            val node = rule.onAllNodes(hasText(label)).fetchSemanticsNodes().first()
            val results = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            val layout = results.single()
            assertEquals("$label wrapped at font $fontScale", 1, layout.lineCount)
            assertFalse("$label truncated at font $fontScale", layout.hasVisualOverflow)
        }
    }

    @Test fun `six labels fit at font 1_0`() = check(1.0f)

    @Test fun `six labels fit at font 1_3`() = check(1.3f)
}
