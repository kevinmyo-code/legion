package com.kevin.midnightai.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the priority order behind DriveHome/MediaDiagnostics' three-way empty
 * state (CLAUDE.md sec 1's "unreadable and empty are different sentences"
 * rule, restated in the reverse-camera/media-diagnostics ticket): a missing
 * notification grant must win over "nothing is playing" even when the other
 * inputs would otherwise look like a clean negative, because without the
 * grant those other inputs are unknowable, not false.
 */
class MediaHomeStateTest {

    @Test
    fun `no notification access wins regardless of the other two flags`() {
        assertEquals(
            MediaEmptyState.NEEDS_NOTIFICATION_ACCESS,
            selectMediaEmptyState(hasNotificationAccess = false, hasActiveSession = false, hasMetadata = false),
        )
        assertEquals(
            MediaEmptyState.NEEDS_NOTIFICATION_ACCESS,
            selectMediaEmptyState(hasNotificationAccess = false, hasActiveSession = true, hasMetadata = true),
        )
    }

    @Test
    fun `access granted but no session is a genuine nothing-playing`() {
        assertEquals(
            MediaEmptyState.NOTHING_PLAYING,
            selectMediaEmptyState(hasNotificationAccess = true, hasActiveSession = false, hasMetadata = false),
        )
    }

    @Test
    fun `session present but metadata missing is its own distinct state`() {
        assertEquals(
            MediaEmptyState.METADATA_MISSING,
            selectMediaEmptyState(hasNotificationAccess = true, hasActiveSession = true, hasMetadata = false),
        )
    }

    @Test
    fun `all three true is genuinely has-info`() {
        assertEquals(
            MediaEmptyState.HAS_INFO,
            selectMediaEmptyState(hasNotificationAccess = true, hasActiveSession = true, hasMetadata = true),
        )
    }

    // --- classifyMetadataFillState ------------------------------------------

    @Test
    fun `null metadata object is NULL regardless of the field values passed`() {
        assertEquals(
            MetadataFillState.NULL,
            classifyMetadataFillState(title = "should be ignored", artist = null, album = null, metadataIsNull = true),
        )
    }

    @Test
    fun `metadata object with all-blank fields is EMPTY, not NULL`() {
        assertEquals(
            MetadataFillState.EMPTY,
            classifyMetadataFillState(title = null, artist = "", album = "   ", metadataIsNull = false),
        )
    }

    @Test
    fun `any one non-blank field makes it POPULATED`() {
        assertEquals(
            MetadataFillState.POPULATED,
            classifyMetadataFillState(title = null, artist = null, album = "Some Album", metadataIsNull = false),
        )
        assertEquals(
            MetadataFillState.POPULATED,
            classifyMetadataFillState(title = "Track", artist = null, album = null, metadataIsNull = false),
        )
    }
}
