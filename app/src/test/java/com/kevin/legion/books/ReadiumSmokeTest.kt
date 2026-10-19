package com.kevin.legion.books

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import org.robolectric.RobolectricTestRunner

/**
 * The engine spike (ADR 0061, `.scratch/books/research/engine.md`): Readium 3.3.0 opens an EPUB on the
 * JVM with ExoPlayer excluded from the classpath, and its TTS navigator classes still load.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalReadiumApi::class)
class ReadiumSmokeTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun opensAnEpubAndReadsItsMetadata() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file: File = EpubFixture.write(tmp.newFile("fixture.epub"), title = "Moby Fixture")
        val http = DefaultHttpClient()
        val retriever = AssetRetriever(context.contentResolver, http)
        val opener = PublicationOpener(DefaultPublicationParser(context, http, retriever, pdfFactory = null))
        val asset = retriever.retrieve(file.toUrl(isDirectory = false)).getOrNull()
        assertNotNull(asset)
        val publication = opener.open(asset!!, allowUserInteraction = false).getOrNull()
        assertNotNull(publication)
        assertEquals("Moby Fixture", publication!!.metadata.title)
        assertEquals(2, publication.readingOrder.size)
        assertEquals(false, publication.isRestricted)
    }

    @Test fun ttsNavigatorClassesLoadWithoutExoPlayer() {
        // Class.forName, not a call: proves the classes link without media3-exoplayer present.
        Class.forName("org.readium.navigator.media.tts.TtsNavigator")
        // AndroidTtsNavigatorFactory is a typealias; the real class is TtsNavigatorFactory.
        Class.forName("org.readium.navigator.media.tts.TtsNavigatorFactory")
        val exo = runCatching { Class.forName("androidx.media3.exoplayer.ExoPlayer") }.isSuccess
        assertEquals("ExoPlayer must stay excluded (CLAUDE.md section 3)", false, exo)
    }
}
