package com.kevin.legion.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Plain JVM - [DatabaseSnapshot.Metadata]'s JSON codec only touches `org.json`, same as
 * [SyncCodec] (see its own doc comment for why that's a plain-JVM-testable dependency). */
class DatabaseSnapshotMetadataTest {

    @Test
    fun `metadata round-trips through JSON bytes`() {
        val original = DatabaseSnapshot.Metadata(timestampMs = 1_723_500_000_000L, schemaVersion = 15, rowCount = 48_213L)

        val decoded = DatabaseSnapshot.Metadata.fromJsonBytes(original.toJsonBytes())

        assertEquals(original, decoded)
    }

    @Test
    fun `malformed metadata bytes decode to null rather than throwing`() {
        assertNull(DatabaseSnapshot.Metadata.fromJsonBytes("not json at all".toByteArray()))
        assertNull(DatabaseSnapshot.Metadata.fromJsonBytes("{}".toByteArray()))
        assertNull(DatabaseSnapshot.Metadata.fromJsonBytes(ByteArray(0)))
    }
}
