package com.kevin.legion.data.local

import android.util.Log
import java.io.File

/**
 * The ADR 0041 delete cascade: "audio, transcript and summary are retained or destroyed
 * together." A summary outliving its transcript is the specific failure that ADR names as the one
 * this ticket exists to prevent, and the transcript/summary live as plain columns on the
 * [VoiceNote] row itself - so destroying "together" means one thing on the phone: delete the row
 * (which takes the transcript and summary with it, since they are not separate rows) and delete
 * the `.m4a` file [VoiceNote.audioPath] names, in that order or the other - both must happen for
 * either to count as done.
 *
 * **Lives here, not on [VoiceNoteDao] or inside [VoiceNoteRecorder].** [VoiceNoteRecorder] is
 * ticket 01's recording state machine, a different responsibility ticket 02 deliberately keeps
 * separate. This class is small on purpose: ticket 04's `VoiceNoteController` is where voice/hands
 * parity for the FULL note lifecycle (start/stop/rename/list/read) is built - this only has to
 * exist now because ticket 02's own verification list requires the delete cascade to have a test
 * today, ahead of that controller. [VoiceNote.audioPath] is already an absolute path
 * ([VoiceNoteRecorder] writes it under `context.cacheDir`), so this class itself needs no
 * [android.content.Context].
 */
class VoiceNoteStore(
    private val dao: VoiceNoteDao,
) {
    companion object {
        private const val TAG = "VoiceNoteStore"
    }

    /**
     * Deletes the note at [id]: its row (transcript and summary go with it, since neither is ever
     * a separate row) and its `.m4a` file, if it still has one. Returns false if no such row
     * existed - a normal, expected outcome (already deleted, or a stale id), never reported as a
     * delete having happened, matching [com.kevin.legion.backend.EventsBackend.softDelete]'s own
     * `Result.success(false)` posture for the same case.
     *
     * **The ROW is deleted first, then the file.** Nothing about a `DELETE ... WHERE id = :id` is
     * a two-phase commit with the filesystem, so one of the two partial states has to be chosen
     * deliberately:
     *
     * - Row gone, file left behind: a leaked `.m4a` that no row claims. Already handled -
     *   [com.kevin.legion.voice.VoiceNoteRecorder.reconcileAfterProcessDeath] sweeps orphan files
     *   with no owning row on the next start.
     * - File gone, row left behind: a row still claiming an [VoiceNote.audioPath] that no longer
     *   exists, and a summary whose transcript's anchor has silently vanished underneath it. That
     *   is the anchor-chain violation ADR 0041 exists to forbid, and nothing sweeps it up.
     *
     * The second is strictly worse and is not recoverable by any later pass, so the order is
     * chosen to make it unreachable: if [VoiceNoteDao.deleteById] throws, nothing has been
     * destroyed and the row is still whole. An earlier revision of this method had the order
     * reversed on the reasoning that a leaked file is the worse outcome; it is not, because the
     * orphan sweeper already exists.
     */
    suspend fun delete(id: Long): Boolean {
        val note = dao.getById(id) ?: return false
        dao.deleteById(id)
        note.audioPath?.let { path ->
            val file = File(path)
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "delete: failed to remove audio file for voice note $id at $path - the " +
                    "row is gone, so the orphan sweeper will collect it on the next start")
            }
        }
        return true
    }
}
