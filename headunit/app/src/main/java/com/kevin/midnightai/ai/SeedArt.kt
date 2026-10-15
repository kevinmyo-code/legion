package com.kevin.midnightai.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.kevin.midnightai.R
import com.kevin.midnightai.ui.theme.AriaPalette

/**
 * The bundled, hand-picked art that replaces in-app generation (2026-09-27,
 * Kevin: "lets generate it ourselves here and seed it because its a personal
 * app now"). Generated in a Claude session with `art/seed/generate.py`, picked
 * by Kevin, and shipped in `res/drawable-nodpi`.
 *
 * Seeded art WINS over anything previously generated into filesDir: an old
 * on-device avatar would otherwise keep showing instead of Uncle Moose.
 *
 * Decoded once per process and cached (plain volatile fields, not a
 * StateFlow - this is immutable bundled art, never mutated or swapped at
 * runtime, so there's none of the staleness risk CLAUDE.md sec 9's "No
 * StateFlow Bitmaps" rule guards against). Both [avatar] and [wallpaper] are
 * read on nearly every Cruise/CarsScreen recomposition
 * (`remember(bgVersion)` re-invokes them whenever the background version
 * bumps), so decoding the resource fresh every call was real repeated work
 * for a picture that never changes.
 */
object SeedArt {

    @Volatile private var cachedAvatar: Bitmap? = null
    @Volatile private var cachedWallpaper: Bitmap? = null
    @Volatile private var cachedNightWallpaper: Bitmap? = null

    fun avatar(context: Context): Bitmap? {
        cachedAvatar?.let { return it }
        synchronized(this) {
            cachedAvatar?.let { return it }
            val decoded = decode(context, R.drawable.seed_avatar_uncle_moose, targetLongEdgePx = 1024)
            cachedAvatar = decoded
            return decoded
        }
    }

    /** The day or night coast road, per [AriaPalette.isNight]. */
    fun wallpaper(context: Context): Bitmap? =
        if (AriaPalette.isNight) nightWallpaper(context) else dayWallpaper(context)

    private fun nightWallpaper(context: Context): Bitmap? {
        cachedNightWallpaper?.let { return it }
        synchronized(this) {
            cachedNightWallpaper?.let { return it }
            val longEdge = context.applicationContext.resources.displayMetrics
                .let { maxOf(it.widthPixels, it.heightPixels) }.coerceAtLeast(1024)
            val decoded = decode(context, R.drawable.seed_wallpaper_summer_night, targetLongEdgePx = longEdge)
            cachedNightWallpaper = decoded
            return decoded
        }
    }

    private fun dayWallpaper(context: Context): Bitmap? {
        cachedWallpaper?.let { return it }
        synchronized(this) {
            cachedWallpaper?.let { return it }
            // Source is 1376x768. The head unit's actual screens (1024x600 to
            // 1280x720) are close enough to that native size that a bounds-first
            // inSampleSize calc against the device's own display width comes out
            // to 1 - i.e. no downsampling - which is the correct answer here,
            // not a shortcut: halving a 1376-wide source to 688 would visibly
            // soften it as a full-bleed backdrop. The bounds-first calc is still
            // real (not hardcoded to 1) so a MUCH smaller display would get one.
            val targetLongEdge = context.applicationContext.resources.displayMetrics
                .let { maxOf(it.widthPixels, it.heightPixels) }
                .coerceAtLeast(1024)
            val decoded = decode(context, R.drawable.seed_wallpaper_summer_coast, targetLongEdgePx = targetLongEdge)
            cachedWallpaper = decoded
            return decoded
        }
    }

    /** Bounds-first decode: measure, pick a power-of-two sample so the long edge lands near [targetLongEdgePx], then decode once. */
    private fun decode(context: Context, resId: Int, targetLongEdgePx: Int): Bitmap? = runCatching {
        val res = context.applicationContext.resources
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(res, resId, bounds)
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        if (longEdge > 0) {
            while (longEdge / (sample * 2) >= targetLongEdgePx) sample *= 2
        }
        BitmapFactory.decodeResource(res, resId, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}
