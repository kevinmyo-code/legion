package com.kevin.legion.news

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.FeedSubscription
import kotlinx.coroutines.flow.Flow

/**
 * Add/remove for [FeedSubscription] - the hands path ticket 06's resolution names explicitly
 * ("adding or removing one is a hands path", ADR 0035), reached from `ui/news/NewsScreen.kt`.
 * There is no voice tool for this ticket - see this ticket's own report for why that is a gap
 * surfaced rather than silently accepted.
 */
object FeedSubscriptionController {

    fun observeAll(context: Context): Flow<List<FeedSubscription>> =
        CarDatabase.getDatabase(context).feedSubscriptionDao().observeAll()

    /** [AddResult.Refused] covers both a blank/non-http(s) URL and a duplicate of one already
     * subscribed - the unique index on [FeedSubscription.url] is what a duplicate insert actually
     * trips, caught here rather than left to crash the tap. */
    sealed interface AddResult {
        object Added : AddResult
        data class Refused(val reason: String) : AddResult
    }

    suspend fun add(context: Context, url: String, title: String?): AddResult {
        val trimmed = url.trim()
        if (trimmed.isBlank()) {
            return AddResult.Refused("A feed needs a URL.")
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return AddResult.Refused("That does not look like a web address (needs http:// or https://).")
        }
        val dao = CarDatabase.getDatabase(context).feedSubscriptionDao()
        return try {
            dao.insert(
                FeedSubscription(
                    url = trimmed,
                    title = title?.trim()?.takeIf { it.isNotBlank() },
                    addedAtMs = System.currentTimeMillis(),
                ),
            )
            AddResult.Added
        } catch (e: SQLiteConstraintException) {
            AddResult.Refused("Already subscribed to that feed.")
        }
    }

    suspend fun remove(context: Context, id: Long) {
        CarDatabase.getDatabase(context).feedSubscriptionDao().delete(id)
    }
}
