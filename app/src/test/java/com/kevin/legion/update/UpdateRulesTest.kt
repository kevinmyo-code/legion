package com.kevin.legion.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The updater's pure decisions (alfred-ota ticket 05): every refusal is a sentence. */
class UpdateRulesTest {

    private val signer = "a".repeat(64)
    private val release = ReleaseInfo("r1", 263091200, "2026-11-05-ab12cd3", "b".repeat(64), 1000, "fixes X", signer)
    private val installed = InstalledApp(263081200, "2026-11-04-0000000", setOf(signer))
    private val apk = ApkFacts("com.kevin.legion", 263091200, setOf(signer))

    @Test
    fun `version compare never downgrades and a debug build never updates`() {
        assertEquals(UpdateRules.Offer.DebugBuild, UpdateRules.compare(1, release))
        assertEquals(UpdateRules.Offer.UpToDate, UpdateRules.compare(263091200, release))
        assertEquals(UpdateRules.Offer.UpToDate, UpdateRules.compare(263101200, release))
        assertEquals(UpdateRules.Offer.UpToDate, UpdateRules.compare(263081200, null))
        assertEquals(UpdateRules.Offer.Newer(release), UpdateRules.compare(263081200, release))
    }

    @Test
    fun `the wifi gate`() {
        assertNull(UpdateRules.downloadRefusal(online = true, unmetered = true, allowMobileData = false))
        assertNull(UpdateRules.downloadRefusal(online = true, unmetered = false, allowMobileData = true))
        val metered = UpdateRules.downloadRefusal(online = true, unmetered = false, allowMobileData = false)!!
        assertTrue(metered, metered.startsWith("Not downloaded") && metered.contains("Wi-Fi only"))
        assertTrue(UpdateRules.downloadRefusal(online = false, unmetered = false, allowMobileData = true)!!.contains("offline"))
    }

    @Test
    fun `a file that matches everything passes`() {
        assertNull(UpdateRules.verifyRefusal(release, release.sha256, apk, installed, "com.kevin.legion"))
    }

    @Test
    fun `a sha256 mismatch refuses`() {
        val why = UpdateRules.verifyRefusal(release, "c".repeat(64), apk, installed, "com.kevin.legion")!!
        assertTrue(why, why.startsWith("Not installed") && why.contains("checksum") && why.contains("discarded"))
    }

    @Test
    fun `a signer mismatch refuses`() {
        val stranger = apk.copy(signerSha256s = setOf("d".repeat(64)))
        val why = UpdateRules.verifyRefusal(release, release.sha256, stranger, installed, "com.kevin.legion")!!
        assertTrue(why, why.contains("not signed with the same key"))
        // No readable signer on the installed side (Android 8 and below) never passes.
        assertNotNull(UpdateRules.verifyRefusal(release, release.sha256, apk, installed.copy(signerSha256s = emptySet()), "com.kevin.legion"))
        // The release naming another signer than the file's refuses too.
        val other = release.copy(signerSha256 = "e".repeat(64))
        assertTrue(UpdateRules.verifyRefusal(other, other.sha256, apk, installed, "com.kevin.legion")!!.contains("signer"))
    }

    @Test
    fun `another app, an unreadable file or an older build refuses`() {
        assertTrue(UpdateRules.verifyRefusal(release, release.sha256, null, installed, "com.kevin.legion")!!.contains("could not read"))
        assertTrue(
            UpdateRules.verifyRefusal(release, release.sha256, apk.copy(packageName = "x.y"), installed, "com.kevin.legion")!!
                .contains("different app"),
        )
        val older = apk.copy(versionCode = 263071200)
        assertTrue(UpdateRules.verifyRefusal(release, release.sha256, older, installed, "com.kevin.legion")!!.contains("never downgrades"))
    }

    @Test
    fun `the install gate refuses Matrix, the car and a missing grant in words`() {
        assertNull(UpdateRules.installRefusal(matrixOn = false, carConnected = false, mayInstallPackages = true))
        assertTrue(UpdateRules.installRefusal(true, false, true)!!.contains("Matrix"))
        assertTrue(UpdateRules.installRefusal(false, true, true)!!.contains("car is connected"))
        assertTrue(UpdateRules.installRefusal(false, false, false)!!.contains("not allowed to install"))
    }

    @Test
    fun `the ask waits for a pause outside a conversation, Matrix and the car`() {
        assertTrue(UpdateRules.mayAskNow(conversationBusy = false, matrixOn = false, carConnected = false))
        assertFalse(UpdateRules.mayAskNow(conversationBusy = true, matrixOn = false, carConnected = false))
        assertFalse(UpdateRules.mayAskNow(conversationBusy = false, matrixOn = true, carConnected = false))
        assertFalse(UpdateRules.mayAskNow(conversationBusy = false, matrixOn = false, carConnected = true))
    }

    @Test
    fun `a voice yes counts only right after the offer`() {
        assertFalse(UpdateRules.offerStillOpen(null, 10_000))
        assertTrue(UpdateRules.offerStillOpen(10_000, 10_000 + UpdateRules.OFFER_WINDOW_MS))
        assertFalse(UpdateRules.offerStillOpen(10_000, 10_001 + UpdateRules.OFFER_WINDOW_MS))
        assertFalse(UpdateRules.offerStillOpen(20_000, 10_000))
        assertFalse("asked and installed in one breath", UpdateRules.offerStillOpen(10_000, 10_500))
        assertTrue(UpdateRules.offerStillOpen(10_000, 10_000 + UpdateRules.MIN_OFFER_AGE_MS))
    }

    @Test
    fun `the release JSON parses as the engine serves it`() {
        val json = """{"id":"9b2c","app":"phone","version_code":263091200,"version_name":"2026-11-05-ab12cd3",
            "sha256":"${"b".repeat(64)}","size_bytes":201000000,"notes":"fixes X","signer_sha256":"$signer",
            "published_at":"2026-11-05T12:00:00Z"}"""
        val parsed = ReleaseJson.parseRelease(json)!!
        assertEquals(263091200L, parsed.versionCode)
        assertEquals("fixes X", parsed.notes)
        assertNull(ReleaseJson.parseRelease(json.replace("\"version_code\":263091200", "\"version_code\":\"1\"")))
        val link = ReleaseJson.parseDownload(
            """{"url":"https://storage.googleapis.com/x","expires_at":"t","sha256":"${"b".repeat(64)}",
            "size_bytes":5,"version_code":263091200}""",
        )!!
        assertEquals(5L, link.sizeBytes)
        assertNull(ReleaseJson.parseDownload("""{"url":"http://plain","sha256":"${"b".repeat(64)}","size_bytes":5,"version_code":1}"""))
    }
}
