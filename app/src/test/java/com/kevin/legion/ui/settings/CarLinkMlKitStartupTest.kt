package com.kevin.legion.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * N-2: ML Kit's MlKitInitProvider would run at EVERY app start (a content provider), for a scanner a
 * phone may never open. It is removed from the merged manifest, and the scan pane initialises ML Kit
 * itself, before it asks for the barcode client.
 */
class CarLinkMlKitStartupTest {
    private fun read(path: String) =
        listOf(File(path), File("app/$path")).first { it.exists() }.readText()

    @Test fun `ML Kit's start-up provider is removed from the manifest`() {
        val manifest = read("src/main/AndroidManifest.xml")
        val i = manifest.indexOf("com.google.mlkit.common.internal.MlKitInitProvider")
        assertTrue("no removal entry for MlKitInitProvider", i >= 0)
        val tag = manifest.substring(manifest.lastIndexOf("<provider", i), manifest.indexOf(">", i) + 1)
        assertTrue(tag, tag.contains("tools:node=\"remove\""))
    }

    @Test fun `the scan pane initialises ML Kit before asking for the barcode client`() {
        val pane = read("src/main/java/com/kevin/legion/ui/settings/CarLinkQrScanPane.kt")
        val init = pane.indexOf("MlKitContext.initializeIfNeeded(")
        val client = pane.indexOf("BarcodeScanning.getClient(")
        assertTrue("no lazy ML Kit initialisation in the pane", init >= 0)
        assertTrue("initialise before the client", init < client)
    }
}
