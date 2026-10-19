package com.kevin.legion.books

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.cover
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

/** Either an opened EPUB or the sentence for why it would not open. */
sealed interface EpubOpenResult {
    data class Opened(val publication: Publication) : EpubOpenResult

    data class Failed(val sentence: String) : EpubOpenResult
}

/** What the sheet learns from an EPUB file itself (ADR 0061: titles and covers come from the file). */
data class EpubInfo(val title: String?, val author: String?, val cover: Bitmap?)

/**
 * Readium 3.3.0 used for EPUB ONLY (Kevin, 2026-10-19: hybrid engine). No PDF factory is passed, so
 * a PDF cannot reach Readium and the pdfium adapter is not on the classpath; CBZ never comes here.
 * Everything is local: the asset is the document's `content://` URI and nothing is fetched.
 */
@OptIn(ExperimentalReadiumApi::class)
class ReadiumEpub(context: Context) {
    private val app = context.applicationContext
    private val httpClient = DefaultHttpClient()
    private val assetRetriever = AssetRetriever(app.contentResolver, httpClient)
    private val opener = PublicationOpener(
        DefaultPublicationParser(app, httpClient = httpClient, assetRetriever = assetRetriever, pdfFactory = null),
    )

    @Suppress("TooGenericExceptionCaught") // a damaged archive can throw anything out of the parser
    suspend fun open(uri: Uri): EpubOpenResult {
        val url = AbsoluteUrl(uri.toString())
            ?: return EpubOpenResult.Failed("Android gave an address for this book that LEGION cannot read.")
        return try {
            val asset = assetRetriever.retrieve(url, MediaType.EPUB).getOrElse {
                return EpubOpenResult.Failed("This EPUB could not be read: ${it.message}")
            }
            val publication = opener.open(asset, allowUserInteraction = false).getOrElse {
                return EpubOpenResult.Failed("This EPUB could not be opened: ${it.message}")
            }
            if (publication.isRestricted) {
                publication.close()
                EpubOpenResult.Failed(DRM_SENTENCE)
            } else {
                EpubOpenResult.Opened(publication)
            }
        } catch (e: Exception) {
            EpubOpenResult.Failed("This EPUB could not be opened: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Title, author and cover, or null when the file would not open. The publication is closed again. */
    suspend fun inspect(uri: Uri): EpubInfo? {
        val publication = (open(uri) as? EpubOpenResult.Opened)?.publication ?: return null
        return try {
            EpubInfo(
                title = publication.metadata.title?.takeIf { it.isNotBlank() },
                author = publication.metadata.authors.firstOrNull()?.name?.takeIf { it.isNotBlank() },
                cover = publication.cover(),
            )
        } finally {
            publication.close()
        }
    }

    companion object {
        const val DRM_SENTENCE = "This EPUB is copy-protected, and LEGION cannot open protected books."
    }
}
