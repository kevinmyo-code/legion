package com.kevin.legion.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R5-2: leaving the scan pane before the camera is ready never binds the camera afterwards. */
class CarLinkScanBindGateTest {
    @Test fun `a gate disposed before the camera is ready runs nothing`() {
        val gate = ScanBindGate()
        gate.dispose()
        var bound = false
        gate.runIfLive { bound = true }
        assertFalse(bound)
    }

    @Test fun `a live gate binds`() {
        var bound = false
        ScanBindGate().runIfLive { bound = true }
        assertTrue(bound)
    }

    @Test fun `a scanner that cannot start is reported by class name and never asked for a client`() {
        var asked = false
        var why: String? = null
        val client = openScanner(
            init = { throw IllegalStateException("ml kit could not start") },
            client = { asked = true; "client" },
            failed = { why = it },
        )
        assertTrue(client == null && !asked)
        assertTrue(why == "IllegalStateException")
    }

    @Test fun `a missing ML Kit method is caught too, not only exceptions`() {
        var why: String? = null
        val client = openScanner(init = { throw NoSuchMethodError("x") }, client = { "client" }, failed = { why = it })
        assertTrue(client == null && why == "NoSuchMethodError")
    }

    @Test fun `a client that cannot be made is reported the same way`() {
        var why: String? = null
        val client = openScanner<String>(
            init = {},
            client = { throw UnsupportedOperationException() },
            failed = { why = it },
        )
        assertTrue(client == null && why == "UnsupportedOperationException")
    }

    @Test fun `the ML Kit pin check refuses another version, a range or an unpinned library line`() {
        val good = "mlkitBarcode = \"17.3.0\"\n" +
            "mlkit-barcode-scanning = { group = \"com.google.mlkit\", name = \"barcode-scanning\", " +
            "version.ref = \"mlkitBarcode\" }\n"
        assertTrue(mlKitPinned(good))
        assertTrue(!mlKitPinned(good.replace("17.3.0", "17.3.1")))
        assertTrue(!mlKitPinned(good.replace("17.3.0", "17.+")))
        assertTrue(!mlKitPinned(good.replace("version.ref = \"mlkitBarcode\"", "version = \"17.4.0\"")))
    }

    @Test fun `ML Kit stays pinned at the version whose sdkinternal start was checked`() {
        // initializeIfNeeded lives in ML Kit's sdkinternal package: an upgrade must re-check it.
        fun read(path: String) = listOf(java.io.File("../$path"), java.io.File(path)).first { it.exists() }.readText()
        assertTrue(mlKitPinned(read("gradle/libs.versions.toml")))
        assertTrue("com.google.mlkit:" !in read("app/build.gradle.kts"))
    }

    /** The catalog pins ML Kit to exactly 17.3.0 and the library line takes that pin, not its own. */
    private fun mlKitPinned(catalog: String): Boolean =
        Regex("""(?m)^mlkitBarcode = "17\.3\.0"$""").containsMatchIn(catalog) &&
            Regex("""(?m)^mlkit-barcode-scanning = \{.*version\.ref = "mlkitBarcode" \}$""").containsMatchIn(catalog)

    @Test fun `a camera that cannot be bound is reported by class name`() {
        var why: String? = null
        val bound = startOrReport<String>(
            step = { throw IllegalArgumentException("no such camera") },
            failed = { why = it },
        )
        assertTrue(bound == null && why == "IllegalArgumentException")
    }

    @Test fun `the back camera is used when there is one, otherwise any camera, otherwise none`() {
        assertTrue(pickCamera("back", hasBack = true, others = listOf("front")) == "back")
        assertTrue(pickCamera("back", hasBack = false, others = listOf("front", "usb")) == "front")
        assertTrue(pickCamera("back", hasBack = false, others = emptyList()) == null)
    }

    @Test fun `leaving the pane releases exactly the use cases it bound, once`() {
        val uses = PaneCameraUses<String>()
        uses.record(listOf("preview", "analysis"))
        assertTrue(uses.release() == listOf("preview", "analysis"))
        assertTrue(uses.release().isEmpty())
    }
}
