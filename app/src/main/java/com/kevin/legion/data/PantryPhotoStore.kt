package com.kevin.legion.data

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

/**
 * Filesystem-backed store for grocery receipt photos, pending ingestion.
 * Replaces the old `PhotoAlbumStore` (named albums, cover art) per the
 * 2026-07-31 carry-over decision - pantry has no browsable album, this is
 * ingestion-only storage: a photo is saved here, handed to
 * [com.kevin.legion.pantry.PantryReceiptAgent] for extraction, then deleted
 * on success. On a quarantine (failed reconciliation), the file is kept so
 * the driver can inspect or retry without re-taking the photo.
 */
object PantryPhotoStore {
    private fun receiptsDir(context: Context) = File(context.filesDir, "pantry_receipts").apply { mkdirs() }

    /** Saves [source] (downscaled) as a new receipt photo, returns the file. */
    fun save(context: Context, source: Bitmap): File {
        val file = File(receiptsDir(context), "${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { source.scaledTo(1600).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return file
    }

    /** Deletes a receipt photo, but only if it belongs to the receipts dir. */
    fun delete(context: Context, file: File) {
        if (file.parentFile == receiptsDir(context)) file.delete()
    }

    private fun Bitmap.scaledTo(maxDim: Int): Bitmap {
        val longest = maxOf(width, height)
        if (longest <= maxDim) return this
        val scale = maxDim.toFloat() / longest
        return Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true)
    }
}
