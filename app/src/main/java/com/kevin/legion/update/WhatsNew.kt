package com.kevin.legion.update

import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttpException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException

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

/** What went wrong before the engine could be asked or after it answered, so [WhatsNew.load] can say which. */
enum class ReleaseHistoryFailure { NOT_SIGNED_IN, UNREADABLE }

class ReleaseHistoryException(val kind: ReleaseHistoryFailure) : IOException(kind.name)

object WhatsNew {
    const val NO_RELEASES = "The engine lists no releases yet."
    private const val NOT_AN_EMPTY_LIST = " That does not mean there are no releases."

    suspend fun load(source: ReleaseHistorySource, installedCode: Long): WhatsNewState =
        source.history().fold(
            onSuccess = { WhatsNewState.Loaded(it, installedCode) },
            onFailure = {
                WhatsNewState.Unavailable("Could not load the release notes: ${cause(it)}.$NOT_AN_EMPTY_LIST")
            },
        )

    /** Only what was observed: a sign-in problem is never put down to the engine being away. */
    private fun cause(e: Throwable): String = when (e) {
        is ReleaseHistoryException -> when (e.kind) {
            ReleaseHistoryFailure.NOT_SIGNED_IN ->
                "this device is not signed in to the engine. Sign in under Settings, then try again"
            ReleaseHistoryFailure.UNREADABLE -> "the engine's reply could not be read"
        }
        is EngineHttpException -> when (val f = e.failure) {
            is EngineFailure.Unauthorized -> "this device's sign-in was not accepted. Sign in again under Settings"
            is EngineFailure.Unreachable -> "the engine could not be reached"
            is EngineFailure.Refused -> "the engine answered with an error (HTTP ${f.status})"
            is EngineFailure.Malformed -> "the engine's reply could not be read"
        }
        else -> e.message ?: "the reason is not known"
    }

    /** The version to record as seen: only once the notes themselves were on screen, never after a failed load. */
    fun seenToRecord(state: WhatsNewState): Long? = (state as? WhatsNewState.Loaded)?.installedCode

    fun notesLine(note: ReleaseNote): String = note.notes.trim().ifEmpty { "This release has no notes." }

    /**
     * Once per update: the installed version differs from the one last shown. A debug build (code 1) never,
     * and never while signed out: a fresh install has nothing to load yet, so opening over its first
     * screen would only show a failure. Settings still reaches the notes by hand.
     */
    fun due(installedCode: Long, seenCode: Long, signedIn: Boolean): Boolean =
        signedIn && installedCode > 1 && installedCode != seenCode
}
