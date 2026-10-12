package com.kevin.legion.ui.home

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.kevin.legion.games.BoxArtCache
import com.kevin.legion.games.Game
import com.kevin.legion.games.SafRomTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where a game tile's picture comes from, in order: an image kept beside the ROM under the same name
 * (a romhack or homebrew has no libretro cover), then the cached libretro box art (ADR 0058). Null
 * means neither, and the tile says the title in words. Every cover is downsampled to about
 * [maxPx] wide, so a big image never costs a full-size bitmap.
 */
class CoverImages(
    private val resolver: ContentResolver,
    private val boxArt: BoxArtCache,
    private val maxPx: Int,
) {
    suspend fun load(tree: Uri?, game: Game): ImageBitmap? = beside(tree, game) ?: cached(game)

    private suspend fun beside(tree: Uri?, game: Game): ImageBitmap? {
        val id = game.coverDocumentId
        if (tree == null || id == null) return null
        val uri = SafRomTree.documentUri(tree, id)
        return withContext(Dispatchers.IO) {
            runCatching { decodeSampled { opts -> openAndDecode(uri, opts) } }.getOrNull()?.asImageBitmap()
        }
    }

    private suspend fun cached(game: Game): ImageBitmap? {
        val file = boxArt.get(game) ?: return null
        return withContext(Dispatchers.Default) {
            val bitmap = decodeSampled { opts -> BitmapFactory.decodeFile(file.path, opts) }
            // A file that will not decode is dropped so the next open fetches it again.
            if (bitmap == null) file.delete()
            bitmap?.asImageBitmap()
        }
    }

    private fun openAndDecode(uri: Uri, opts: BitmapFactory.Options): Bitmap? =
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }

    /** Decodes twice: bounds first, then with the sample size that brings the width near [maxPx]. */
    private fun decodeSampled(decode: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxPx * 2) sample *= 2
        return decode(BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
