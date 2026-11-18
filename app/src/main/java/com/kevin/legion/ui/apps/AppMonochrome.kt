package com.kevin.legion.ui.apps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/**
 * App icons as small MONOCHROME marks (Kevin, 2026-11-18: "monochrome icons"; ADR 0081's "no
 * logos" holds). The drawer cache keeps one alpha mark per app and every surface tints it with the
 * screen's ink ([AppMark]), so a mark is ink on paper by day, cream by night, and Matrix's own ink
 * under Matrix mode, never the app's colours.
 *
 * Which layer becomes the mark, in order:
 * 1. The app's own monochrome (themed-icon) layer, Android 13 and later: drawn by the app for exactly
 *    this, so it is the best mark there is.
 * 2. Otherwise an adaptive icon's foreground layer, flattened to a silhouette: most apps put their
 *    logo shape in the foreground and the colour field in the background, so the shape survives.
 * 3. Otherwise (a legacy, non-adaptive icon) the whole picture, flattened the same way.
 * Any of the three is dropped when its silhouette does not read as a shape ([readable]): a filled
 * tile (a legacy icon's square, a full-bleed foreground) or an empty layer. The app then shows its
 * two-letter monogram in a ruled square, the Typeset dock's own mark (ADR 0081), which still tells
 * two apps apart where one generic glyph for every app would not.
 */
internal object AppMonochrome {

    enum class Source { MONOCHROME, FOREGROUND, WHOLE }

    /** The visible share a silhouette may cover and still read as a shape, not a tile. */
    private const val MAX_COVER = 0.6f

    /** Below this the layer drew next to nothing in the visible area. */
    private const val MIN_COVER = 0.02f

    private const val HALF_ALPHA = 0x80
    private const val ALPHA_SHIFT = 24
    private const val ANDROID_13 = 33

    /** Pure: which layer to flatten. */
    fun source(api: Int, adaptive: Boolean, hasMonochrome: Boolean): Source = when {
        adaptive && hasMonochrome && api >= ANDROID_13 -> Source.MONOCHROME
        adaptive -> Source.FOREGROUND
        else -> Source.WHOLE
    }

    /** Pure: the share of [argb] pixels at least half opaque. */
    fun coverage(argb: IntArray): Float =
        if (argb.isEmpty()) 0f else argb.count { (it ushr ALPHA_SHIFT) >= HALF_ALPHA }.toFloat() / argb.size

    /** Pure: whether a silhouette with these pixels reads as a mark. */
    fun readable(argb: IntArray): Boolean = coverage(argb) in MIN_COVER..MAX_COVER

    /**
     * [icon] flattened to a [px]-square mark (only its alpha matters; [AppMark] tints it), or null
     * when no layer reads as one. An adaptive layer is 108 units with the visible 72 in the middle,
     * so it is drawn half again as large and the middle [px] kept, as a launcher's mask would.
     */
    fun mark(icon: Drawable, px: Int): ImageBitmap? {
        val adaptive = icon is AdaptiveIconDrawable
        val mono = if (Build.VERSION.SDK_INT >= ANDROID_13 && adaptive) {
            (icon as AdaptiveIconDrawable).monochrome
        } else {
            null
        }
        val bitmap = when (source(Build.VERSION.SDK_INT, adaptive, mono != null)) {
            Source.MONOCHROME -> layer(mono!!, px)
            Source.FOREGROUND -> (icon as AdaptiveIconDrawable).foreground?.let { layer(it, px) } ?: return null
            Source.WHOLE -> icon.toBitmap(px, px)
        }
        val pixels = IntArray(px * px)
        bitmap.getPixels(pixels, 0, px, 0, 0, px, px)
        return if (readable(pixels)) bitmap.asImageBitmap() else null
    }

    private fun layer(d: Drawable, px: Int): Bitmap {
        val full = px * 3 / 2
        val inset = (full - px) / 2
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.translate(-inset.toFloat(), -inset.toFloat())
        val layer = d.mutate()
        layer.setBounds(0, 0, full, full)
        layer.draw(canvas)
        return out
    }
}

/**
 * An app's monochrome mark ([AppMonochrome]) in the screen's ink: `onSurface` of the theme in force,
 * which is Typeset ink by day (16:1 on paper), cream by night, and Matrix's ink under Matrix mode.
 */
@Composable
internal fun AppMark(mark: ImageBitmap, modifier: Modifier = Modifier) {
    Image(
        bitmap = mark,
        contentDescription = null,
        modifier = modifier,
        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
    )
}
