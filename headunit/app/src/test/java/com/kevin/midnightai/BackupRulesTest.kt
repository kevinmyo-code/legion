package com.kevin.midnightai

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Security review H1 (2026-11-20): the car link and engine prefs never leave the unit in a backup
 * or a device transfer (a restored ciphertext never decrypts, which left a unit "paired" with an
 * unreadable key). Parses both rule files as shipped.
 */
class BackupRulesTest {
    private fun res(name: String): String {
        val candidates = listOf("src/main/res/xml/$name", "app/src/main/res/xml/$name")
        return candidates.map(::File).first { it.exists() }.readText()
    }

    private fun excluded(xml: String, section: String?, file: String): Boolean {
        val scope = if (section == null) xml else Regex("<$section>(.*?)</$section>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1).orEmpty()
        return Regex("""<exclude\s+domain="sharedpref"\s+path="${Regex.escape(file)}"\s*/>""").containsMatchIn(scope)
    }

    @Test fun pairingAndEnginePrefsAreExcludedEverywhere() {
        val modern = res("data_extraction_rules.xml")
        val legacy = res("backup_rules.xml")
        for (file in listOf("car_link.xml", "engine_config.xml")) {
            assertTrue("cloud $file", excluded(modern, "cloud-backup", file))
            assertTrue("transfer $file", excluded(modern, "device-transfer", file))
            assertTrue("legacy $file", excluded(legacy, null, file))
        }
    }
}
