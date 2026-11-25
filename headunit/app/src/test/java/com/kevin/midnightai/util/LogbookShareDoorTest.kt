package com.kevin.midnightai.util

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The logbook's SHARE tap, on a unit where the card cannot be built or its URI cannot be made.
 *
 * [LogbookExporter.share] runs on a detached IO coroutine, so anything it throws is an unhandled
 * coroutine exception and the launcher process dies. Two steps ran before the door: the cache-file
 * write (an `IOException`, which [ExternalDoor] deliberately does not catch) and
 * `FileProvider.getUriForFile` (a `RuntimeException` the door WOULD have caught, had the call been
 * inside it). Both are proven here by the words the tap gets back instead of a throw.
 *
 * The second one is reached through the authority rather than through a file outside the provider's
 * configured roots, which is its field shape. `FileProvider` resolves `cache-path` against the
 * context it is handed and then caches that strategy per authority for the whole process, so a test
 * that moved `cacheDir` would pass or fail on test order. An authority no provider answers throws
 * `IllegalArgumentException` out of the same lookup, so the thrown class is the field's own and the
 * test is order-independent.
 */
@RunWith(RobolectricTestRunner::class)
class LogbookShareDoorTest {

    private val app get() = RuntimeEnvironment.getApplication()

    private val entries = listOf(
        ShareEntry("Oil and filter", "4 Jan", "maintenance"),
        ShareEntry("Rear main seal", "19 Feb", "repair"),
    )

    @Test fun `a cache it cannot write to says so instead of killing the launcher`() {
        // A regular file where the card's directory should be: the card's own outputStream() then
        // throws FileNotFoundException, which is an IOException, on every platform.
        val notADirectory = File(app.cacheDir, "logbook-cache-is-a-file").apply {
            parentFile?.mkdirs()
            writeText("not a directory")
        }

        val words = LogbookExporter.share(CacheDirAt(app, notADirectory), "1998 Jeep Cherokee", entries)

        assertEquals(LogbookExporter.CARD_NOT_BUILT_WORDS, words)
    }

    @Test fun `a provider that cannot name the card says so instead of killing the launcher`() {
        val words = LogbookExporter.share(AuthorityNobodyAnswers(app), "1998 Jeep Cherokee", entries)

        assertEquals(ExternalScreen.SHARE_SHEET.failedWords(), words)
    }

    /** Puts the card's write where it cannot succeed, and nowhere else. */
    private class CacheDirAt(base: Context, private val dir: File) : ContextWrapper(base) {
        override fun getCacheDir(): File = dir
    }

    /** The share authority is `"<packageName>.provider"`, so this makes it one no provider holds. */
    private class AuthorityNobodyAnswers(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = "com.example.nothing.here"
    }
}
