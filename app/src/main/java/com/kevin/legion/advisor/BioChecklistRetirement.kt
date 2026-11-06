package com.kevin.legion.advisor

import android.content.Context
import com.kevin.legion.checklists.ChecklistController

/**
 * Archives the BIO advisor's "BIO Plan" tickbox list once (workouts, 2026-11-06).
 *
 * Kevin: *"right now theres the old workout tickboxes left over from before and its confusing"*.
 * That list was model-written text found by [AdvisorProposalExecutor.BIO_CHECKLIST_SOURCE_KEY]; it
 * showed under Body > More, on Lists, the Calendar day and the web home, and ticking it wrote
 * nothing but a tick. The engine's deterministic workout (`server/body_coach/programme.py`) replaces
 * it, and the BIO advisor no longer has the op that wrote it ([AdvisorBriefs.BIO]).
 *
 * **Archived, not deleted**, so its tick history stays and Lists can unarchive it by hand. The
 * archive syncs like any checklist change, so the web stops showing it too. Runs once per install
 * (a SharedPreferences flag); a list Kevin unarchives afterwards is left alone.
 */
object BioChecklistRetirement {
    private const val PREFS = "bio_checklist_retirement"
    private const val KEY = "archived_v1"

    suspend fun retireIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY, false)) return
        val list = ChecklistController.getChecklistBySourceKey(
            context,
            AdvisorProposalExecutor.BIO_CHECKLIST_SOURCE_KEY,
        )
        if (list != null && !list.archived) ChecklistController.archiveChecklist(context, list.id)
        prefs.edit().putBoolean(KEY, true).apply()
    }
}
