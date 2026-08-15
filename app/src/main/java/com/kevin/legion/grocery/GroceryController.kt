package com.kevin.legion.grocery

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.GroceryItem
import com.kevin.legion.data.local.GroceryStaple

/**
 * The grocery trip: build it, shop it, tear it down (Kevin, 2026-08-11 - "a grocery list, made once
 * and torn down once grocery is complete").
 *
 * Same natural-language-helper shape as [com.kevin.legion.notes.NotesController]: `LiveToolbox`'s
 * voice tool calls these and phrases the returned facts, `ui/notes/GroceryScreen.kt` calls the same
 * functions, and the pure part (matching a spoken item, folding a finished trip into staples) lives
 * in `grocery/GroceryLogic.kt` so it tests without a `Context`.
 *
 * **A trip is in progress exactly when [items] is non-empty.** There is no separate active flag -
 * see [GroceryItem]'s doc comment for why a `GroceryTrip` row would only add a state that can
 * disagree with its own contents.
 */
object GroceryController {
    private fun db(context: Context) = CarDatabase.getDatabase(context)

    /** How many staples to offer when starting a trip. Enough to be useful, few enough to scan. */
    const val SUGGESTION_LIMIT = 8

    // ------------------------------------------------------------------------------ reading

    suspend fun items(context: Context): List<GroceryItem> = db(context).groceryItemDao().getAll()

    suspend fun tripInProgress(context: Context): Boolean = db(context).groceryItemDao().count() > 0

    /**
     * Staples to offer, most-bought first, EXCLUDING anything already on the current trip - a
     * suggestion the driver has already added is not a suggestion, it is a duplicate waiting to be
     * tapped. Matching is on the normalised name, so "Milk" on the list suppresses the "milk"
     * staple.
     */
    suspend fun suggestions(context: Context, limit: Int = SUGGESTION_LIMIT): List<GroceryStaple> {
        val onList = items(context).map { normalizeGroceryName(it.text) }.toSet()
        // Over-fetch so filtering out what is already on the list cannot leave a short row of
        // suggestions when there are plenty of other staples to offer.
        return db(context).groceryStapleDao().topStaples(limit * 3)
            .filter { it.name !in onList }
            .take(limit)
    }

    // ------------------------------------------------------------------------------ writing

    /**
     * Appends one item. **Merges rather than duplicating**: saying "milk" twice on one trip means
     * you want milk, not two lines of it. Returns the row as it now stands, whether created or
     * matched.
     */
    suspend fun addItem(context: Context, text: String): GroceryItem {
        val trimmed = text.trim()
        val now = System.currentTimeMillis()
        val existing = items(context).firstOrNull {
            normalizeGroceryName(it.text) == normalizeGroceryName(trimmed)
        }
        if (existing != null) {
            // Re-adding something already ticked is the driver correcting themselves ("no, I still
            // need milk"), so it comes back UNTICKED rather than silently staying in the basket.
            if (existing.done) {
                db(context).groceryItemDao().markUndone(existing.id, now)
                return existing.copy(done = false, doneAt = null, updatedAt = now)
            }
            return existing
        }
        val item = GroceryItem(
            text = trimmed,
            sortOrder = db(context).groceryItemDao().count(),
            createdAt = now,
            updatedAt = now,
        )
        return item.copy(id = db(context).groceryItemDao().insert(item))
    }

    suspend fun tick(context: Context, item: GroceryItem) =
        db(context).groceryItemDao().markDone(item.id, System.currentTimeMillis())

    suspend fun untick(context: Context, item: GroceryItem) =
        db(context).groceryItemDao().markUndone(item.id, System.currentTimeMillis())

    suspend fun removeItem(context: Context, item: GroceryItem) =
        db(context).groceryItemDao().deleteById(item.id)

    /** Fuzzy-matches [query] against the trip's items - the voice path's "tick off the milk". */
    suspend fun findItem(context: Context, query: String): GroceryMatch =
        matchGroceryItem(query, items(context))

    /**
     * **Completes the trip: folds what was actually bought into the staples memory, then deletes
     * every row.** This is the teardown half of "made once, torn down once complete".
     *
     * Only TICKED items count toward [GroceryStaple.timesBought] - an item left unticked was not
     * bought, and counting it would teach the suggestions the opposite of the truth
     * ([GroceryItem.done]). Unticked items are still deleted: the trip is over either way, and a
     * list that quietly kept its leftovers would stop being "made once, torn down once".
     *
     * Returns a [TripSummary] so the caller can say, in words, what was kept and what was dropped -
     * a teardown that silently discards items the driver never bought is exactly the kind of
     * invisible data loss this repo keeps having to fix.
     */
    suspend fun completeTrip(context: Context): TripSummary {
        val all = items(context)
        if (all.isEmpty()) return TripSummary(bought = 0, skipped = 0, boughtNames = emptyList())

        val now = System.currentTimeMillis()
        val bought = all.filter { it.done }
        for (item in bought) {
            val key = normalizeGroceryName(item.text)
            if (key.isBlank()) continue
            val existing = db(context).groceryStapleDao().getByName(key)
            db(context).groceryStapleDao().upsert(
                GroceryStaple(
                    name = key,
                    // Keep the most recent spelling the driver used, not the first one ever seen.
                    displayName = item.text.trim(),
                    timesBought = (existing?.timesBought ?: 0) + 1,
                    lastBoughtAt = now,
                    // Reuse the existing row's syncId so a staple keeps one identity across trips.
                    syncId = existing?.syncId ?: java.util.UUID.randomUUID().toString(),
                )
            )
        }
        db(context).groceryItemDao().clearAll()
        return TripSummary(
            bought = bought.size,
            skipped = all.size - bought.size,
            boughtNames = bought.map { it.text.trim() },
        )
    }

    /**
     * Abandons the trip WITHOUT recording anything to staples - the "I never went" escape hatch.
     * Separate from [completeTrip] on purpose: folding an abandoned list into the staples memory
     * would count things as bought that were never bought.
     */
    suspend fun abandonTrip(context: Context): Int {
        val count = db(context).groceryItemDao().count()
        db(context).groceryItemDao().clearAll()
        return count
    }

    /** Forgets one staple - the only way to correct a suggestion list that has learned something wrong. */
    suspend fun forgetStaple(context: Context, name: String) =
        db(context).groceryStapleDao().deleteByName(normalizeGroceryName(name))
}
