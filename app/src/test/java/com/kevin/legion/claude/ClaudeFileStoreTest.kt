package com.kevin.legion.claude

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.kevin.legion.ui.claudesession.ClaudeFileActivity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The phone side of files to phone (ADR 0080): files land in app-private storage under a safe
 * name, leave LEGION only through a read-only FileProvider grant (ACTION_VIEW or the share sheet),
 * are pruned after 30 days, and the notification names a count, never a file. Made-up names only.
 */
@RunWith(RobolectricTestRunner::class)
class ClaudeFileStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var clock = System.currentTimeMillis()
    private val store = ClaudeFileStore(context) { clock }

    private val realUri = ClaudeFileIntents.uriFor

    @Before
    fun setUp() {
        // FileProvider refuses every path under Robolectric on Windows (see ClaudeFileIntents.uriFor);
        // the URI it would make on the phone has this shape: content://<pkg>.provider/claude_files/...
        ClaudeFileIntents.uriFor = { c, f ->
            Uri.parse("content://${c.packageName}.provider/claude_files/${f.taskId}/${Uri.encode(f.file.name)}")
        }
    }

    @After
    fun tearDown() {
        ClaudeFileIntents.uriFor = realUri
        File(context.filesDir, ClaudeFileStore.DIR).deleteRecursively()
    }

    @Test
    fun `a kept file is app-private, listed with its type, and has() sees it`() {
        assertFalse(store.has(58, 91))
        assertTrue(runBlocking { store.keep(58, 91, "form-scan.pdf", byteArrayOf(1, 2)) })
        assertTrue(store.has(58, 91))
        val f = store.list(58).single()
        assertEquals("form-scan.pdf", f.name)
        assertEquals("application/pdf", f.mimeType)
        assertFalse(f.isImage)
        assertTrue(f.file.path.startsWith(context.filesDir.path))
        assertEquals(File(File(context.filesDir, "claude_files"), "58").path, f.file.parentFile!!.path)
        assertTrue("no half-written file left", f.file.parentFile!!.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test
    fun `a name cannot climb out of its folder`() {
        assertEquals("passwd", ClaudeFileStore.safeName("../../etc/passwd"))
        assertEquals("a_b.jpg", ClaudeFileStore.safeName("a:b.jpg"))
        assertEquals("x.pdf", ClaudeFileStore.safeName("C:\\files\\x.pdf"))
        assertEquals("claude-file", ClaudeFileStore.safeName(".."))
        runBlocking { store.keep(58, 92, "../../evil.jpg", byteArrayOf(1)) }
        assertEquals("evil.jpg", store.list(58).single().name)
        assertTrue(store.list(58).single().isImage)
    }

    @Test
    fun `files older than 30 days are pruned on the next keep`() {
        runBlocking { store.keep(58, 91, "old.pdf", byteArrayOf(1)) }
        File(context.filesDir, "claude_files/58").listFiles()!!.forEach { it.setLastModified(clock - 31L * 86_400_000) }
        runBlocking { store.keep(59, 95, "new.pdf", byteArrayOf(1)) }
        assertTrue(store.list(58).isEmpty())
        assertEquals(1, store.list(59).size)
    }

    @Test
    fun `view hands one read-only content uri to a chooser`() {
        runBlocking { store.keep(58, 91, "form-scan.pdf", byteArrayOf(1)) }
        val chooser = ClaudeFileIntents.view(context, store.list(58).single())
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val inner = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_VIEW, inner.action)
        assertEquals("application/pdf", inner.type)
        assertEquals("content", inner.data!!.scheme)
        assertEquals("${context.packageName}.provider", inner.data!!.authority)
        assertFalse("never a path", inner.dataString!!.contains(context.filesDir.path))
        assertTrue(inner.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(inner.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0)
    }

    @Test
    fun `share is Android's own sheet, one file or several, with no recipient`() {
        runBlocking {
            store.keep(58, 91, "photo-1.jpg", byteArrayOf(1))
            store.keep(58, 92, "photo-2.jpg", byteArrayOf(2))
        }
        val files = store.list(58)
        @Suppress("DEPRECATION")
        val one = ClaudeFileIntents.share(context, files.take(1)).getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, one.action)
        assertEquals("image/jpeg", one.type)
        @Suppress("DEPRECATION")
        assertNotNull(one.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(null, one.`package`)
        assertEquals(null, one.component)
        @Suppress("DEPRECATION")
        val many = ClaudeFileIntents.share(context, files).getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND_MULTIPLE, many.action)
        @Suppress("DEPRECATION")
        assertEquals(2, many.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!.size)
        assertEquals(2, many.clipData!!.itemCount)
        assertTrue(many.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    /** What the Windows seam above skips: the provider really maps `claude_files/` under filesDir. */
    @Test
    fun `the FileProvider config exposes claude_files as a files-path`() {
        val xml = context.resources.getXml(com.kevin.legion.R.xml.provider_paths)
        var found = false
        while (xml.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (xml.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && xml.name == "files-path") {
                found = xml.getAttributeValue(null, "path") == "claude_files/" ||
                    xml.getAttributeValue("http://schemas.android.com/apk/res/android", "path") == "claude_files/"
            }
        }
        assertTrue(found)
    }

    @Test
    fun `the notification names a count and opens the files, never a name`() {
        assertEquals("1 file from task 58 is on your phone. Tap to open.", ClaudeFilesNotifier.text(58, 1))
        assertEquals("2 files from task 58 are on your phone. Tap to open.", ClaudeFilesNotifier.text(58, 2))
        val n = ClaudeFilesNotifier.build(context, 58, 1)
        val text = n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        assertFalse(text.contains(".pdf"))
        val tapped = shadowOf(n.contentIntent).savedIntent
        assertEquals(ClaudeFileActivity::class.java.name, tapped.component!!.className)
    }
}
