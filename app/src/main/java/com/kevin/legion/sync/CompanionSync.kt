package com.kevin.legion.sync

/**
 * Pure (Android-free) decision logic for companion identity sync (S1's last
 * unbuilt piece). Unlike [SyncEngine]'s registered tables, `companion.json`
 * isn't a Room row - it's the single "who am I" text blob (name/persona/
 * traits/voice, see [com.kevin.legion.ai.CompanionProfile]). A genuine
 * mismatch between two devices means two DIFFERENT companions have met for
 * the first time (e.g. two head units each onboarded fresh before ever
 * syncing), not just an edit to reconcile silently - so unlike the LWW
 * tables, the very first content conflict has to ask, not auto-pick a
 * winner. Split out (mirrors [SyncMerge]) so the decision matrix
 * unit-tests without a device or a live Drive connection.
 */
object CompanionSync {

    /** The companion identity fields as of one save. [traits] is the raw encoded
     * picker-selections string ([com.kevin.legion.ai.encodeSelections] output),
     * not a decoded map, so content equality is a plain field comparison. */
    data class CompanionIdentity(
        val name: String,
        val persona: String,
        val traits: String,
        val voice: String,
        val updatedAt: Long,
    ) {
        /** Content equality ignoring the clock - two saves of the same companion. */
        fun sameContentAs(other: CompanionIdentity): Boolean =
            name == other.name && persona == other.persona &&
                traits == other.traits && voice == other.voice

        /**
         * True when this device has no companion for this car at all - an ABSENCE,
         * not a rival identity. See [decideCompanion]'s blank-local branch.
         */
        val isBlank: Boolean
            get() = name.isBlank() && persona.isBlank() && traits.isBlank() && voice.isBlank()
    }

    /** A genuine first-meeting mismatch, surfaced to the driver by [SyncEngine.pendingCompanionClash]. */
    data class CompanionClash(val local: CompanionIdentity, val remote: CompanionIdentity)

    enum class Decision { UPLOAD_LOCAL, ADOPT_REMOTE, PROMPT, NOTHING }

    /**
     * Decides what to do with the local vs. remote companion identity.
     *  - No remote file yet -> [Decision.UPLOAD_LOCAL] (first sync from this Drive account).
     *  - Same content -> [Decision.NOTHING] (identical companions, nothing to reconcile).
     *  - Blank local, remote exists -> [Decision.ADOPT_REMOTE]. See below.
     *  - Different content, never reconciled before -> [Decision.PROMPT]. Two
     *    different companions meeting for the first time; never silently pick
     *    a winner - the driver decides once, via [SyncEngine.resolveCompanionClash].
     *  - Different content, already reconciled once -> ordinary edit
     *    propagation from here on: last-write-wins by [CompanionIdentity.updatedAt].
     */
    fun decideCompanion(local: CompanionIdentity, remote: CompanionIdentity?, reconciled: Boolean): Decision {
        if (remote == null) return Decision.UPLOAD_LOCAL
        if (local.sameContentAs(remote)) return Decision.NOTHING
        // A blank local is an ABSENCE, not a rival companion - adopt, never prompt.
        //
        // This branch exists because identity went per-car (2026-07-16), which made
        // `reconciled` a per-car flag too. Without it, the FIRST time a device
        // selects a car it has never held, local is blank, remote is that car's
        // real companion, content differs, and reconciled is false - so the driver
        // gets the "two companions met" dialog asking them to choose between
        // nothing and their own companion. Answering "keep local" would then upload
        // the blank and DESTROY that car's identity on Drive. On a multi-car roster
        // that fires on nearly every first switch.
        //
        // Ordered after sameContentAs so blank-vs-blank stays NOTHING, and before
        // the !reconciled check because that check is what would misfire.
        if (local.isBlank) return Decision.ADOPT_REMOTE
        if (!reconciled) return Decision.PROMPT
        return when {
            remote.updatedAt > local.updatedAt -> Decision.ADOPT_REMOTE
            local.updatedAt > remote.updatedAt -> Decision.UPLOAD_LOCAL
            else -> Decision.NOTHING
        }
    }
}
