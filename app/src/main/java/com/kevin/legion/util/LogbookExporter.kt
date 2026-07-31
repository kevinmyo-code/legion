package com.kevin.legion.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File

/**
 * One entry shown on the exported share card: title, human-readable date string,
 * and the category tag ("maintenance", "mod", "repair", etc.).
 */
data class ShareEntry(val title: String, val date: String, val category: String)

/**
 * Renders a logbook share card to a PNG and fires the system share sheet.
 *
 * The card is a fixed 800x600 bitmap rendered entirely with [android.graphics.Canvas]
 * and [android.graphics.Paint] — no Compose involved — so it can be produced on
 * [kotlinx.coroutines.Dispatchers.IO] without touching the UI thread.
 *
 * Card layout (portrait of the logbook at a glance):
 *  - Deep teal-charcoal surround matching AriaColors.Background.
 *  - Aged-paper cream inner card with 24 px margin and 12 px rounded corners.
 *  - Amber header: car label in bold serif.
 *  - Entry list: up to 5 entries with category tag, title, and right-aligned date.
 *  - Dark footer strip: tagline on the left, QR link on the right, watermark center.
 */
object LogbookExporter {

    private const val CARD_W = 800
    private const val CARD_H = 600

    // The Play listing is deterministic from the applicationId, so the QR resolves
    // the moment the app is published. Replace only if distribution moves off Play
    // (CLAUDE.md sec 2 lists Gumroad/itch.io as possible v1.1).
    private const val DOWNLOAD_URL =
        "https://play.google.com/store/apps/details?id=com.kevin.legion"

    private const val QR_SIZE = 120

    /**
     * Renders the share card on the calling thread (must be IO-dispatched by the
     * caller), writes it to [Context.getCacheDir], and fires [Intent.ACTION_SEND].
     *
     * @param context   An Activity or application context.
     * @param carLabel  Display label from [com.kevin.legion.vehicle.VehicleController],
     *                  e.g. "1998 Jeep Cherokee".
     * @param entries   Up to 5 entries to show; caller supplies the most-recent ones.
     */
    fun share(context: Context, carLabel: String, entries: List<ShareEntry>) {
        val bmp = render(carLabel, entries)
        val file = File(context.cacheDir, "midnight_logbook_export.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 95, it) }
        bmp.recycle()

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "Logged with MIDNIGHT AI")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "Share logbook")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    /**
     * Draws the share card bitmap.
     *
     * Pixel budget (800 x 600):
     *  - Outer margin: 24 px all sides â†’ cream card from (24,24) to (776,576)
     *  - Header zone: card top to yâ‰ˆ99 (42px amber serif title + 2px amber rule)
     *  - Entry zone: yâ‰ˆ107 to yâ‰ˆ438 (5 slots of ~66 px, category line + title line)
     *  - Footer strip: y=446 to y=576 (dark, 130 px; QR 120x120 right side)
     */
    private fun render(carLabel: String, entries: List<ShareEntry>): Bitmap {
        val bmp = Bitmap.createBitmap(CARD_W, CARD_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Solid-fill paint; color is set before each draw call so it is reused
        // for all fill operations rather than allocating a new Paint per shape.
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        // One paint object per distinct text role.
        val pHeader = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFB02E.toInt()          // amber phosphor
            textSize = 42f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val pCategory = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF2E7D6B.toInt()          // Patina green
            textSize = 19f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }
        val pTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF2C2018.toInt()          // deep sepia ink
            textSize = 24f
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            textAlign = Paint.Align.LEFT
        }
        val pDate = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF6E5C46.toInt()          // faded ink
            textSize = 19f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            textAlign = Paint.Align.RIGHT
        }
        val pFooterTag = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFB02E.toInt()
            textSize = 19f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }
        val pWatermark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF4A3828.toInt()          // very dim sepia — recedes behind QR
            textSize = 15f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }

        // -- Background -------------------------------------------------------
        fill.color = 0xFF0D1418.toInt()
        canvas.drawRect(0f, 0f, CARD_W.toFloat(), CARD_H.toFloat(), fill)

        // -- Aged-paper card --------------------------------------------------
        fill.color = 0xFFEDE0C8.toInt()
        canvas.drawRoundRect(24f, 24f, 776f, 576f, 12f, 12f, fill)

        // -- Header: car label in amber ---------------------------------------
        // Title baseline y=82 puts the cap-height top at roughly y=44 (innerPad).
        val labelText = truncate(pHeader, (carLabel.ifBlank { "MY CAR" }).uppercase(), 712f)
        canvas.drawText(labelText, 400f, 82f, pHeader)

        // Thin amber rule underneath the header text.
        fill.color = 0xFFFFB02E.toInt()
        canvas.drawRect(44f, 97f, 756f, 99f, fill)

        // -- Footer dark strip ------------------------------------------------
        // Clip the full card rounded-rect to the footer region so the bottom
        // corners stay rounded while the fill becomes dark.
        fill.color = 0xFF0D1418.toInt()
        canvas.save()
        canvas.clipRect(24f, 446f, 776f, 576f)
        canvas.drawRoundRect(24f, 24f, 776f, 576f, 12f, 12f, fill)
        canvas.restore()

        // Tagline (left side of footer).
        canvas.drawText("MIDNIGHT AI — the car that knows itself", 44f, 492f, pFooterTag)

        // Watermark centered below the tagline.
        canvas.drawText("SCAN TO INSTALL", 400f, 518f, pWatermark)

        // QR code (right side of footer; 120x120 centered vertically in 130 px strip).
        val qr = makeQr(DOWNLOAD_URL, QR_SIZE)
        // qrLeft = contentRight(756) - QR_SIZE(120) = 636; qrTop = 446 + (130-120)/2 = 451
        canvas.drawBitmap(qr, 636f, 451f, null)
        qr.recycle()

        // -- Entry list -------------------------------------------------------
        val displayEntries = entries.take(5)
        // Distribute available vertical space evenly across however many entries exist.
        val entryH = if (displayEntries.isEmpty()) 66f
            else (438f - 107f) / displayEntries.size

        displayEntries.forEachIndexed { i, entry ->
            val ey = 107f + i * entryH

            // Category tag: small monospace bold, patina green, top line.
            canvas.drawText(entry.category.uppercase(), 44f, ey + 22f, pCategory)

            // Title with em-dash bullet on the second line; truncated to leave
            // 170 px of space on the right for the date.
            val rawTitle = "— ${entry.title}"
            val titleText = truncate(pTitle, rawTitle, 542f) // 756-44-170 = 542
            canvas.drawText(titleText, 44f, ey + 50f, pTitle)

            // Date right-aligned at the same baseline as the title.
            canvas.drawText(entry.date, 756f, ey + 50f, pDate)

            // Hairline divider between entries (not after the last one).
            if (i < displayEntries.size - 1) {
                fill.color = 0xFFE3CFA4.toInt()
                val divY = ey + entryH - 1f
                canvas.drawRect(44f, divY, 756f, divY + 1f, fill)
            }
        }

        return bmp
    }

    /**
     * Shortens [text] until [paint] can measure it fitting within [maxWidth],
     * appending an ellipsis if any characters were removed.
     */
    private fun truncate(paint: Paint, text: String, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var s = text
        while (s.isNotEmpty() && paint.measureText("$s…") > maxWidth) {
            s = s.dropLast(1)
        }
        return "$s…"
    }

    /**
     * Encodes [url] as a QR code bitmap of [size]x[size] pixels. Dark modules use
     * the Aria background color (0xFF0D1418) and light modules use the card cream
     * (0xFFEDE0C8) so the code reads as "printed on the card" rather than floating.
     *
     * RGB_565 is sufficient for a two-color image and halves the memory footprint.
     */
    private fun makeQr(url: String, size: Int): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size, hints)
        val qrBmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                qrBmp.setPixel(x, y, if (matrix[x, y]) 0xFF0D1418.toInt() else 0xFFEDE0C8.toInt())
            }
        }
        return qrBmp
    }
}
