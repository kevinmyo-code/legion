package com.kevin.midnightai.link

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review L-b: the owner PIN record never leaves the unit in a backup or a device transfer. */
class BackupRulesTest {
    private fun rules(name: String) = File("src/main/res/xml/$name").readText()

    @Test fun theOwnerPinIsExcludedFromBackupAndTransfer() {
        val modern = rules("data_extraction_rules.xml")
        val excluded = Regex("""<exclude domain="sharedpref" path="owner_pin.xml" />""").findAll(modern).count()
        assertTrue("cloud backup and device transfer must both exclude owner_pin.xml", excluded == 2)
        assertTrue(rules("backup_rules.xml").contains("""<exclude domain="sharedpref" path="owner_pin.xml" />"""))
    }
}
