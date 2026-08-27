package com.kevin.legion.ledger

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [IngestPipeline.stripAccountPrefix] / [IngestPipeline.reattachAccountPrefix] are inverses, and
 * this is the regression coverage for the bug that shipped without it: `ingested_files.driveFileId`
 * is stored STRIPPED (ticket 03's key-stability rationale), but Drive's own SAF provider only
 * resolves the FULL, prefixed document id - a real folder scan never notices because
 * [com.kevin.legion.service.IngestScanner] opens bytes with the child's original unstripped id and
 * only stores the stripped copy; [ReingestDryRun] rebuilds a URI FROM the stored key and has to put
 * the prefix back on. Plain JVM - both functions are pure string logic, no Android.
 */
class IngestPipelineAccountPrefixTest {
    @Test
    fun `reattaching the prefix from the tree's own document id restores the original full id`() {
        val fullDocumentId = "acc=1;doc=encoded=o_GGpalKBAfTlOkt93tJoIsI7YFNkl6XCfyg_e8xx5M5xaqOppo="
        val stripped = IngestPipeline.stripAccountPrefix(fullDocumentId)

        // The stripped copy is what really gets stored - confirm the round trip starts from that.
        assertEquals("doc=encoded=o_GGpalKBAfTlOkt93tJoIsI7YFNkl6XCfyg_e8xx5M5xaqOppo=", stripped)

        val restored = IngestPipeline.reattachAccountPrefix(stripped, treeDocumentId = fullDocumentId)
        assertEquals(fullDocumentId, restored)
    }

    @Test
    fun `a tree document id with no account prefix leaves the stored id untouched`() {
        // A non-Drive SAF provider (local storage, another cloud app) prints no acc= prefix at
        // all - there is nothing to reattach, and fabricating one would address a file that does
        // not exist.
        val stored = "doc=1234"
        val restored = IngestPipeline.reattachAccountPrefix(stored, treeDocumentId = "primary:Download")
        assertEquals(stored, restored)
    }

    @Test
    fun `an already-prefixed stored id is not double-prefixed`() {
        val alreadyFull = "acc=1;doc=encoded=abc="
        val restored = IngestPipeline.reattachAccountPrefix(alreadyFull, treeDocumentId = "acc=1;root")
        assertEquals(alreadyFull, restored)
    }
}
