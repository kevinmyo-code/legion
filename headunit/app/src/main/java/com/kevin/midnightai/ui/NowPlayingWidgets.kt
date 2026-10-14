package com.kevin.midnightai.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A Showa cassette art object. Motion runs while music plays (reels turn) and
 * freezes in place when it pauses, all on the frame clock ([animPhase]) so it
 * survives head units that disable system animations.
 *
 * Cruise's now-playing object until 2026-10-13, when the split music deck
 * (ui/MusicDeck.kt) replaced it; the Cruise usage, its colorway setting and
 * the style plumbing were removed then. Kept because ProtoCruiseVariants'
 * previews still render it.
 */

// --- Shell colorways ---

internal enum class CassetteVariant { PACIFIC, MIDNIGHT, SUNSET, INDIGO, SAKURA }

/** The colorway of a cassette shell (label may be a gradient, so a Brush). */
private class CassettePalette(
    val shell: Color,
    val shellEdge: Color,
    val label: Brush,
    val print: Color,
    val footer: Color,
)

private fun cassettePalette(variant: CassetteVariant): CassettePalette = when (variant) {
    // Pacific Breeze: cream shell, pink label, teal print (the style reference).
    CassetteVariant.PACIFIC -> CassettePalette(
        shell = Color(0xFFEDE4D4),
        shellEdge = Color(0xFFB8AD97),
        label = SolidColor(Color(0xFFF0A8C4)),
        print = Color(0xFF1E9E96),
        footer = Color(0xFFB8AD97),
    )
    // Midnight: smoke shell, amber print — the deck at night.
    CassetteVariant.MIDNIGHT -> CassettePalette(
        shell = Color(0xFF1C1510),
        shellEdge = Color(0xFFFFB02E).copy(alpha = 0.75f),
        label = SolidColor(Color(0xFF2C2114)),
        print = Color(0xFFFFB02E),
        footer = Color(0xFFFFB02E).copy(alpha = 0.6f),
    )
    // Sunset: white shell, coral→magenta gradient label, cream print.
    CassetteVariant.SUNSET -> CassettePalette(
        shell = Color(0xFFF5EFE2),
        shellEdge = Color(0xFFC9B99B),
        label = Brush.linearGradient(
            listOf(Color(0xFFFF7A3D), Color(0xFFFF2E97), Color(0xFF6A2ECC)),
        ),
        print = Color(0xFFFFF3DC),
        footer = Color(0xFFC9B99B),
    )
    // Indigo: deep navy shell, cyan print - a Nagai poolside-at-night look.
    CassetteVariant.INDIGO -> CassettePalette(
        shell = Color(0xFF16202E),
        shellEdge = Color(0xFF35E0E6).copy(alpha = 0.75f),
        label = SolidColor(Color(0xFF1B3A5C)),
        print = Color(0xFF35E0E6),
        footer = Color(0xFF35E0E6).copy(alpha = 0.6f),
    )
    // Sakura: dusty-rose cream shell, pink→coral gradient label, warm ink print.
    CassetteVariant.SAKURA -> CassettePalette(
        shell = Color(0xFFF7E6E8),
        shellEdge = Color(0xFFD9A8B3),
        label = Brush.linearGradient(listOf(Color(0xFFFFB6C1), Color(0xFFFF6F91))),
        print = Color(0xFF5C3A21),
        footer = Color(0xFFD9A8B3),
    )
}

// Physical-object colors for the deck widgets. Widgets are props with their own
// colorways (not re-themed by the night palette), so their colors live here as
// named constants rather than as magic hexes scattered through the draw code.
private val TapeGold = Color(0xFFC79A3B)
private val TapeDark = Color(0xFF2A1E10)
private val HubCream = Color(0xFFEDE4D4)
private val WidgetCream = Color(0xFFF0E6CC)   // album-art label ink
private val TapeStripInk = Color(0xFF3A2A18)  // sepia ink on the now-playing label tape

/**
 * Cassette tape at real Compact Cassette proportions (~1.6:1): printed label
 * (or the album art) with a title band, a clear window mid-label showing two
 * golden reels that turn while music plays, SIDE A on the bare shell below,
 * corner screws. Colorway per [variant].
 */
@Composable
internal fun Cassette(
    playing: Boolean,
    albumArt: Bitmap?,
    variant: CassetteVariant,
    titleLabel: String? = null,
    track: String? = null,
    artist: String? = null,
) {
    val pal = cassettePalette(variant)
    val angle = animPhase(2400, running = playing) * 360f
    Box(
        modifier = Modifier
            .width(266.dp)
            .height(165.dp)
            .clip(RoundedCornerShape(10.dp))
            // Real shells (see UI Style References/casette reference.png) are
            // translucent colored plastic, not a flat opaque color - a slight
            // alpha plus the diagonal sheen below stand in for that candy-shell
            // gloss without needing a texture asset.
            .background(pal.shell.copy(alpha = 0.94f))
            .border(2.dp, pal.shellEdge, RoundedCornerShape(10.dp)),
    ) {
        // Glossy plastic sheen: a soft diagonal highlight band, upper-left to
        // lower-right, like light catching a molded shell.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.16f),
                            Color.Transparent,
                            Color.Transparent,
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(400f, 300f),
                    ),
                    shape = RoundedCornerShape(10.dp),
                ),
        )
        // Printed label: upper two-thirds of the face.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .height(106.dp)
                .clip(RoundedCornerShape(6.dp)),
        ) {
            if (albumArt != null) {
                Image(
                    bitmap = albumArt.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize().background(pal.label))
            }

            if (track != null) {
                // Now-playing: a stuck-on label-tape strip carrying the current
                // track + artist, in place of the printed title band. Cream
                // masking tape, slightly askew, sepia ink - a handwritten-mixtape
                // read that puts the song ON the cassette object itself.
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 5.dp)
                        .fillMaxWidth(0.94f)
                        .rotate(-1.5f)
                        .clip(RoundedCornerShape(2.dp))
                        .background(WidgetCream)
                        .border(1.dp, TapeStripInk.copy(alpha = 0.25f), RoundedCornerShape(2.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        track.uppercase(),
                        style = AriaType.neonLabel.copy(fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Black),
                        color = TapeStripInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    artist?.let {
                        Text(
                            it,
                            style = AriaType.neonLabel.copy(fontSize = 8.sp, letterSpacing = 1.sp),
                            color = TapeStripInk.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else {
                // Idle: the printed deck / mixtape title band.
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(
                            if (albumArt != null) Color.Black.copy(alpha = 0.35f) else Color.Transparent
                        )
                        .padding(top = 6.dp, bottom = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        titleLabel?.uppercase() ?: "DRIVE MIX",
                        style = AriaType.neonLabel.copy(fontSize = 13.sp, letterSpacing = 5.sp, fontWeight = FontWeight.Black),
                        color = if (albumArt != null) WidgetCream else pal.print,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // A driver-named mixtape has no katakana transliteration to show,
                    // so this second line is only the default deck's flavor text.
                    if (titleLabel == null) {
                        Text(
                            "ドライブ・ミックス",
                            style = AriaType.neonLabel.copy(fontSize = 8.sp, letterSpacing = 3.sp),
                            color = (if (albumArt != null) WidgetCream else pal.print).copy(alpha = 0.8f),
                        )
                    }
                }
            }

            // Clear tape window, set into the label like the reference shell.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
                    .width(200.dp)
                    .height(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(TapeDark)
                    .border(2.dp, pal.shell, RoundedCornerShape(8.dp)),
            ) {
                // Tape between the reels
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .width(90.dp)
                        .height(3.dp)
                        .background(TapeGold.copy(alpha = 0.5f)),
                )
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Reel(angle)
                    // The little clear leader window in the middle.
                    Box(
                        Modifier
                            .width(44.dp)
                            .height(20.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .border(1.dp, pal.shell.copy(alpha = 0.4f), RoundedCornerShape(3.dp)),
                    )
                    Reel(angle)
                }
            }
        }

        // Bare shell footer: SIDE A print.
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "SIDE A",
                style = AriaType.neonLabel.copy(fontSize = 10.sp, letterSpacing = 3.sp),
                color = pal.footer,
            )
        }
        Text(
            "ミッドナイト",
            style = AriaType.neonLabel.copy(fontSize = 9.sp, letterSpacing = 2.sp),
            color = pal.footer.copy(alpha = 0.8f),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 10.dp),
        )

        // Corner screws.
        val screwMod = Modifier.size(6.dp).clip(CircleShape)
            .background(pal.footer.copy(alpha = 0.6f))
            .border(1.dp, pal.footer, CircleShape)
        Box(Modifier.align(Alignment.TopStart).padding(4.dp)) { Box(screwMod) }
        Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) { Box(screwMod) }
        Box(Modifier.align(Alignment.BottomStart).padding(4.dp)) { Box(screwMod) }
        Box(Modifier.align(Alignment.BottomEnd).padding(4.dp)) { Box(screwMod) }

        // Write-protect notches: the small punched-out tabs on a real shell's
        // top edge (record labels snap them off; blank tapes leave them in).
        val notchMod = Modifier.width(10.dp).height(4.dp).clip(RoundedCornerShape(1.dp))
            .background(TapeDark.copy(alpha = 0.55f))
        Row(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(Modifier.padding(start = 34.dp)) { Box(notchMod) }
            Box(Modifier.padding(end = 34.dp)) { Box(notchMod) }
        }

        // Locating holes: the two small holes on the bottom edge a real deck's
        // pins seat into.
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).width(120.dp).padding(bottom = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            repeat(2) {
                Box(
                    Modifier.size(5.dp).clip(CircleShape)
                        .background(TapeDark.copy(alpha = 0.5f)),
                )
            }
        }

        // Tape-length badge, printed on the bare shell like a real cassette's
        // "C-90" spec text.
        Text(
            "C-90",
            style = AriaType.neonLabel.copy(fontSize = 7.sp, letterSpacing = 1.sp),
            color = pal.footer.copy(alpha = 0.7f),
            modifier = Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 12.dp),
        )
    }
}

/**
 * Cassette reel: golden tape pack around a cream hub with teeth, like the
 * reference shell's visible reels. Turns while music plays.
 */
@Composable
private fun Reel(angle: Float) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .rotate(angle)
            .clip(CircleShape)
            .background(TapeGold),
        contentAlignment = Alignment.Center,
    ) {
        // Hub with drive teeth (6 notches).
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(HubCream),
            contentAlignment = Alignment.Center,
        ) {
            repeat(3) { i ->
                Box(
                    Modifier
                        .rotate(i * 60f)
                        .width(2.dp)
                        .height(14.dp)
                        .background(TapeDark.copy(alpha = 0.7f)),
                )
            }
        }
    }
}

/**
 * Loads album art from a URI at a capped pixel size, downsampling to avoid OOM.
 * Runs on the IO dispatcher via the calling LaunchedEffect; returns null on any failure.
 */
internal suspend fun loadAlbumArt(context: Context, uri: String, sizePx: Int): Bitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            val scale = maxOf(1, maxOf(opts.outWidth, opts.outHeight) / sizePx)
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = scale }
            context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            }
        }.getOrNull()
    }
