package com.kevin.legion.data

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

/**
 * Filesystem-backed store for [com.kevin.legion.data.local.FieldType.PHOTO] field values on any
 * generated aspect-engine record (`ui/generated/GeneratedFormScreen.kt`'s photo picker) - the engine
 * twin of [PantryPhotoStore], same shape, different directory, kept SEPARATE rather than shared
 * because pantry's store is ingestion-only (a photo is deleted once extraction succeeds) while an
 * engine record's photo is the record's own permanent data, kept for the record's whole lifetime.
 */
object EnginePhotoStore {
    private fun photosDir(context: Context) = File(context.filesDir, "engine_photos").apply { mkdirs() }

    /** Saves [source] (downscaled) as a new engine-record photo, returns its absolute path - the
     * exact string a [com.kevin.legion.data.local.FieldType.PHOTO] field stores in the record payload. */
    fun save(context: Context, source: Bitmap): String {
        val file = File(photosDir(context), "${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { source.scaledTo(1600).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return file.absolutePath
    }

    private fun Bitmap.scaledTo(maxDim: Int): Bitmap {
        val longest = maxOf(width, height)
        if (longest <= maxDim) return this
        val scale = maxDim.toFloat() / longest
        return Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true)
    }
}
