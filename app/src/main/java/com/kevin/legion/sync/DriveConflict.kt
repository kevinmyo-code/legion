package com.kevin.legion.sync

/**
 * Pure (Android-free) decision helpers for B20's optimistic-concurrency retry
 * loop, split out of [DriveClient]/[SyncEngine] so the conflict logic
 * unit-tests without a device or a live Drive connection - the HTTP calls
 * themselves aren't unit-testable here (see [DriveClient]'s class doc for why
 * Drive v3's undocumented `If-Match` support makes the version re-check the
 * real guard, not the 412 alone).
 */
internal object DriveConflict {

    /** Drive's (observed, undocumented) conflict signal on a failed precondition. */
    const val HTTP_PRECONDITION_FAILED = 412

    /**
     * True if the file was written by someone else since [expectedVersion]
     * was captured. Both sides must be known - a missing version (fetch
     * failed, or no prior read) never blocks a write on its own.
     */
    fun versionChanged(expectedVersion: String?, liveVersion: String?): Boolean =
        expectedVersion != null && liveVersion != null && expectedVersion != liveVersion

    /** True if another sync-and-retry attempt is worth making. */
    fun shouldRetry(attempt: Int, maxAttempts: Int): Boolean = attempt < maxAttempts
}
