package com.kevin.legion.memory

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** What the cache holds: the engine's whole list as of [fetchedAtMs]. */
data class CachedMemories(val memories: List<MemberMemory>, val fetchedAtMs: Long)

/**
 * The phone's copy of the engine's memory list (map decision F). **Replaced whole on every refresh,
 * never merged**: the engine hard-deletes with no tombstone and has no `?since=` feed, so a merge
 * could never learn that a memory was forgotten, and a forgotten memory would live on here and be
 * read back offline. [replace] is the only write.
 *
 * Read when the engine cannot be reached (recall says it is the phone's copy) and by the session's
 * continuity note. An interface because tests need one with no file behind it.
 */
interface MemoryCache {
    suspend fun read(): CachedMemories?
    suspend fun replace(memories: List<MemberMemory>, fetchedAtMs: Long)
    /** Drops one row after a confirmed delete, so an offline read before the next refresh agrees. */
    suspend fun remove(id: String)
}

/** The in-memory cache tests use. */
class InMemoryMemoryCache(private var held: CachedMemories? = null) : MemoryCache {
    override suspend fun read(): CachedMemories? = held
    override suspend fun replace(memories: List<MemberMemory>, fetchedAtMs: Long) {
        held = CachedMemories(memories, fetchedAtMs)
    }
    override suspend fun remove(id: String) {
        held = held?.let { it.copy(memories = it.memories.filterNot { m -> m.id == id }) }
    }
}

@Serializable
private data class CacheRow(
    val id: String,
    val text: String,
    val kind: String,
    val importance: Int,
    val decayClass: String,
    val source: String,
    val shared: Boolean,
    val aboutYou: Boolean,
    val createdAtMs: Long,
    val lastRecalledAtMs: Long? = null,
    val fromConversation: String? = null,
)

@Serializable
private data class CacheFile(val fetchedAtMs: Long, val rows: List<CacheRow>)

/**
 * The production cache: one JSON file in the app's private files directory, written to a temp file
 * and renamed so a crash mid-write leaves the previous copy, never half of one. Unreadable is
 * treated as absent (the next refresh rewrites it), never as an empty list that would read as
 * "nothing remembered". Every call runs on [Dispatchers.IO], whatever thread asks (a screen's
 * ViewModel and `MainActivity.onResume` ask from Main). One [Mutex] serialises them.
 */
class FileMemoryCache(private val file: File) : MemoryCache {

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    override suspend fun read(): CachedMemories? = withContext(Dispatchers.IO) { lock.withLock { readNow() } }

    private fun readNow(): CachedMemories? {
        if (!file.exists()) return null
        return try {
            val parsed = json.decodeFromString(CacheFile.serializer(), file.readText())
            CachedMemories(parsed.rows.map { it.toMemory() }, parsed.fetchedAtMs)
        } catch (e: IOException) {
            null
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    override suspend fun replace(memories: List<MemberMemory>, fetchedAtMs: Long) =
        withContext(Dispatchers.IO) { lock.withLock { replaceNow(memories, fetchedAtMs) } }

    private fun replaceNow(memories: List<MemberMemory>, fetchedAtMs: Long) {
        val body = json.encodeToString(CacheFile.serializer(), CacheFile(fetchedAtMs, memories.map { it.toRow() }))
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(file)) {
                file.writeText(body)
                tmp.delete()
            }
        } catch (e: IOException) {
            // A cache that could not be written is a cache that is older than it should be; the
            // engine still holds every memory, so nothing is lost.
        }
    }

    override suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = readNow() ?: return@withLock
            replaceNow(current.memories.filterNot { it.id == id }, current.fetchedAtMs)
        }
    }

    private fun CacheRow.toMemory() = MemberMemory(
        id, text, kind, importance, decayClass, source, shared, aboutYou, createdAtMs, lastRecalledAtMs,
        fromConversation,
    )

    private fun MemberMemory.toRow() = CacheRow(
        id, text, kind, importance, decayClass, source, shared, aboutYou, createdAtMs, lastRecalledAtMs,
        fromConversation,
    )

    companion object {
        private const val FILE_NAME = "member_memories_cache.json"

        fun forContext(context: Context): FileMemoryCache =
            FileMemoryCache(File(context.applicationContext.filesDir, FILE_NAME))
    }
}
