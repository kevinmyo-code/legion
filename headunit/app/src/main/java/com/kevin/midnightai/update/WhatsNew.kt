package com.kevin.midnightai.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** One line of `GET /api/releases/history` (`server/releases/views.py`'s `ReleaseHistorySerializer`). */
data class ReleaseNote(val versionCode: Long, val versionName: String, val notes: String, val publishedAt: String)

/** Where "What's new" reads the notes from. Apart from [UpdateBackend] so the updater's fakes stay as they are. */
interface ReleaseHistorySource {
    /** Newest first. A failure is a failure: never an empty list standing in for one. */
    suspend fun history(): Result<List<ReleaseNote>>
}

sealed interface WhatsNewState {
    data object Loading : WhatsNewState
    data class Loaded(val releases: List<ReleaseNote>, val installedCode: Long) : WhatsNewState
    data class Unavailable(val sentence: String) : WhatsNewState
}

object ReleaseHistoryJson {
    /** Null when the answer is not a list of well-formed releases: one bad element spoils it, as [ReleaseJson] does. */
    fun parse(text: String): List<ReleaseNote>? {
        val array = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: return null
        val notes = array.mapNotNull { (it as? JsonObject)?.let(::note) }
        return notes.takeIf { it.size == array.size }
    }

    private fun note(o: JsonObject): ReleaseNote? {
        val code = num(o, "version_code")?.takeIf { it > 0 }
        val name = str(o, "version_name")
        return if (code == null || name == null) {
            null
        } else {
            ReleaseNote(code, name, str(o, "notes").orEmpty(), str(o, "published_at").orEmpty())
        }
    }

    private fun str(o: JsonObject, key: String): String? =
        (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun num(o: JsonObject, key: String): Long? =
        (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
}

object WhatsNew {
    const val NO_RELEASES = "The engine lists no releases yet."

    suspend fun load(source: ReleaseHistorySource, installedCode: Long): WhatsNewState =
        source.history().fold(
            onSuccess = { WhatsNewState.Loaded(it, installedCode) },
            onFailure = {
                WhatsNewState.Unavailable(
                    "Could not load the release notes: ${it.message ?: "the engine did not answer"}. " +
                        "That is the engine being out of reach, not that there are no releases.",
                )
            },
        )

    fun notesLine(note: ReleaseNote): String = note.notes.trim().ifEmpty { "This release has no notes." }

    /** Once per update: the installed version differs from the one last shown. A debug build (code 1) never. */
    fun due(installedCode: Long, seenCode: Long): Boolean = installedCode > 1 && installedCode != seenCode
}
