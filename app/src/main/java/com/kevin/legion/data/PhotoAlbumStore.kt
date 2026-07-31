package com.kevin.legion.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** A driver-created photo album (a trip, an era) with its own generated cover. */
data class PhotoAlbum(
    val id: String,
    val name: String,
    val coverPrompt: String? = null,
    val createdAt: Long,
    val sortOrder: Int,
)

/**
 * Filesystem-backed store for named photo albums. Album metadata (name, cover
 * prompt, order) lives in a single JSON index; each album's photos and its
 * cover.png live in its own folder under filesDir/albums/<id>/. No Room table
 * (Kevin's call, 2026-07-14): albums don't participate in anything relational,
 * so this skips a DB migration and the head unit's uninstall-on-version-bump.
 */
object PhotoAlbumStore {
    private const val COVER = "cover.png"

    private fun albumsDir(context: Context) = File(context.filesDir, "albums")
    private fun indexFile(context: Context) = File(albumsDir(context), "index.json")
    private fun albumDir(context: Context, id: String) = File(albumsDir(context), sanitize(id))

    fun coverFile(context: Context, id: String) = File(albumDir(context, id), COVER)

    /** All albums, ordered by sortOrder then creation time. Empty if none yet. */
    fun list(context: Context): List<PhotoAlbum> {
        val f = indexFile(context)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PhotoAlbum(
                    id = o.getString("id"),
                    name = o.optString("name"),
                    coverPrompt = o.optString("coverPrompt").ifBlank { null },
                    createdAt = o.optLong("createdAt"),
                    sortOrder = o.optInt("sortOrder"),
                )
            }.sortedWith(compareBy({ it.sortOrder }, { it.createdAt }))
        }.getOrDefault(emptyList())
    }

    private fun writeIndex(context: Context, albums: List<PhotoAlbum>) {
        val arr = JSONArray()
        for (a in albums) {
            arr.put(
                JSONObject().apply {
                    put("id", a.id)
                    put("name", a.name)
                    put("coverPrompt", a.coverPrompt ?: "")
                    put("createdAt", a.createdAt)
                    put("sortOrder", a.sortOrder)
                },
            )
        }
        val f = indexFile(context)
        f.parentFile?.mkdirs()
        f.writeText(arr.toString())
    }

    fun create(context: Context, name: String): PhotoAlbum {
        val current = list(context)
        val album = PhotoAlbum(
            id = System.currentTimeMillis().toString(),
            name = name.trim().ifBlank { "New album" },
            createdAt = System.currentTimeMillis(),
            sortOrder = (current.maxOfOrNull { it.sortOrder } ?: -1) + 1,
        )
        albumDir(context, album.id).mkdirs()
        writeIndex(context, current + album)
        return album
    }

    fun rename(context: Context, id: String, name: String) {
        writeIndex(
            context,
            list(context).map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it },
        )
    }

    fun setCoverPrompt(context: Context, id: String, prompt: String) {
        writeIndex(context, list(context).map { if (it.id == id) it.copy(coverPrompt = prompt) else it })
    }

    fun delete(context: Context, id: String) {
        albumDir(context, id).deleteRecursively()
        writeIndex(context, list(context).filterNot { it.id == id })
    }

    /** An album's photos (newest first), excluding its cover image. */
    fun photos(context: Context, id: String): List<File> =
        albumDir(context, id).listFiles { f -> f.extension == "png" && f.name != COVER }
            ?.sortedByDescending { it.name } ?: emptyList()

    fun photoCount(context: Context, id: String): Int = photos(context, id).size

    /** Saves a picked/captured photo (downscaled) into the album, returns its path. */
    fun addPhoto(context: Context, id: String, source: Bitmap): String? = runCatching {
        val dir = albumDir(context, id)
        dir.mkdirs()
        val file = File(dir, "${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { source.scaledTo(1280).compress(Bitmap.CompressFormat.PNG, 100, it) }
        file.absolutePath
    }.getOrNull()

    /** Deletes a photo by path, but only if it belongs to [id]'s folder. */
    fun deletePhoto(context: Context, id: String, path: String) {
        val f = File(path)
        if (f.parentFile == albumDir(context, id)) f.delete()
    }

    fun saveCover(context: Context, id: String, bitmap: Bitmap) {
        val f = coverFile(context, id)
        f.parentFile?.mkdirs()
        FileOutputStream(f).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    fun cover(context: Context, id: String): Bitmap? =
        coverFile(context, id).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }

    // Album ids are our own timestamps, but sanitize defensively for a filename.
    private fun sanitize(id: String): String = id.replace(Regex("[^A-Za-z0-9_]"), "_")

    private fun Bitmap.scaledTo(maxDim: Int): Bitmap {
        val longest = maxOf(width, height)
        if (longest <= maxDim) return this
        val scale = maxDim.toFloat() / longest
        return Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true)
    }
}
