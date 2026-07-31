package com.kevin.legion.vehicle

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.CarTask

/**
 * The voice-managed car to-do / wishlist ([CarTask]) - the open-ended companion
 * to the scheduled maintenance brain ([VehicleController]/[com.kevin.legion.data.local.MaintenanceItem]).
 *
 * Mirrors the natural-language helper pattern of
 * [com.kevin.legion.location.ReminderController]: the Live tools in `LiveToolbox`
 * call these and hand the returned line back for Zero to phrase. Completing and
 * removing match the driver's spoken phrase against the open items (exact ->
 * substring -> word overlap), so "check off the bushings" finds "replace the
 * front bushings".
 */
object CarTaskController {
    private val CATEGORIES = setOf("maintenance", "project", "wishlist")

    private fun dao(context: Context) = CarDatabase.getDatabase(context).carTaskDao()

    /** Adds an item. [category] is a loose hint from Zero; normalized or left "general". */
    suspend fun add(context: Context, text: String, category: String): String {
        val t = text.trim()
        if (t.isBlank()) return "I didn't catch what to add - say it again?"
        dao(context).insert(
            CarTask(text = t, category = normalizeCategory(category), createdAt = System.currentTimeMillis())
        )
        return "Added \"$t\" to your car list."
    }

    /** Marks the best-matching open item done; null if nothing matched. */
    suspend fun complete(context: Context, query: String): String? {
        val match = match(query, dao(context).getOpen()) ?: return null
        dao(context).markDone(match.id, System.currentTimeMillis())
        return "Checked off \"${match.text}\"."
    }

    /** Removes the best-matching open item; null if nothing matched. */
    suspend fun remove(context: Context, query: String): String? {
        val match = match(query, dao(context).getOpen()) ?: return null
        dao(context).deleteById(match.id)
        return "Took \"${match.text}\" off your list."
    }

    suspend fun openTasks(context: Context): List<CarTask> = dao(context).getOpen()

    suspend fun openCount(context: Context): Int = dao(context).openCount()

    /** Check off an item by id (the control-panel list operates on ids, not phrases). */
    suspend fun markDone(context: Context, id: Long) = dao(context).markDone(id, System.currentTimeMillis())

    /** Remove an item by id (control-panel list). */
    suspend fun delete(context: Context, id: Long) = dao(context).deleteById(id)

    private fun normalizeCategory(raw: String): String {
        val c = raw.trim().lowercase()
        return when {
            c in CATEGORIES -> c
            c.isBlank() -> "general"
            c.contains("maint") || c.contains("repair") || c.contains("service") -> "maintenance"
            c.contains("buy") || c.contains("wish") || c.contains("accessor") -> "wishlist"
            c.contains("project") || c.contains("build") || c.contains("mod") || c.contains("swap") -> "project"
            else -> "general"
        }
    }

    /** Exact -> substring (either direction) -> best word-overlap match. */
    private fun match(query: String, tasks: List<CarTask>): CarTask? {
        val q = query.trim().lowercase()
        if (q.isBlank()) return null
        tasks.firstOrNull { it.text.lowercase() == q }?.let { return it }
        tasks.firstOrNull { it.text.lowercase().contains(q) || q.contains(it.text.lowercase()) }?.let { return it }
        val qWords = words(q)
        if (qWords.isEmpty()) return null
        return tasks
            .map { it to (words(it.text.lowercase()) intersect qWords).size }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }

    /** Content words (>2 chars), so stopwords like "the"/"a" don't drive a match. */
    private fun words(s: String): Set<String> =
        s.split(Regex("\\W+")).filter { it.length > 2 }.toSet()
}
