package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.MidnightEvents
import com.kevin.legion.backend.engine.EngineBackends
import com.kevin.legion.backend.engine.DjangoPlacesBackend
import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.backend.engine.engineRefusalSentence
import com.kevin.legion.backend.engine.guardingForeground
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.TaggedPlace

/**
 * Install-scoped record of which `places` labels [PlacesBackfill] has already dealt with.
 *
 * **A label set, not a high-water row id, because `places` has no row id.**
 * [com.kevin.legion.data.local.TaggedPlace]'s primary key IS the label - there is no
 * autoincrement `id` and no `syncId` column - so the per-table `Long` cursor
 * [ChecklistsBackfillCursor] and [LastAspectsBackfillCursor] both use has nothing to count.
 * [LastAspectsBackfill] hit the identical wall on `grocery_staples` (also natural-keyed) and
 * answered it by rescanning the whole table every run; this answers it by remembering the labels
 * instead, which costs a handful of short strings and buys the thing that rescan does not: a
 * second run that makes **no network call at all** (see [PlacesBackfill.run]'s early return).
 *
 * **It also records the labels the engine will never take, and that second job is not decoration** -
 * same argument [ChecklistsBackfillCursor]'s own doc makes at length. The accounted-set alone
 * already stops a refused label being retried; what the reason buys is the SENTENCE, so a later
 * run can still say a place is being held back instead of reporting a clean "backfilled 0".
 */
internal object PlacesBackfillCursor {
    private const val PREFS = "places_backfill_cursor"
    private const val KEY_ACCOUNTED = "accounted_labels"
    private const val KEY_UNSYNCABLE = "unsyncable_labels"

    /** Separates a label from its reason inside one stored string. ASCII 31, "unit separator" -
     * the same choice and the same reasoning as [ChecklistsBackfillCursor]'s: the reason is the
     * ENGINE'S own English sentence and may contain any punctuation a human would write. A label
     * cannot contain it either, since labels come from speech and from a text field. */
    private const val FIELD_SEPARATOR = '\u001F'

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Every label this backfill has finished with - pushed, found already on the engine, skipped
     * as a local-only tombstone, or permanently refused. "Accounted", not "sent": three of those
     * four never crossed the wire, and naming the set for what it actually holds is what keeps the
     * tombstone branch from reading as an upload. */
    fun accounted(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_ACCOUNTED, emptySet()).orEmpty()

    fun markAccounted(context: Context, label: String) {
        val kept = accounted(context).toMutableSet()
        kept += label
        prefs(context).edit().putStringSet(KEY_ACCOUNTED, kept).apply()
    }

    /** Labels this install will never send, with the reason. */
    fun unsyncable(context: Context): Map<String, String> =
        prefs(context).getStringSet(KEY_UNSYNCABLE, emptySet()).orEmpty()
            .mapNotNull { entry ->
                val at = entry.indexOf(FIELD_SEPARATOR)
                if (at <= 0) null else entry.substring(0, at) to entry.substring(at + 1)
            }
            .toMap()

    /** Idempotent: recording the same label twice replaces its reason rather than adding a second
     * entry - the set is keyed by the whole string, and a re-refusal could word itself differently. */
    fun recordUnsyncable(context: Context, label: String, reason: String) {
        val kept = unsyncable(context).toMutableMap()
        kept[label] = reason
        prefs(context).edit()
            .putStringSet(KEY_UNSYNCABLE, kept.map { (l, why) -> "$l$FIELD_SEPARATOR$why" }.toSet())
            .apply()
    }

    fun unsyncableCount(context: Context): Int = unsyncable(context).size

    /** Test-only. Robolectric's `SharedPreferences` outlive a `CarDatabase` reset, so without this
     * a set left behind by one test silently makes the next test's backfill examine nothing - which
     * would not fail loudly, it would just assert on a run that did no work. Same reason
     * [ChecklistsBackfillCursor.resetForTest] exists. */
    fun resetForTest(context: Context) {
        prefs(context).edit().clear().apply()
    }
}

/**
 * The one-time upload of every saved place that predates the `places` transport flip.
 *
 * **Places had a pull as of 2026-09-07 and still had no way to send what was already on the
 * phone.** [PlaceController][com.kevin.legion.location.PlaceController] is pure write-through, so
 * it only ever pushes writes made from NOW on; [PlacesReconcile] was the upload half and it
 * **stands down entirely on [com.kevin.legion.backend.engine.Transport.DJANGO]** by design (read
 * its `maybeAutoRun` comment: it reads the retired on-device engine RecordStore and would push
 * those rows into a Supabase project the phone has stopped reading). Nothing replaced it. So
 * flipping the `places` transport today would strand every place already tagged: [PlacesSync.pull]
 * correctly leaves a local-only row alone (BodyMerge's rule 6), so those rows would sit invisible
 * to the engine and to a second client forever. This is the replacement, and it is an upload, not
 * a migration - it reads `places`, the same table the pull writes.
 *
 * Same five rules as [ChecklistsBackfill], read onto a natural-keyed table:
 *
 * 1. **Idempotent.** `PUT /api/places/<label>/` is keyed on the label, so a repeat push overwrites
 *    the row it already made rather than making a second one. Beyond that, a second run does not
 *    even reach the network: with every local label already in [PlacesBackfillCursor], [run]
 *    returns before the server read.
 * 2. **Resumable.** The cursor is written per label as each one resolves, so a run that dies
 *    halfway resumes at the label it stopped on.
 * 3. **The cursor is trusted only in the "already dealt with" direction.** A label in the set is
 *    skipped; a label absent from it is examined. The reverse would be unsafe, which is exactly
 *    why rule 1 makes a redundant push harmless.
 * 4. **A place deleted locally that the engine never had is skipped, never resurrected.** This one
 *    is sharper here than on any other aspect: `PlaceViewSet.put_revives_tombstone = True` (see
 *    [com.kevin.legion.backend.engine.DjangoPlacesBackend]'s own class doc - `places` is the ONLY
 *    aspect whose PUT revives a tombstone), so pushing a forgotten label would not merely create a
 *    row to tombstone again later, it would bring a deliberately-forgotten place back to life on
 *    both limbs. That is also why the server set this run diffs against is read with
 *    [PlacesIncrementalPull.fetchChangedSince] and not [PlacesBackend.fetchActive]: an active-only
 *    feed cannot tell "the engine never heard of this label" from "the engine has it, tombstoned",
 *    and a push is safe in the first case and destructive in the second.
 * 5. **An UNREACHABLE engine stops the run and is reported**, with no cursor advanced, so the next
 *    foreground asks the same question again.
 * 6. **A REFUSED row is skipped, recorded, and the run carries on - never a stop.** Ticket 09's
 *    lesson, learned the expensive way on the A25: one legacy checklist tick the engine would not
 *    take stopped every OTHER tick from crossing, and zero ticks were ever backfilled. A place the
 *    engine refuses (an over-long label predating the server's cap is the realistic one) is left on
 *    the phone exactly as it is, never deleted and never tombstoned, with the engine's own sentence
 *    recorded beside it so [com.kevin.legion.backend.engine.EngineSyncNow] can keep saying so.
 *
 * **What this deliberately does NOT do: push a DELETE.** A place tombstoned on this phone that the
 * engine still holds active is counted, recorded and said in words, and then left alone. Deleting
 * a server row is a destructive act, this table's local tombstone carries no evidence of when it
 * was intended relative to the engine's copy, and `PlaceController` has no outbox to have queued
 * one. That divergence is a real gap and it is named rather than quietly closed the wrong way.
 */
object PlacesBackfill {

    /** One place the engine will not take (or that this backfill will not send), kept so the
     * summary sentence can name it. */
    data class Skipped(val label: String, val reason: String)

    /**
     * @param pushed labels that crossed on THIS run.
     * @param alreadyOnEngine labels the server already had - counted, never re-pushed, so a place
     *   tagged since the flip through the ordinary write-through path is not overwritten with the
     *   phone's copy.
     * @param alreadyAccounted labels an earlier run already finished with (the no-op case).
     * @param skippedLocalOnlyDeleted forgotten places the engine never had - rule 4.
     * @param skipped rows held back on THIS run, with the reason. Empty on every run after the one
     *   that met them.
     * @param unsyncableTotal every label this install has ever held back, this run's included -
     *   the number that keeps being true, so a later run can still say a place is not crossing
     *   rather than reporting a clean pass.
     * @param stopped an unreachable/unauthorized engine that ended the run early - rule 5. Null on
     *   a complete pass. **Never carries a refusal**; that is what [skipped] is for, and collapsing
     *   the two is precisely the defect ticket 09 corrected on checklists.
     */
    data class Report(
        val pushed: Int = 0,
        val alreadyOnEngine: Int = 0,
        val alreadyAccounted: Int = 0,
        val skippedLocalOnlyDeleted: Int = 0,
        val skipped: List<Skipped> = emptyList(),
        val unsyncableTotal: Int = 0,
        val stopped: String? = null,
    )

    /** What one row's examination decided - the four things the loop can do next, named as those
     * four things. Same split, and the same reason for the split, as [ChecklistsBackfill]'s own
     * `PushOutcome`: [Skip] is a fact about this ROW ("this one, never; the others, carry on"),
     * [Stop] is a fact about the RUN.
     *
     * [Pushed] and [Settled] are separated because only one of them is a claim that something
     * crossed the wire. Both mean "finished with this label, advance the cursor"; conflating them
     * would let the summary say "backfilled 3" over three rows that were already there. */
    private sealed interface RowOutcome {
        data object Pushed : RowOutcome
        data object Settled : RowOutcome
        data class Skip(val reason: String) : RowOutcome
        data class Stop(val reason: String) : RowOutcome
    }

    private class Tally {
        var pushed = 0
        var alreadyOnEngine = 0
        var skippedLocalOnlyDeleted = 0
        val skipped = mutableListOf<Skipped>()
        var stopped: String? = null
    }

    /**
     * Uploads every local place the engine has never heard of.
     *
     * [T] must be both halves of the Django places transport, and that is stated in the signature
     * rather than in a comment: the write goes through [PlacesBackend.upsert] and the "does the
     * engine already have this label" read needs [PlacesIncrementalPull.fetchChangedSince]'s
     * tombstone-carrying feed (rule 4). Only
     * [com.kevin.legion.backend.engine.DjangoPlacesBackend] implements both, so this function
     * cannot be pointed at Supabase by accident.
     */
    suspend fun <T> run(context: Context, backend: T): Report
        where T : PlacesBackend, T : PlacesIncrementalPull {
        val db = CarDatabase.getDatabase(context)
        // Tombstones included on the LOCAL side too - rule 4 needs to SEE a forgotten place in
        // order to decide not to send it. getAll() would filter it out and the label would be
        // re-examined (and, once the engine had it, re-pushed) on every single run.
        val local = db.placeDao().getAllIncludingTombstones()
        val accounted = PlacesBackfillCursor.accounted(context)
        val pending = local.filter { it.label !in accounted }.sortedBy { it.label }
        val alreadyAccounted = local.size - pending.size

        // Rule 1's second half, and the whole of "a second run is a no-op": nothing left to
        // examine means no server read either. Every sibling backfill still spends one request per
        // run to learn this; places is read on every foreground return, so it is worth the branch.
        //
        // A null `known` set stands for "we never asked", so the empty-pending case and the
        // unreachable case share one exit rather than taking two more early returns - detekt's
        // `ReturnCount` again, and the reading order is unchanged.
        val fetched = if (pending.isEmpty()) Result.success(emptyList()) else backend.fetchChangedSince(0L)
        // Every label the engine knows about IN ANY STATE, tombstones included - see rule 4.
        val known = fetched.getOrNull()?.map { it.label }?.toSet()

        val tally = Tally()
        if (known == null) {
            tally.stopped = fetched.exceptionOrNull()?.message ?: "the engine could not be read"
        } else {
            for (row in pending) {
                if (!recordOutcome(context, row, examine(context, backend, row, known, tally), tally)) break
            }
        }
        return Report(
            pushed = tally.pushed,
            alreadyOnEngine = tally.alreadyOnEngine,
            alreadyAccounted = alreadyAccounted,
            skippedLocalOnlyDeleted = tally.skippedLocalOnlyDeleted,
            skipped = tally.skipped.toList(),
            // Read AFTER the loop, so it already includes anything this pass recorded.
            unsyncableTotal = PlacesBackfillCursor.unsyncableCount(context),
            stopped = tally.stopped,
        )
    }

    /**
     * The four branches for one local row, in the order they have to be asked.
     *
     * The two counters incremented here rather than in [recordOutcome] are the ones that are NOT
     * skips - "already on the engine" and "forgotten and the engine never had it" are both
     * [RowOutcome.Sent] as far as the cursor is concerned (finished with, advance) but neither is
     * a push and neither belongs in the [Report.skipped] list a user reads.
     */
    private suspend fun <T> examine(
        context: Context,
        backend: T,
        row: TaggedPlace,
        known: Set<String>,
        tally: Tally,
    ): RowOutcome where T : PlacesBackend, T : PlacesIncrementalPull {
        val onEngine = row.label in known
        return when {
            row.deleted && !onEngine -> {
                tally.skippedLocalOnlyDeleted++
                RowOutcome.Settled
            }
            // Forgotten here, alive there. Never pushed (it would revive it) and never deleted
            // server-side (this file does not delete) - see the class doc's closing paragraph.
            row.deleted -> RowOutcome.Skip(
                "\"${row.label}\" was forgotten on this phone but the engine still has it; " +
                    "a backfill will not delete a server row",
            )
            onEngine -> {
                tally.alreadyOnEngine++
                RowOutcome.Settled
            }
            else -> push(context, backend, row)
        }
    }

    /** The push itself, plus the ACK write-back. Split out of [examine] to keep both under
     * detekt's ceilings and to keep the branch table above readable as a branch table. */
    private suspend fun push(context: Context, backend: PlacesBackend, row: TaggedPlace): RowOutcome {
        val result = backend.upsert(row.label, row.latitude, row.longitude)
        val remote = result.getOrElse { return classify(it) }
        // The ACK's own `updated_at` written back onto the replica, the same way
        // PlaceController.tagPlace does on its configured branch: `TaggedPlace.timestamp` is this
        // table's LWW clock, and after a push the engine's copy is the authority on it.
        //
        // **What this does NOT buy, checked rather than assumed:** it does not stop the next
        // PlacesSync.pull counting the row as "updated". BodyMerge takes `remote >= local` and the
        // server wins an exact tie, so an equal-clock row is still rewritten and still counted -
        // exactly as an older-clock one would be. What it buys is narrower and still worth the two
        // lines: Room agrees with the engine the moment the push lands, rather than holding a row
        // that claims to predate the server copy of its own data for up to the five minutes until
        // a throttled pull happens to reconcile it (places has no Realtime and no poll).
        CarDatabase.getDatabase(context).placeDao().upsert(
            TaggedPlace(
                label = remote.label,
                latitude = remote.latitude,
                longitude = remote.longitude,
                timestamp = remote.updatedAtMs,
                deleted = remote.deleted,
            )
        )
        return RowOutcome.Pushed
    }

    /**
     * Sorts a failure into "this row" and "this run" - identical to [ChecklistsBackfill]'s own
     * `classify`, including the unwrapping of DRF's `{"non_field_errors": [...]}` envelope for the
     * SENTENCE only ([engineRefusalSentence]). Nothing here rewords the engine.
     */
    private fun classify(cause: Throwable?): RowOutcome =
        when (val failure = (cause as? EngineHttpException)?.failure) {
            is EngineFailure.Refused -> RowOutcome.Skip(engineRefusalSentence(failure.body))
            null -> RowOutcome.Stop(cause?.message ?: "unknown error")
            else -> RowOutcome.Stop(failure.sentence)
        }

    /**
     * Applies one row's [outcome] to [tally] and to the cursor. Returns false only for
     * [RowOutcome.Stop], the one outcome that ends the run.
     *
     * **The cursor is marked on a [RowOutcome.Skip] as well as on a [RowOutcome.Pushed]**, which is
     * what makes rule 6 hold: without it, a refused label would be retried on every foreground
     * forever - the same "error blob repeated on every sync" checklists ended, merely quieter.
     */
    private fun recordOutcome(
        context: Context,
        row: TaggedPlace,
        outcome: RowOutcome,
        tally: Tally,
    ): Boolean {
        when (outcome) {
            is RowOutcome.Pushed -> {
                tally.pushed++
                PlacesBackfillCursor.markAccounted(context, row.label)
            }
            is RowOutcome.Settled -> PlacesBackfillCursor.markAccounted(context, row.label)
            is RowOutcome.Skip -> {
                tally.skipped += Skipped(row.label, outcome.reason)
                PlacesBackfillCursor.recordUnsyncable(context, row.label, outcome.reason)
                PlacesBackfillCursor.markAccounted(context, row.label)
            }
            is RowOutcome.Stop -> {
                tally.stopped = "${outcome.reason} (label \"${row.label}\")"
                return false
            }
        }
        return true
    }

    // --- Foreground auto-trigger ---------------------------------------------------------------

    @Volatile private var lastAutoRunAt = 0L
    private const val AUTO_RUN_MIN_INTERVAL_MS = 5 * 60 * 1000L

    /** Test seam for the throttle - [lastAutoRunAt] is process-scoped state on an `object`, which
     * Robolectric does not reset between test methods. */
    internal fun setLastAutoRunAtForTest(atMs: Long) {
        lastAutoRunAt = atMs
    }

    /**
     * `MainActivity.onResume`'s hook - runs BEFORE [PlacesSync.maybeAutoPull], the same ordering
     * every sibling backfill uses and for the same reason: an unsent local row must reach the
     * engine before a pull weighs last-write-wins against an engine copy that does not know about
     * it yet.
     *
     * No-ops silently when `places` is not on the Django transport or this device holds no engine
     * token - the `as?` resolves to null on Supabase, exactly as [PlacesSync.maybeAutoPull]'s does,
     * so an install that has not flipped the transport row behaves as it did before this file
     * existed.
     */
    suspend fun maybeAutoRun(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastAutoRunAt < AUTO_RUN_MIN_INTERVAL_MS) return
        val app = context.applicationContext
        // DjangoPlacesBackend by name, because it is the only class that implements both halves
        // [run] needs - see [run]'s own doc comment. On Supabase (the default) this is null and
        // nothing happens.
        val backend = EngineBackends(app).placesBackend() as? DjangoPlacesBackend ?: return
        lastAutoRunAt = now
        guardingForeground(onFailure = { MidnightEvents.placesBackfillFailed(it) }) {
            val report = run(app, backend)
            MidnightEvents.placesBackfillSucceeded(
                report.pushed,
                report.alreadyOnEngine,
                report.skippedLocalOnlyDeleted,
                report.skipped.map { "${it.label}: ${it.reason}" },
                report.unsyncableTotal,
                report.stopped,
            )
        }
    }
}
