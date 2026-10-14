package com.kevin.midnightai.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.kevin.midnightai.data.local.CarDatabase
import com.kevin.midnightai.data.local.LibraryTrack
import com.kevin.midnightai.data.local.Mixtape
import com.kevin.midnightai.data.local.MixtapeTrack
import java.io.File
import java.util.UUID

/**
 * Imports sideloaded audio files into the general library (the "drawer").
 * Files are COPIED into filesDir/library/<uuid>.<ext> immediately - the app
 * never depends on a USB stick staying plugged in or a SAF permission grant
 * surviving reboot (both are unreliable on vendor head-unit AOSP builds). No
 * storage permission is needed: the picker (OpenMultipleDocuments, audio MIME
 * wildcard) surfaces USB volumes as SAF document providers and grants per-uri access.
 */
object MixtapeLibrary {
    private const val TAG = "MixtapeLibrary"

    /**
     * Copies each picked file into the library, reads its tags, and inserts a
     * [LibraryTrack] row. If [intoMixtapeId] is given, each imported track is
     * also appended to that mixtape. Returns how many imported cleanly.
     */
    suspend fun importFiles(context: Context, uris: List<Uri>, intoMixtapeId: Long? = null): Int {
        val db = CarDatabase.getDatabase(context)
        var imported = 0
        for (uri in uris) {
            runCatching { importOne(context, uri, db, intoMixtapeId) }
                .onSuccess { imported++ }
                .onFailure { Log.w(TAG, "import failed for $uri: ${it.message}") }
        }
        return imported
    }

    private suspend fun importOne(
        context: Context,
        uri: Uri,
        db: CarDatabase,
        intoMixtapeId: Long?,
    ) {
        val sourceName = displayNameOf(context, uri)
        val ext = sourceName.substringAfterLast('.', "mp3")
        val dest = File(libraryDir(context), "${UUID.randomUUID()}.$ext")
        dest.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Could not open $uri")

        val tags = readTags(dest)
        val track = LibraryTrack(
            filePath = dest.absolutePath,
            title = tags.title.ifBlank { sourceName.substringBeforeLast('.') },
            artist = tags.artist,
            album = tags.album,
            durationMs = tags.durationMs,
            sourceName = sourceName,
            addedAt = System.currentTimeMillis(),
        )
        val trackId = db.libraryTrackDao().insert(track)

        if (intoMixtapeId != null) {
            addToMixtape(db, intoMixtapeId, trackId)
        }
    }

    /** Appends a library track to a mixtape (no-op if it's already the last entry re-added). */
    suspend fun addToMixtape(db: CarDatabase, mixtapeId: Long, libraryTrackId: Long) {
        val nextOrder = (db.mixtapeTrackDao().maxSortOrder(mixtapeId) ?: -1) + 1
        db.mixtapeTrackDao().insert(
            MixtapeTrack(
                mixtapeId = mixtapeId,
                libraryTrackId = libraryTrackId,
                sortOrder = nextOrder,
                addedAt = System.currentTimeMillis(),
            )
        )
    }

    /** Creates a new, empty mixtape and returns its id. */
    suspend fun createMixtape(context: Context, name: String): Long {
        val db = CarDatabase.getDatabase(context)
        val nextOrder = (db.mixtapeDao().getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1
        return db.mixtapeDao().insert(
            Mixtape(name = name, createdAt = System.currentTimeMillis(), sortOrder = nextOrder)
        )
    }

    /** Deletes a tape and its membership rows - library tracks and files are untouched. */
    suspend fun deleteMixtape(context: Context, mixtapeId: Long) {
        val db = CarDatabase.getDatabase(context)
        db.mixtapeTrackDao().deleteAllForMixtape(mixtapeId)
        db.mixtapeDao().delete(mixtapeId)
    }

    /** Removes a track from the library entirely: membership rows, the row, and the file. */
    suspend fun deleteFromLibrary(context: Context, libraryTrackId: Long) {
        val db = CarDatabase.getDatabase(context)
        val track = db.libraryTrackDao().getById(libraryTrackId)
        db.mixtapeTrackDao().deleteAllForLibraryTrack(libraryTrackId)
        db.libraryTrackDao().delete(libraryTrackId)
        track?.let { runCatching { File(it.filePath).delete() } }
    }

    private data class Tags(val title: String, val artist: String, val album: String, val durationMs: Long)

    private fun readTags(file: File): Tags {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            Tags(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: "",
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "",
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "",
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
            )
        } catch (e: Exception) {
            Tags(title = "", artist = "", album = "", durationMs = 0L)
        } finally {
            retriever.release()
        }
    }

    private fun displayNameOf(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (col >= 0 && cursor.moveToFirst()) return cursor.getString(col)
        }
        return uri.lastPathSegment ?: "track"
    }

    private fun libraryDir(context: Context): File = File(context.filesDir, "library")
}
