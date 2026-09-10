package com.kevin.legion.wellbeing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kevin.legion.MidnightEvents
import com.kevin.legion.advisor.AdvisorProposalExecutor
import com.kevin.legion.checklists.ChecklistController
import com.kevin.legion.service.ProactiveBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fires when the wellbeing digest's alarm goes off - goal-plans ticket 05
 * (`.scratch/goal-plans/issues/05-wellbeing-digest.md`). Shape closely mirrors
 * [com.kevin.legion.sitrep.SitrepAlarmReceiver]: `goAsync()` because reading today's checklist is
 * a Room round trip that does not fit inside `onReceive`'s few-second budget.
 *
 * In order:
 * 1. **Materializes today's checklist** via [GoalChecklistSync.materializeToday] BEFORE reading it
 *    back. This is the one real difference from the sitrep's own receiver, and it exists for a
 *    concrete reason: [GoalChecklistSync.currentItems] only ever returns rows [GoalChecklistSync
 *    .materializeToday] already wrote for today, and that materializer is normally triggered by
 *    the app opening or a plan being accepted - neither of which has necessarily happened yet on a
 *    day this alarm fires while Kevin has not touched the app. Skipping this step would make the
 *    digest read as "no plan" on any day the app was not opened before the scheduled hour, which
 *    is not what "no plan" means and would be exactly the kind of false negative CLAUDE.md's
 *    reconciliation-gate posture (an unreadable state must never render as a clear one) warns
 *    against outside ledger too. [materializeToday] is idempotent, so calling it here on top of
 *    whatever already ran today is always safe.
 * 2. **Builds the ONE raise** via [WellbeingDigestBuilder.buildRaise] over today's item texts. Per
 *    that function's own contract, a raise is constructed ONLY when there is at least one item -
 *    an empty checklist (no plan ever accepted, or every target since cleared) produces `null`,
 *    and this receiver constructs and sends NOTHING in that case. **Never falls back to a
 *    "nothing today" raise** - ticket 05's own rule: "no accepted plan means no raise at all - not
 *    a raise saying 'nothing today'."
 * 3. **Raises it through [ProactiveBus.speakIfAllowed] under [com.kevin.legion.service
 *    .ProactiveCategory.WELLBEING]** - inherits the master kill switch, quiet hours (Wellbeing is
 *    the one category allowed to speak inside them), the three-a-day cap, decline suppression, and
 *    one-delivery-per-raise, all for free, exactly the way ticket 05 asks for.
 * 4. **Re-arms itself for tomorrow** - [WellbeingDigestScheduler]'s own "re-arm on fire, never
 *    `setRepeating`" precedent. Re-arms unconditionally, even when step 2 produced no raise at all
 *    or step 3 refused to speak it - neither "no plan today" nor a gated firing is a reason to stop
 *    scheduling future ones; the settings screen is the only thing allowed to cancel the schedule
 *    outright.
 */
class WellbeingDigestAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                fire(context)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun fire(context: Context) {
        // **Repointed 2026-09-10 (one-home ticket 05).** This read `GoalChecklistSync` - it
        // materialized today's lines first, then read them back. That mechanism is retired; the
        // advisor writes a real recurring checklist through `AdvisorProposalExecutor`, found by
        // [AdvisorProposalExecutor.BIO_CHECKLIST_SOURCE_KEY]. **There is nothing to materialize any
        // more**, so the first call is gone rather than replaced - a recurring checklist simply
        // exists; it does not need a nightly job to bring it into being.
        //
        // Failure stays non-fatal and reported, exactly as before: this runs on an alarm, and a
        // digest that cannot read its list must still re-arm tomorrow's (see this class's own doc
        // comment). An empty list is a normal outcome and is NOT an error - `buildRaise` decides
        // whether silence is the right answer.
        val itemTexts = runCatching {
            val checklist = ChecklistController.getChecklistBySourceKey(
                context,
                AdvisorProposalExecutor.BIO_CHECKLIST_SOURCE_KEY,
            ) ?: return@runCatching emptyList<String>()
            when (val res = ChecklistController.itemsWithTickState(context, checklist.id)) {
                // Unreadable is not empty (CLAUDE.md §1). Throwing here routes it to the same
                // `wellbeing_digest_read_failed` event a thrown read would, rather than quietly
                // becoming "nothing on your plan tonight".
                is ChecklistController.ChecklistItemsResult.Failed ->
                    error("checklist unreadable: ${res.reason}")
                is ChecklistController.ChecklistItemsResult.Loaded -> res.items.map { it.item.text }
            }
        }
            .onFailure { MidnightEvents.appStartWorkFailed("wellbeing_digest_read_failed", it) }
            .getOrNull()
            .orEmpty()

        val raise = WellbeingDigestBuilder.buildRaise(itemTexts)
        if (raise != null) {
            ProactiveBus.speakIfAllowed(context, raise)
        }

        // Re-arm regardless of whether a raise was built or spoken - see this class's own doc
        // comment on why neither "no plan today" nor a gated firing may cancel tomorrow's.
        runCatching { WellbeingDigestScheduler.rescheduleFromSettings(context) }
            .onFailure { MidnightEvents.appStartWorkFailed("wellbeing_digest_rearm_failed", it) }
    }

    companion object {
        const val ACTION_FIRE = "com.kevin.legion.action.WELLBEING_DIGEST_FIRE"
    }
}
