package com.kevin.legion.games

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** The network seam. A fake stands in for it in tests. */
fun interface BoxArtFetcher {
    fun fetch(url: String): FetchResult
}

sealed interface FetchResult {
    class Found(val bytes: ByteArray) : FetchResult

    /** The server answered and has no such cover: a definite miss. */
    data object NotFound : FetchResult

    /** No network, timeout, or any other answer: unknown, not a statement about the cover. */
    data object Unreachable : FetchResult
}

/** `HttpURLConnection`, short timeouts, off the caller's thread by [BoxArtCache]. */
object HttpBoxArtFetcher : BoxArtFetcher {
    private const val TIMEOUT_MS = 8_000
    private const val HTTP_OK = 200
    private const val HTTP_NOT_FOUND = 404

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // any wire failure means "unreachable"
    override fun fetch(url: String): FetchResult = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        try {
            when (connection.responseCode) {
                HTTP_OK -> FetchResult.Found(connection.inputStream.use { it.readBytes() })
                HTTP_NOT_FOUND -> FetchResult.NotFound
                else -> FetchResult.Unreachable
            }
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        FetchResult.Unreachable
    }
}

/**
 * Box art, fetched once per game and kept in app-private storage ([dir]).
 *
 * A cover is `<sha1(url)>.png`. A miss is `<sha1(url)>.miss`, holding the epoch millis after which
 * to try again: a 404 is retried after [NOT_FOUND_RETRY_MS] (a week; libretro adds covers), a failed
 * fetch after [UNREACHABLE_RETRY_MS] (an hour; offline is not a statement about the cover). So
 * opening the sheet never refetches a recorded miss, and being offline costs at most one failed
 * attempt per game per hour.
 */
class BoxArtCache(
    private val dir: File,
    private val fetcher: BoxArtFetcher,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val gate = Semaphore(PARALLEL_FETCHES)

    /** The cover file for [game], or null when there is none (yet): the tile then says its title in words. */
    suspend fun get(game: Game): File? = withContext(Dispatchers.IO) {
        val url = BoxArtUrl.forGame(game)
        val key = sha1(url)
        val cover = File(dir, "$key.png")
        if (cover.exists()) return@withContext cover
        val miss = File(dir, "$key.miss")
        if (miss.exists() && !retryDue(miss)) return@withContext null
        gate.withPermit {
            // Another tile for the same cover may have finished while this one waited.
            if (cover.exists()) return@withPermit cover
            dir.mkdirs()
            when (val result = fetcher.fetch(url)) {
                is FetchResult.Found -> {
                    cover.writeBytes(result.bytes)
                    miss.delete()
                    cover
                }
                FetchResult.NotFound -> recordMiss(miss, NOT_FOUND_RETRY_MS)
                FetchResult.Unreachable -> recordMiss(miss, UNREACHABLE_RETRY_MS)
            }
        }
    }

    private fun recordMiss(miss: File, retryAfterMs: Long): File? {
        miss.writeText("${now() + retryAfterMs}")
        return null
    }

    private fun retryDue(miss: File): Boolean {
        val retryAt = miss.readText().trim().toLongOrNull() ?: return true
        return now() >= retryAt
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val NOT_FOUND_RETRY_MS = 7L * 24 * 60 * 60 * 1000
        const val UNREACHABLE_RETRY_MS = 60L * 60 * 1000
        private const val PARALLEL_FETCHES = 3
    }
}
