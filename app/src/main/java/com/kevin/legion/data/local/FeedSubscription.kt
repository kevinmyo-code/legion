package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A feed URL Kevin typed - one-home ticket 07, on ticket 06's resolution: *"Subscriptions
 * (the feed URLs Kevin types) DO persist. They are his own data under any reading."* Distinct on
 * purpose from a feed ITEM ([com.kevin.legion.news.FeedFetcher]'s `FeedHeadline`, which is never
 * stored anywhere - not even a cache file): this table names the feed, never what the feed said.
 *
 * **Local-only, no sync columns** ([guid]/[serverId]/etc. that [BodyweightLog] and similar rows
 * carry are absent here on purpose). Ticket 06's resolution says a subscription list "is
 * unambiguously his, ... synced like any other record", but one-home's own map states its
 * execution boundary in so many words: *"Everything here is in `app/`; nothing touches `server/`
 * ... so this map can run alongside the `web-and-households` work without a worktree collision."*
 * A Django endpoint plus an Android sync leg is a second ticket's worth of work this map's own
 * scope note puts out of bounds, so this table follows [SitrepModuleSetting]/[SitrepSchedule]'s
 * own precedent instead: a small piece of Kevin's own configuration, persisted locally, with no
 * engine leg (yet). **Surfaced rather than silently decided** - see this ticket's own report for
 * the fork this leaves open.
 */
@Entity(tableName = "feed_subscriptions", indices = [Index(value = ["url"], unique = true)])
data class FeedSubscription(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    /** An optional label Kevin gives the feed. Null renders as the URL itself - see
     * [com.kevin.legion.ui.news.NewsScreen]'s own row. */
    val title: String?,
    val addedAtMs: Long,
)

@Dao
interface FeedSubscriptionDao {
    @Query("SELECT * FROM feed_subscriptions ORDER BY addedAtMs ASC")
    fun observeAll(): Flow<List<FeedSubscription>>

    @Query("SELECT * FROM feed_subscriptions ORDER BY addedAtMs ASC")
    suspend fun all(): List<FeedSubscription>

    @Insert
    suspend fun insert(subscription: FeedSubscription): Long

    @Query("DELETE FROM feed_subscriptions WHERE id = :id")
    suspend fun delete(id: Long)
}
