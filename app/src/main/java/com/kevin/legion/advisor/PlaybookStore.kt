package com.kevin.legion.advisor

import android.content.Context
import android.util.Log
import com.kevin.legion.ai.ActiveCompanionProfile
import java.io.File

/**
 * The driver's own copy of each [PrimingTopic]'s doctrine, as a plain UTF-8 text file on disk
 * (2026-08-18).
 *
 * **Why files and not a Room table.** Three reasons, in order of weight. (1) It needs no schema
 * change, so nothing about this can go wrong the way a migration can - CLAUDE.md §5's bar for a
 * new table is a verbatim generated-SQL migration plus a committed schema JSON, and a blob of
 * prose with no queryable structure earns none of that. (2) The whole point of the feature is that
 * doctrine is INSPECTABLE prose the driver can read and correct; a text file is the honest
 * representation of that, a TEXT column in a binary database is not. (3) One file per topic per
 * profile makes "revert to the shipped default" a delete, which cannot half-fail.
 *
 * **Keyed per companion profile.** Two drivers share one Google account and one roster
 * ([ActiveCompanionProfile]'s doc comment) but must not share training doctrine or money rules.
 * The path is `filesDir/playbooks/<profileId>/<topic.key>.md`, with the id sanitised because it
 * reaches the filesystem. A device that has never picked a profile writes under `default`, which
 * is a real directory and not a sentinel - a driver who never touches the roster still gets a
 * working, editable playbook.
 *
 * **NOT synced, deliberately, for now.** `sync/` snapshots Room tables through [
 * com.kevin.legion.sync.SyncCodec]; nothing in it carries files, and Drive still has no
 * compare-and-swap (CLAUDE.md §2 finding 2), so a shared last-write-wins playbook file across two
 * phones would silently eat one driver's edit. Local-only is the correct state until sync is
 * append-only. Stated here so a later sync pass finds the reason rather than the omission.
 *
 * **Blocking file IO, on purpose.** These are single-digit-KB files read once per advisor exchange
 * or dispatch, not per frame. Callers on the UI thread must still wrap them (`Dispatchers.IO`) -
 * every caller in the app today is already inside a coroutine.
 */
/**
 * What one [PlaybookStore.save] did, and when it refused, why - so the editor screen can say it in
 * words rather than showing a bare failure or, worse, appearing to succeed.
 */
sealed class PlaybookSaveResult {
    /** The driver's edit is now what every advisor and dispatcher for this topic reads. */
    object Saved : PlaybookSaveResult()

    /** The edit was blank or identical to the shipped text, so the override was dropped and the
     * shipped playbook is in force again. A success, not a failure - see [PlaybookStore.save]. */
    object RevertedToDefault : PlaybookSaveResult()

    /** Refused: over the token ceiling the shipped playbooks are held to. */
    data class TooLong(val actualChars: Int, val maxChars: Int) : PlaybookSaveResult()

    /** Refused: the edit dropped one or more professional-referral or estimate-phrasing lines the
     * doctrine is required to carry. [missing] names them so the screen can say which. */
    data class MissingBoundaries(val missing: List<String>) : PlaybookSaveResult()

    /** The write itself failed. Nothing was stored; the previous text still stands. */
    object WriteFailed : PlaybookSaveResult()
}

object PlaybookStore {
    private const val TAG = "PlaybookStore"
    private const val DIR = "playbooks"
    private const val DEFAULT_PROFILE = "default"

    /** The shipped playbook - what [text] falls back to and what [revertToDefault] restores. */
    fun defaultText(topic: PrimingTopic): String = topic.defaultText

    /**
     * What should actually ride a prompt for [topic]: the driver's edit if there is a non-blank
     * one, else the shipped default. Never returns blank - a blank file is treated as "no
     * override" rather than as "prime with nothing", because an empty playbook silently strips
     * domain knowledge from an advisor that is still expected to answer, which is a failure mode
     * that looks exactly like working software.
     */
    fun text(context: Context, topic: PrimingTopic): String {
        val custom = readOverride(context, topic)
        return if (custom.isNullOrBlank()) topic.defaultText else custom
    }

    /** True when the driver has saved an edit that differs from the shipped default. Drives the
     * "edited" marker and the Revert affordance on the editor screen. */
    fun isCustomised(context: Context, topic: PrimingTopic): Boolean {
        val custom = readOverride(context, topic)
        return !custom.isNullOrBlank() && custom.trim() != topic.defaultText.trim()
    }

    /**
     * Saves the driver's own text for [topic], subject to the two guards below.
     *
     * Blank input, or input equal to the shipped default, DELETES the override rather than storing
     * a copy - so "select all, delete, save" reverts instead of blanking the advisor, and so
     * [isCustomised] can never report an edit that is not one.
     *
     * **Guard 1, size.** An edit over [PrimingTopic.MAX_CHARS] is refused. The playbook rides
     * every model call in a bounded investigate loop, so an unbounded one does not fail loudly, it
     * quietly inflates the cost of every question the driver asks on his own key.
     *
     * **Guard 2, boundaries.** An edit that drops one of [PrimingTopic.requiredPhrases] is
     * refused. `PlaybookKeywordsTest` fails the build when a code change deletes a
     * professional-referral boundary from a shipped constant; before this guard existed, the
     * editor screen could delete the same line at runtime and no test could see it. That is the
     * same shape as the regressions quant-viz is tracking - later work walking past a guard that
     * still passes CI.
     *
     * Never fails silently: every refusal comes back as a typed [PlaybookSaveResult] the caller
     * must render. Losing an edit the driver typed, without saying so, is the same sin as
     * accepting an unverified row.
     */
    fun save(context: Context, topic: PrimingTopic, text: String): PlaybookSaveResult {
        val trimmed = text.trim()
        if (trimmed.isBlank() || trimmed == topic.defaultText.trim()) {
            revertToDefault(context, topic)
            return PlaybookSaveResult.RevertedToDefault
        }
        if (trimmed.length > PrimingTopic.MAX_CHARS) {
            return PlaybookSaveResult.TooLong(actualChars = trimmed.length, maxChars = PrimingTopic.MAX_CHARS)
        }
        val missing = topic.missingPhrases(trimmed)
        if (missing.isNotEmpty()) return PlaybookSaveResult.MissingBoundaries(missing)
        return try {
            val f = fileFor(context, topic)
            f.parentFile?.mkdirs()
            f.writeText(trimmed)
            PlaybookSaveResult.Saved
        } catch (e: Exception) {
            Log.w(TAG, "save failed for ${topic.key}", e)
            PlaybookSaveResult.WriteFailed
        }
    }

    /** Drops the driver's override so [text] returns the shipped default again. A no-op when
     * there was none. */
    fun revertToDefault(context: Context, topic: PrimingTopic) {
        try {
            fileFor(context, topic).delete()
        } catch (e: Exception) {
            Log.w(TAG, "revert failed for ${topic.key}", e)
        }
    }

    /** `filesDir/playbooks/<profileId>/<topic.key>.md` for the active profile. */
    fun fileFor(context: Context, topic: PrimingTopic): File =
        File(File(File(context.applicationContext.filesDir, DIR), profileDir(context)), "${topic.key}.md")

    private fun readOverride(context: Context, topic: PrimingTopic): String? = try {
        val f = fileFor(context, topic)
        if (f.isFile) f.readText() else null
    } catch (e: Exception) {
        // A read failure must degrade to the shipped default, never to no doctrine at all.
        Log.w(TAG, "read failed for ${topic.key}", e)
        null
    }

    /**
     * The active profile id, reduced to a filesystem-safe directory name. Everything outside
     * `[A-Za-z0-9._-]` becomes `_`, and an id that sanitises to nothing (or is absent) falls back
     * to [DEFAULT_PROFILE]. Ids are UUIDs today, so this is a guard against a future id shape
     * rather than a live hazard - but it is the filesystem, and a path separator arriving in a
     * profile id is not the place to find out.
     */
    internal fun profileDir(context: Context): String {
        val raw = ActiveCompanionProfile.activeProfileId(context).orEmpty()
        val safe = raw.map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '_' }
            .joinToString("")
            .trim('.', '_')
        return safe.ifBlank { DEFAULT_PROFILE }
    }
}
