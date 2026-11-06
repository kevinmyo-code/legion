package com.kevin.midnightai.update

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The head unit updater's pure decisions (alfred-ota ticket 08), mirroring the phone's
 * `UpdateRulesTest`: every refusal is a sentence. Adds the moving-car gate, the ABI check and the
 * hotspot default the phone does not have.
 */
class UpdateRulesTest {

    @get:Rule val tmp = TemporaryFolder()

    private val pkg = "com.kevin.midnightai"
    private val abis = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
    private val signer = "a".repeat(64)
    private val release = ReleaseInfo("r1", 263101200, "1.0+ab12cd3", "b".repeat(64), 1000, "fixes X", signer)
    private val installed = InstalledApp(263091200, "1.0+0000000", setOf(signer))
    private val apk = ApkFacts(pkg, 263101200, setOf(signer), setOf("arm64-v8a"))

    @Test
    fun `version compare never downgrades and a debug build never updates`() {
        assertEquals(UpdateRules.Offer.DebugBuild, UpdateRules.compare(1, release))
        assertEquals(UpdateRules.Offer.UpToDate, UpdateRules.compare(263101200, release))
        assertEquals(UpdateRules.Offer.UpToDate, UpdateRules.compare(263111200, release))
        assertEquals(UpdateRules.Offer.UpToDate, UpdateRules.compare(263091200, null))
        assertEquals(UpdateRules.Offer.Newer(release), UpdateRules.compare(263091200, release))
    }

    @Test
    fun `the network gate allows the hotspot unless switched to Wi-Fi only`() {
        assertNull(UpdateRules.downloadRefusal(online = true, unmetered = true, allowMetered = false))
        assertNull(UpdateRules.downloadRefusal(online = true, unmetered = false, allowMetered = true))
        val metered = UpdateRules.downloadRefusal(online = true, unmetered = false, allowMetered = false)!!
        assertTrue(metered, metered.startsWith("Not downloaded") && metered.contains("hotspot"))
        assertTrue(UpdateRules.downloadRefusal(online = false, unmetered = false, allowMetered = true)!!.contains("offline"))
    }

    @Test
    fun `a file that matches everything passes`() {
        assertNull(UpdateRules.verifyRefusal(release, release.sha256, apk, installed, pkg, abis))
    }

    @Test
    fun `a sha256 mismatch refuses`() {
        val why = UpdateRules.verifyRefusal(release, "c".repeat(64), apk, installed, pkg, abis)!!
        assertTrue(why, why.startsWith("Not installed") && why.contains("checksum") && why.contains("discarded"))
    }

    @Test
    fun `a signer mismatch refuses`() {
        val stranger = apk.copy(signerSha256s = setOf("d".repeat(64)))
        val why = UpdateRules.verifyRefusal(release, release.sha256, stranger, installed, pkg, abis)!!
        assertTrue(why, why.contains("not signed with the same key"))
        // No readable signer on the installed side (Android 8 and below) never passes.
        assertNotNull(UpdateRules.verifyRefusal(release, release.sha256, apk, installed.copy(signerSha256s = emptySet()), pkg, abis))
        // The release naming another signer than the file's refuses too.
        val other = release.copy(signerSha256 = "e".repeat(64))
        assertTrue(UpdateRules.verifyRefusal(other, other.sha256, apk, installed, pkg, abis)!!.contains("signer"))
    }

    @Test
    fun `another app, an unreadable file or an older build refuses`() {
        assertTrue(UpdateRules.verifyRefusal(release, release.sha256, null, installed, pkg, abis)!!.contains("could not read"))
        // The phone's APK carries the same debug key: only the package name tells them apart.
        assertTrue(
            UpdateRules.verifyRefusal(release, release.sha256, apk.copy(packageName = "com.kevin.legion"), installed, pkg, abis)!!
                .contains("different app"),
        )
        val older = apk.copy(versionCode = 263081200)
        assertTrue(UpdateRules.verifyRefusal(release, release.sha256, older, installed, pkg, abis)!!.contains("never downgrades"))
    }

    @Test
    fun `an APK for another ABI refuses, one with no native code passes`() {
        val x86 = apk.copy(abis = setOf("x86_64"))
        val why = UpdateRules.verifyRefusal(release, release.sha256, x86, installed, pkg, abis)!!
        assertTrue(why, why.contains("built for x86_64") && why.contains("arm64-v8a"))
        // armeabi-v7a on a unit that also runs 32-bit code is fine.
        assertNull(UpdateRules.verifyRefusal(release, release.sha256, apk.copy(abis = setOf("armeabi-v7a")), installed, pkg, abis))
        assertNull(UpdateRules.verifyRefusal(release, release.sha256, apk.copy(abis = emptySet()), installed, pkg, abis))
    }

    @Test
    fun `the install gate refuses a moving car and a missing grant in words`() {
        assertNull(UpdateRules.installRefusal(driving = false, mayInstallPackages = true))
        assertTrue(UpdateRules.installRefusal(driving = true, mayInstallPackages = true)!!.contains("car is moving"))
        assertTrue(UpdateRules.installRefusal(driving = false, mayInstallPackages = false)!!.contains("not allowed to install"))
    }

    @Test
    fun `driving holds for two minutes after the last moving reading, and no reading is parked`() {
        assertFalse(UpdateRules.isDriving(nowMs = 1_000_000, lastMovingAtMs = 0))
        assertTrue(UpdateRules.isDriving(1_000_000, 1_000_000))
        assertTrue("a red light is still driving", UpdateRules.isDriving(1_000_000 + UpdateRules.DRIVING_HOLD_MS, 1_000_000))
        assertFalse(UpdateRules.isDriving(1_000_001 + UpdateRules.DRIVING_HOLD_MS, 1_000_000))
        assertFalse(UpdateRules.mayPromptNow(driving = true))
        assertTrue(UpdateRules.mayPromptNow(driving = false))
    }

    @Test
    fun `driving state stamps only readings above walking pace`() {
        DrivingState.reset()
        try {
            DrivingState.note(null, 5_000)
            DrivingState.note(3.0, 5_000)
            assertEquals(0L, DrivingState.lastMovingAtMs)
            DrivingState.note(40.0, 10_000)
            DrivingState.note(0.0, 20_000)
            assertEquals("a stop does not erase the last moving stamp", 10_000L, DrivingState.lastMovingAtMs)
            assertTrue(DrivingState.isDriving(20_000))
            assertFalse(DrivingState.isDriving(10_001 + UpdateRules.DRIVING_HOLD_MS))
        } finally {
            DrivingState.reset()
        }
    }

    @Test
    fun `ABIs are read from the APK's lib folders`() {
        val file = zip("split.apk", "AndroidManifest.xml", "lib/arm64-v8a/libx.so", "lib/arm64-v8a/liby.so", "classes.dex")
        assertEquals(setOf("arm64-v8a"), ApkAbis.of(file))
        assertEquals(emptySet<String>(), ApkAbis.of(zip("plain.apk", "classes.dex")))
        assertEquals(emptySet<String>(), ApkAbis.of(tmp.newFile("not-a-zip.apk").apply { writeText("nope") }))
    }

    @Test
    fun `the release JSON parses as the engine serves it`() {
        val json = """{"id":"9b2c","app":"headunit","version_code":263101200,"version_name":"1.0+ab12cd3",
            "sha256":"${"b".repeat(64)}","size_bytes":87000000,"notes":"fixes X","signer_sha256":"$signer",
            "published_at":"2026-11-06T12:00:00Z"}"""
        val parsed = ReleaseJson.parseRelease(json)!!
        assertEquals(263101200L, parsed.versionCode)
        assertEquals("fixes X", parsed.notes)
        assertNull(ReleaseJson.parseRelease(json.replace("\"version_code\":263101200", "\"version_code\":\"1\"")))
        val link = ReleaseJson.parseDownload(
            """{"url":"https://storage.googleapis.com/x","expires_at":"t","sha256":"${"b".repeat(64)}",
            "size_bytes":5,"version_code":263101200}""",
        )!!
        assertEquals(5L, link.sizeBytes)
        assertNull(ReleaseJson.parseDownload("""{"url":"http://plain","sha256":"${"b".repeat(64)}","size_bytes":5,"version_code":1}"""))
    }

    private fun zip(name: String, vararg entries: String): File {
        val file = File(tmp.root, name)
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { out.putNextEntry(ZipEntry(it)); out.write(1); out.closeEntry() }
        }
        return file
    }
}
