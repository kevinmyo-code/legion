package com.kevin.legion.ui.claudesession

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kevin.legion.claude.LocalClaudeFile
import com.kevin.legion.ui.theme.soft.SoftTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The files viewer (ADR 0080): every delivered file has Open and Share, several have Share all, and
 * no files says in words why rather than drawing an empty list. Made-up files only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClaudeFilesContentTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(id: Int, name: String, mime: String): LocalClaudeFile {
        val f: File = tmp.newFile("$id-$name").apply { writeBytes(ByteArray(2048)) }
        return LocalClaudeFile(58, id, name, f, mime)
    }

    @Test
    fun `each file has Open and Share, and Share all shares every one`() {
        val files = listOf(file(91, "form-scan.pdf", "application/pdf"), file(92, "notes.txt", "text/plain"))
        val opened = mutableListOf<String>()
        val shared = mutableListOf<List<String>>()
        rule.setContent {
            SoftTheme {
                ClaudeFilesContent(files, { opened += it.name }, { list -> shared += list.map { it.name } }, {})
            }
        }
        rule.onAllNodesWithTag(FILE_ROW_TAG).fetchSemanticsNodes().let { assertEquals(2, it.size) }
        rule.onNodeWithText("form-scan.pdf (2 KB)").assertExists()
        rule.onNodeWithText("Share all 2").performClick()
        assertEquals(listOf(listOf("form-scan.pdf", "notes.txt")), shared)
        rule.onAllNodesWithText("Open")[0].performClick()
        assertEquals(listOf("form-scan.pdf"), opened)
    }

    @Test
    fun `no files on this phone says why`() {
        rule.setContent { SoftTheme { ClaudeFilesContent(emptyList(), {}, {}, {}) } }
        rule.onNodeWithTag(FILES_GONE_TAG).assertExists()
        rule.onNodeWithText("Share all", substring = true).assertDoesNotExist()
    }
}
